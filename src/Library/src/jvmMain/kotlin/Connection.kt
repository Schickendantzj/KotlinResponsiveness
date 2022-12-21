package org.responsiveness.main

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.system.measureNanoTime
import kotlin.text.toByteArray

// TODO Should this be an interface?
abstract class Connection {
    public val id: Int = Connection.getID()
    public var probes: ArrayList<Probe> = ArrayList<Probe>()
    public val listenerFactory: ListenerFactory = ListenerFactory()

    protected val client: HttpClient
    protected var isLoaded: Boolean = false

    private companion object {
        private var ID = AtomicInteger(0)
        fun getID(): Int {
            return ID.getAndIncrement().toInt()
        }
    }

    init {
        // Initialize the client, this forces single connections since we do not have control over which connection
        // we assign requests
        client = io.ktor.client.HttpClient(OkHttp) {
            engine {
                config {
                    // TODO FIND A WAY TO FORCE H2
                    //protocols(listOf<Protocol>(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.H2_PRIOR_KNOWLEDGE))
                    //protocols(listOf<Protocol>(okhttp3.Protocol.H2_PRIOR_KNOWLEDGE)) // Clear Text

                    // Create a new connection pool to make sure we don't reuse the old ones
                    // Force max connections to be 1
                    // TODO Test that it is only 1 connection
                    connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))

                    // Add listener to measure RTT
                    eventListenerFactory(listenerFactory)

                    // In the case we time out early for some reason
                    // connectTimeout(30, TimeUnit.SECONDS)
                    // readTimeout(30, TimeUnit.SECONDS)
                    // writeTimeout(30, TimeUnit.SECONDS)

                    // Change the Dispatcher to make sure we only ever use one connection
                    var d = Dispatcher()
                    d.maxRequestsPerHost = 10
                    dispatcher(d)

                    // Add interceptor to add a tag to all requests (did not manage to do so with the ktor HTTPclient)
                    // TODO Figure out which to use (network or regular and inline or class)
//                      addInterceptor(MyInterceptor())
//                      addNetworkInterceptor { chain ->
//                          var request = chain.request()
//                          val id: String = UUID.randomUUID().toString()
//                          val builder = request.newBuilder().tag(id)
//                          val out = builder.build()
//                          chain.proceed(out)
//                      }
                    addInterceptor { chain ->
                        val request = chain.request()
                        chain.connection()
                        val builder = request.newBuilder().removeHeader("ID")
                        val out = builder.build()
                        chain.proceed(out)
                    }
                }
            }
        }
    }

    // Load the connection (can only be called once)
    abstract suspend fun loadConnection(): Boolean

    // Probe the connection
    fun startProbes(url: String, waitTime: Long = 100, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) {
        println("PROBES STARTED")
        runBlocking {
            launch() {
                while (true) {
                    // TODO Utilize eventListener Factory
                    val probe = Probe(url, System.nanoTime())
                    client.get(probe.url) {
                        header("ID", "P${probe.id} : Connection${id}")
                    }
                    delay(timeUnit.toMillis(waitTime))
                }
            }
        }
    }
}

class DownloadConnection(private val url: String) : Connection() {
    override suspend fun loadConnection(): Boolean {
        // Can only call this once and return true
        if (isLoaded) {
            return false
        }
        isLoaded = true
        // TODO Confirm Async?
        with(CoroutineScope(coroutineContext)) {
            launch(newSingleThreadContext("D${id}")) {
                // TODO MAKE THIS A FUNCTION
                var exitLogging = false

                // Make sure that the object is thread safe
                var internalBytesRead =
                    AtomicLong(0L) // This is for the body stream returning bytes read on each read call
                var internalBytesReadTotal = AtomicLong(0L)
                var bytesRead = AtomicLong(0L)
                var bytesReadTotal = AtomicLong(0L)
                var startTime = AtomicLong(System.nanoTime())
                var updateTime = AtomicLong(System.nanoTime())

                // Coroutine to log throughput every 500ms
                launch(currentCoroutineContext()) {
                    while (!exitLogging) {
                        delay(500L) // Delay/Pause for 500ms
                        val currentTime = System.nanoTime() - updateTime.get()
                        println("D${id}: Received ${bytesRead.get()} bytes | ${bytesToMegabytes(bytesRead.get())} megabytes in ${currentTime} ns | ${currentTime / 1_000_000} ms")
                        println("D${id}: Read ${internalBytesRead.get()} bytes | ${bytesToMegabytes(internalBytesRead.get())} megabytes in ${currentTime} ns | ${currentTime / 1_000_000} ms")

                        // Update Values
                        bytesReadTotal.getAndAdd(bytesRead.get())
                        bytesRead.set(0)

                        // Update Values for internal
                        internalBytesReadTotal.getAndAdd(bytesRead.get())
                        internalBytesRead.set(0)

                        updateTime.set(System.nanoTime())
                    }
                }

                var lastBytesReadTotal = 0L
                client.prepareGet(url) {
                    onDownload { _bytesReadTotal, contentLength ->
                        bytesRead.getAndAdd(_bytesReadTotal - lastBytesReadTotal)
                        lastBytesReadTotal = _bytesReadTotal
                    }
                    header("ID", "D${id}")
                }.execute { httpResponse: HttpResponse ->
                    val channel: ByteReadChannel = httpResponse.body()

                    // To read the packets from the stream channel
                    var elapsedTimeReading = 0L
                    while (!channel.isClosedForRead) {
                        val packet =
                            channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong()) //  // DEFAULT_BUFFER_SIZE = 8192 as tested
                        while (!packet.isEmpty) {
                            val bytes: ByteArray
                            val timeInNanos = measureNanoTime {
                                bytes = packet.readBytes()
                            }
                            elapsedTimeReading += timeInNanos
                            internalBytesRead.getAndAdd(bytes.size.toLong())
                            //println("Read ${bytes.size} bytes from ${httpResponse.contentLength()} in ${timeInNanos} ns")
                        }
                    }
                    println("Success!")
                }
            }
        }
        return true
    }
}

class UploadConnection(private val url: String) : Connection() {

    override suspend fun loadConnection(): Boolean {
        // Can only call this once and return true
        if (isLoaded) {
            return false
        }
        isLoaded = true
        // TODO Confirm Async?
        with(CoroutineScope(coroutineContext)) {
            launch(newSingleThreadContext("U${id}")) {
                // TODO MAKE THIS A FUNCTION
                var exitLogging = false

                // Make sure that the object is thread safe
                var internalBytesRead =
                    AtomicLong(0L) // This is for the observable stream returning bytes read on each read call
                var internalBytesReadTotal = AtomicLong(0L)
                var bytesRead = AtomicLong(0L)
                var bytesReadTotal = AtomicLong(0L)
                var startTime = AtomicLong(System.nanoTime())
                var updateTime = AtomicLong(System.nanoTime())

                // Coroutine to log throughput every 500ms
                launch(currentCoroutineContext()) {
                    while (!exitLogging) {
                        delay(500L) // Delay/Pause for 500ms
                        val currentTime = System.nanoTime() - updateTime.get()
                        println("U${id}: Sent ${bytesRead.get()} bytes | ${bytesToMegabytes(bytesRead.get())} megabytes in ${currentTime} ns | ${currentTime / 1_000_000} ms")
                        println("U${id}: Read ${internalBytesRead.get()} bytes | ${bytesToMegabytes(internalBytesRead.get())} megabytes in ${currentTime} ns | ${currentTime / 1_000_000} ms")

                        // Update Values
                        bytesReadTotal.getAndAdd(bytesRead.get())
                        bytesRead.set(0)

                        // Update Values for internal
                        internalBytesReadTotal.getAndAdd(bytesRead.get())
                        internalBytesRead.set(0)

                        // Reset timer
                        updateTime.set(System.nanoTime())
                    }
                }

                var bytesSentTotalLast = 0L
                client.post(url) {
                    onUpload { bytesSentTotal, contentLength ->
                        //println("U${id}: Sent ${bytesSentTotal}")

                        bytesRead.getAndAdd(bytesSentTotal - bytesSentTotalLast)
                        bytesSentTotalLast = bytesSentTotal
                    }
                    contentType(ContentType.Application.OctetStream)
                    setBody(JvmMain.ObservableInputStream(SEND.toByteArray()) { bytes ->
                        internalBytesRead.getAndAdd(bytes)
                        // println("U${id}: Read ${bytes}")
                    })
                    header("ID", "U${id}")
                }
            }
        }
        return true
    }
}

class Probe(val url: String, val startTime: Long) {
    val id: Int = Probe.getID()
    var finishTime: Long = -1L
    val connectionStats: ConnectionStats = ConnectionStats(startTime)

    private companion object {
        private var ID = AtomicInteger(0)
        fun getID(): Int {
            return ID.getAndIncrement().toInt()
        }
    }
}

class ConnectionStats(val startTime: Long) {
    // startTime is upon the creation of the stats nothing more.
    // Time is in nanoseconds, unadjusted
    // All default times are -1, error times -2

    // Reference for updated info: https://square.github.io/okhttp/3.x/okhttp/okhttp3/EventListener.html#EventListener--
    // Client request processing
    var callStart: Long = -1  // Call is enqueued or executed by a client
    var callEnd: Long = -1    // Call has ended completely

    // DNS lookup
    var dnsStart: Long = -1   // DNS lookup started
    var dnsEnd: Long = -1     // DNS lookup finished

    // TLS connection
    var tlsStart: Long = -1   // TLS setup started
    var tlsEnd: Long = -1     // TLS setup finished

    // Request
    var requestHeaderStart: Long = -1  // Request Header Started
    var requestHeaderEnd: Long = -1    // Request Header Ended
    var requestBodyStart: Long = -1    // Request Body Started (Should be close to requestHeaderEnd)
    var requestBodyEnd: Long = -1      // Request Body Ended

    // Response
    var responseHeaderStart: Long = -1  // Response Header Started
    var responseHeaderEnd: Long = -1    // Response Header Ended
    var responseBodyStart: Long = -1    // Response Body Started (Should be close to responseHeaderEnd)
    var responseBodyEnd: Long = -1      // Response Body Ended

    // Socket connection
    var socketStart: Long = -1      // Socket command started
    var socketEnd: Long = -1        // Socket command ended
    var socketAcquired: Long = -1   // Socket acquired // -2 for failed acquire
}

// FROM: https://square.github.io/okhttp/features/events/
// We can use an event listener factory to create a unique event listener for EACH call
class ListenerFactory(): EventListener.Factory {
    var loadListener: Listener? = null
    var probeListeners = HashMap<String, Listener>()

    // Says it is an error to mutate Call here. DO NOT MUTATE Call.
    override fun create(call: Call): EventListener {
        var id = call.request().headers.get("ID")
        if (id == null) {
            println("ERROR: NO ID HEADER IN REQUEST")
            id = ""
        }
        val listener = Listener(id, System.nanoTime())
        if (id.startsWith("P")) {
            if (probeListeners.containsKey(id)) {
                println("CRITICAL ERROR: PROBE ID IS NOT UNIQUE")
            } else {
                probeListeners[id] = listener
            }
        } else {
            loadListener = listener
        }
        return listener
    }

    class Listener(val ID: String, val startTime: Long) : EventListener() {
        val connectionStats = ConnectionStats(startTime) // TODO: consider using the same startTime as passed vs System nanotime
        // https://square.github.io/okhttp/3.x/okhttp/okhttp3/EventListener.html#EventListener--

        // OKHttp3 Call events
        override fun callStart(call: Call) {
            connectionStats.callStart = System.nanoTime()
            println("${this.ID}: Call started/executed/enqueued by a client")
        }

        override fun callEnd(call: Call) {
            connectionStats.callEnd = System.nanoTime()
            println("${this.ID}: Call ended")
        }

        override fun callFailed(call: Call, ioe: IOException) {
            connectionStats.callEnd = System.nanoTime()
            println("${this.ID}: Call Failed")
        }

        // DNS events
        override fun dnsStart(call: Call, domainName: String) {
            connectionStats.dnsStart = System.nanoTime()
            println("${this.ID}: DNS Search Started: ${JvmMain.currTimeDelta(startTime)} ms | for ${domainName}")
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            connectionStats.dnsEnd = System.nanoTime()
            println("${this.ID}: DNS Search Returned: ${JvmMain.currTimeDelta(startTime)} ms | for ${domainName} | ${inetAddressList}")
        }

        // TLS events
        override fun secureConnectStart(call: Call) {
            connectionStats.tlsStart = System.nanoTime()
            println("${this.ID}: TLS connection started: ${JvmMain.currTimeDelta(startTime)} ms")
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            connectionStats.tlsEnd = System.nanoTime()
            if (handshake != null) {
                println("${this.ID}: TLS connection finished: ${JvmMain.currTimeDelta(startTime)} ms | version: ${handshake.tlsVersion}")
            } else {
                println("${this.ID}: TLS connection failed: ${JvmMain.currTimeDelta(startTime)} ms")
            }
        }

        // Request events
        override fun requestHeadersStart(call: Call) {
            connectionStats.requestHeaderStart = System.nanoTime()
            println("${this.ID}: RequestHeaderStarted: ${JvmMain.currTimeDelta(startTime)} ms")
        }

        override fun requestHeadersEnd(call: Call, request: Request) {
            connectionStats.requestHeaderEnd = System.nanoTime()
            println("${this.ID}: RequestHeaderEnded: ${JvmMain.currTimeDelta(startTime)} ms")
        }

        override fun requestBodyStart(call: Call) {
            connectionStats.requestBodyStart = System.nanoTime()
            println("${this.ID}: RequestBodyStarted: ${JvmMain.currTimeDelta(startTime)} ms")
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            connectionStats.requestBodyEnd = System.nanoTime()
            println("${this.ID}: RequestBodyEnded: ${JvmMain.currTimeDelta(startTime)} ms")
        }

        // requestFailed

        // Response events
        override fun responseHeadersStart(call: Call) {
            connectionStats.responseHeaderStart = System.nanoTime()
            println("${this.ID}: ResponseHeaderStarted: ${JvmMain.currTimeDelta(startTime)} ms")
        }

        override fun responseBodyStart(call: Call) {
            connectionStats.responseBodyStart = System.nanoTime()
            println("${this.ID}: ResponseBodyStarted: ${JvmMain.currTimeDelta(startTime)} ms")
        }


        // Socket events
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectionStats.socketStart = System.nanoTime()
            println("${this.ID}: connectStart SOCKET START ATTEMPT: ${JvmMain.currTimeDelta(startTime)} ms")
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            println("${this.ID}: connectEnd SOCKET STARTED: ${JvmMain.currTimeDelta(startTime)} ms | protocol: ${protocol}")
        }

        override fun connectionAcquired(call: Call, connection: okhttp3.Connection) {
            connectionStats.socketAcquired = System.nanoTime()
            println("${this.ID}: SOCKET ACQUIRED: ${JvmMain.currTimeDelta(startTime)} ms | Local Port: ${connection.socket().localPort}")
        }



    }
}