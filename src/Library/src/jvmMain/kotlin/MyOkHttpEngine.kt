package org.responsiveness.main

/*

 *
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 *

 *
 * This has been slightly modified to include a tag variable check and addition to okhttp call
 * Source: https://github.com/ktorio/ktor/tree/main/ktor-client/ktor-client-okhttp/jvm/src/io/ktor/client/engine/okhttp
 *

 */


import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.http.Headers
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.http.HttpMethod
import okio.*
import java.io.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.*
import kotlin.coroutines.*
import okhttp3.Headers as OkHttpHeaders





/**
 * A configuration for the [OkHttp] client engine.
 */
public class OkHttpConfig : HttpClientEngineConfig() {

    internal var config: OkHttpClient.Builder.() -> Unit = {
        followRedirects(false)
        followSslRedirects(false)

        retryOnConnectionFailure(true)
    }

    /**
     * Allows you to specify a preconfigured [OkHttpClient] instance.
     */
    public var preconfigured: OkHttpClient? = null

    /**
     * Specifies the size of cache that keeps recently used [OkHttpClient] instances.
     * Set this property to `0` to disable caching.
     */
    public var clientCacheSize: Int = 10

    /**
     * Specifies the [WebSocket.Factory] used to create a [WebSocket] instance.
     * Otherwise, [OkHttpClient] is used directly.
     */
    public var webSocketFactory: WebSocket.Factory? = null

    /**
     * Configures [OkHttpClient] using [OkHttpClient.Builder].
     */
    public fun config(block: OkHttpClient.Builder.() -> Unit) {
        val oldConfig = config
        config = {
            oldConfig()
            block()
        }
    }

    /**
     * Adds an [Interceptor] to the [OkHttp] client.
     */
    public fun addInterceptor(interceptor: Interceptor) {
        config {
            addInterceptor(interceptor)
        }
    }

    /**
     * Adds a network [Interceptor] to the [OkHttp] client.
     */
    public fun addNetworkInterceptor(interceptor: Interceptor) {
        config {
            addNetworkInterceptor(interceptor)
        }
    }
}

public object MyOkHttp : HttpClientEngineFactory<OkHttpConfig> {
    override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine =
        MyOkHttpEngine(OkHttpConfig().apply(block))


@Suppress("KDocMissingDocumentation")
@OptIn(InternalAPI::class, DelicateCoroutinesApi::class)
public class MyOkHttpEngine(override val config: OkHttpConfig) : HttpClientEngineBase("ktor-okhttp") {

    public override val dispatcher: CoroutineDispatcher by lazy {
        Dispatchers.clientDispatcher(
            config.threadsCount,
            "ktor-okhttp-dispatcher"
        )
    }

    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> =
        setOf(HttpTimeout, WebSocketCapability)

    private val requestsJob: CoroutineContext

    override val coroutineContext: CoroutineContext

    /**
     * Cache that keeps least recently used [OkHttpClient] instances.
     */
    private val clientCache = createLRUCache(::createOkHttpClient, {}, config.clientCacheSize)

    init {
        val parent = super.coroutineContext[Job]!!
        requestsJob = SilentSupervisor(parent)
        coroutineContext = super.coroutineContext + requestsJob

        @OptIn(ExperimentalCoroutinesApi::class)
        GlobalScope.launch(super.coroutineContext, start = CoroutineStart.ATOMIC) {
            try {
                requestsJob[Job]!!.join()
            } finally {
                clientCache.forEach { (_, client) ->
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
                @Suppress("BlockingMethodInNonBlockingContext")
                (dispatcher as Closeable).close()
            }
        }
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val engineRequest = data.convertToOkHttpRequest(callContext)

        val requestEngine = clientCache[data.getCapabilityOrNull(HttpTimeout)]
            ?: error("OkHttpClient can't be constructed because HttpTimeout plugin is not installed")

//        return if (data.isUpgradeRequest()) {
//            executeWebSocketRequest(requestEngine, engineRequest, callContext)
//        } else {
//            executeHttpRequest(requestEngine, engineRequest, callContext, data)
//        }
        return executeHttpRequest(requestEngine, engineRequest, callContext, data)
    }

    override fun close() {
        super.close()
        (requestsJob[Job] as CompletableJob).complete()
    }

    private suspend fun executeWebSocketRequest(
        engine: OkHttpClient,
        engineRequest: Request,
        callContext: CoroutineContext
    ): Unit {
//        val requestTime = GMTDate()
//        val session = OkHttpWebsocketSession(
//            engine,
//            config.webSocketFactory ?: engine,
//            engineRequest,
//            callContext
//        ).apply { start() }
//
//        val originResponse = session.originResponse.await()
//        return buildResponseData(originResponse, requestTime, session, callContext)
    }

    private suspend fun executeHttpRequest(
        engine: OkHttpClient,
        engineRequest: Request,
        callContext: CoroutineContext,
        requestData: HttpRequestData
    ): HttpResponseData {
        val requestTime = GMTDate()
        val response = engine.execute(engineRequest, requestData)

        val body = response.body
        callContext[Job]!!.invokeOnCompletion { body?.close() }

        val responseContent = body?.source()?.toChannel(callContext, requestData) ?: ByteReadChannel.Empty
        return buildResponseData(response, requestTime, responseContent, callContext)
    }

    private fun buildResponseData(
        response: Response,
        requestTime: GMTDate,
        body: Any,
        callContext: CoroutineContext
    ): HttpResponseData {
        val status = HttpStatusCode(response.code, response.message)
        val version = response.protocol.fromOkHttp()
        val headers = response.headers.fromOkHttp()

        return HttpResponseData(status, requestTime, headers, version, body, callContext)
    }

    internal fun OkHttpHeaders.fromOkHttp(): Headers = object : Headers {
        override val caseInsensitiveName: Boolean = true

        override fun getAll(name: String): List<String>? = this@fromOkHttp.values(name).takeIf { it.isNotEmpty() }

        override fun names(): Set<String> = this@fromOkHttp.names()

        override fun entries(): Set<Map.Entry<String, List<String>>> = this@fromOkHttp.toMultimap().entries

        override fun isEmpty(): Boolean = this@fromOkHttp.size == 0
    }

    @Suppress("DEPRECATION")
    internal fun Protocol.fromOkHttp(): HttpProtocolVersion = when (this) {
        Protocol.HTTP_1_0 -> HttpProtocolVersion.HTTP_1_0
        Protocol.HTTP_1_1 -> HttpProtocolVersion.HTTP_1_1
        Protocol.SPDY_3 -> HttpProtocolVersion.SPDY_3
        Protocol.HTTP_2 -> HttpProtocolVersion.HTTP_2_0
        Protocol.H2_PRIOR_KNOWLEDGE -> HttpProtocolVersion.HTTP_2_0
        Protocol.QUIC -> HttpProtocolVersion.QUIC
    }
    private companion object {
        /**
         * It's an artificial prototype object to be used to create actual clients and eliminate the following issue:
         * https://github.com/square/okhttp/issues/3372.
         */
        val okHttpClientPrototype: OkHttpClient by lazy {
            OkHttpClient.Builder().build()
        }
    }

    private fun createOkHttpClient(timeoutExtension: HttpTimeout.HttpTimeoutCapabilityConfiguration?): OkHttpClient {
        val builder = (config.preconfigured ?: okHttpClientPrototype).newBuilder()

        builder.dispatcher(Dispatcher())
        builder.apply(config.config)
        config.proxy?.let { builder.proxy(it) }
        timeoutExtension?.let {
            builder.setupTimeoutAttributes(it)
        }

        return builder.build()
    }
}

internal suspend fun OkHttpClient.execute(
    request: Request,
    requestData: HttpRequestData
): Response = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)

    call.enqueue(OkHttpCallback(requestData, continuation))

    continuation.invokeOnCancellation {
        call.cancel()
    }
}

private class OkHttpCallback(
    private val requestData: HttpRequestData,
    private val continuation: CancellableContinuation<Response>
) : Callback {
    override fun onFailure(call: Call, e: IOException) {
        if (continuation.isCancelled) {
            return
        }

        continuation.resumeWithException(mapOkHttpException(requestData, e))
    }

    override fun onResponse(call: Call, response: Response) {
        if (!call.isCanceled()) {
            continuation.resume(response)
        }
    }
}

private fun mapOkHttpException(
    requestData: HttpRequestData,
    origin: IOException
): Throwable = when (val cause = origin.unwrapSuppressed()) {
    is SocketTimeoutException ->
        if (cause.isConnectException()) {
            ConnectTimeoutException(requestData, cause)
        } else {
            SocketTimeoutException(requestData, cause)
        }
    else -> cause
}

@OptIn(DelicateCoroutinesApi::class)
private fun BufferedSource.toChannel(context: CoroutineContext, requestData: HttpRequestData): ByteReadChannel =
    GlobalScope.writer(context) {
        use { source ->
            var lastRead = 0
            while (source.isOpen && context.isActive && lastRead >= 0) {
                channel.write { buffer ->
                    lastRead = try {
                        source.read(buffer)
                    } catch (cause: Throwable) {
                        throw mapExceptions(cause, requestData)
                    }
                }
            }
        }
    }.channel

private fun mapExceptions(cause: Throwable, request: HttpRequestData): Throwable = when (cause) {
    is java.net.SocketTimeoutException -> SocketTimeoutException(request, cause)
    else -> cause
}

@OptIn(InternalAPI::class)
private fun HttpRequestData.convertToOkHttpRequest(callContext: CoroutineContext): Request {
    val builder = Request.Builder()
    val tag = this.attributes[AttributeKey("tag")]
    if (tag is String)
        println("Successful tag inplaced")
    with(builder) {
        url(url.toString())
        tag(tag)
        mergeHeaders(headers, body) { key, value ->
            if (key == HttpHeaders.ContentLength) return@mergeHeaders

            addHeader(key, value)
        }

        val bodyBytes = if (HttpMethod.permitsRequestBody(method.value)) {
            body.convertToOkHttpBody(callContext)
        } else null

        method(method.value, bodyBytes)
    }

    return builder.build()
}

@OptIn(DelicateCoroutinesApi::class)
internal fun OutgoingContent.convertToOkHttpBody(callContext: CoroutineContext): RequestBody = when (this) {
    is OutgoingContent.ByteArrayContent -> bytes().let {
        it.toRequestBody(null, 0, it.size)
    }
    is OutgoingContent.ReadChannelContent -> StreamRequestBody(contentLength) { readFrom() }
    is OutgoingContent.WriteChannelContent -> {
        StreamRequestBody(contentLength) { GlobalScope.writer(callContext) { writeTo(channel) }.channel }
    }
    is OutgoingContent.NoContent -> ByteArray(0).toRequestBody(null, 0, 0)
    else -> throw UnsupportedContentTypeException(this)
}

/**
 * Update [OkHttpClient.Builder] setting timeout configuration taken from
 * [HttpTimeout.HttpTimeoutCapabilityConfiguration].
 */
@OptIn(InternalAPI::class)
private fun OkHttpClient.Builder.setupTimeoutAttributes(
    timeoutAttributes: HttpTimeout.HttpTimeoutCapabilityConfiguration
): OkHttpClient.Builder {
    timeoutAttributes.connectTimeoutMillis?.let {
        connectTimeout(convertLongTimeoutToLongWithInfiniteAsZero(it), TimeUnit.MILLISECONDS)
    }
    timeoutAttributes.socketTimeoutMillis?.let {
        readTimeout(convertLongTimeoutToLongWithInfiniteAsZero(it), TimeUnit.MILLISECONDS)
        writeTimeout(convertLongTimeoutToLongWithInfiniteAsZero(it), TimeUnit.MILLISECONDS)
    }
    return this
}

private fun IOException.isConnectException() =
    message?.contains("connect", ignoreCase = true) == true

private fun IOException.unwrapSuppressed(): Throwable {
    if (suppressed.isNotEmpty()) return suppressed[0]
    return this
}

internal class StreamRequestBody(
    private val contentLength: Long?,
    private val block: () -> ByteReadChannel
) : RequestBody() {

    override fun contentType(): MediaType? = null

    override fun writeTo(sink: BufferedSink) {
        block().toInputStream().source().use {
            sink.writeAll(it)
        }
    }

    override fun contentLength(): Long = contentLength ?: -1

    override fun isOneShot(): Boolean = true
}}