package org.responsiveness.main

import kotlinx.coroutines.*


import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
//import io.ktor.client.engine.cio.*
import io.ktor.client.engine.java.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.cio.Request
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.utils.io.* // ByteReadChannel
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.EventListener
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.TypeVariable
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.system.measureNanoTime

import java.net.InetAddress
import java.security.cert.Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList


//var io: io.ktor.client.plugins.HttpCallValidator

// CONFIG
val URL_TO_GET =  "https://mensura.cdn-apple.com/api/v1/gm/large" // "https://rpm.obs.cr/large/" //
val URL_TO_POST =  "https://mensura.cdn-apple.com/api/v1/gm/slurp" //"https://rpm.obs.cr:443/slurp" //

// GLOBAL USED
var SEND = ""


actual object MainFactory {
    actual fun createMain(): Main = JvmMain
}

// UTILS
fun bytesToMegabytes(bytes: Long): Float {
    return bytes.toFloat() / 1024 / 1024
}



// Class meant to be used to stop all launched coroutines across the board "safely"
class Context(stop: Boolean) {
    private class Stopper (
        var name: String,
        var reason: String="Context Stopper named: ${name}, has no reason provided",
        var stop :Boolean=false
    ) {
        // Equals when name and reason are the same.
        override fun equals(other: Any?): Boolean {
            return (other is Stopper) && other.name == this.name && other.reason == this.name
        }
    }

    // TODO: Consider using an array instead
    private var stoppers:ArrayList<Stopper> = ArrayList<Stopper>()

    // Returns false if the stopper already exists; true on add
    fun registerStopper(name: String, reason: String=""): Boolean {
        val stopper: Stopper
        if (reason != "") {
            stopper = Stopper(name, reason)
        } else {
            stopper = Stopper(name)
        }

        if (stopper in stoppers) { return false } // Do not add if exists
        stoppers.add(stopper)

        return true

    }

    // If stopper exists returns true and triggers stopper (starts stops); false if stopper does not exist
    fun activateStopper(name: String, reason: String=""): Boolean {
        val stopper: Stopper
        if (reason != "") {
            stopper = Stopper(name, reason)
        } else {
            stopper = Stopper(name)
        }

        for (i in this.stoppers) {
            if (i == stopper) {
                i.stop = true
                return true
            }
        }
        return false
    }

    // Returns true if any stoppers are true
    fun stop(): Boolean {
        for (stopper in this.stoppers) {
            if (stopper.stop) {
                return true
            }
        }
        return false
    }

    // Returns a string of the reason why we are stopping
    fun reason(): String {
        // TODO MAKE THIS PRETTY
        var out = ""
        for (stopper in this.stoppers) {
            if (stopper.stop) {
                out += "\n" + stopper.reason
            }
        }
        return out
    }
}

object JvmMain : Main {

    //https://gaumala.com/posts/2020-01-27-working-with-streams-kotlin.html
    class ObservableInputStream(private val chunk: ByteArray, private val onBytesRead: (Long) -> Unit): InputStream() {
        private var bytesRead: Long = 0

        @Throws(IOException::class)
        override fun read(): Int {
            println("empty read was called with ${chunk.size} returned")
            // might need to limit (4 gigs)
            val res = chunk.size
            if (res > -1) {
                bytesRead++
            }
            onBytesRead(bytesRead)
            return res
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray): Int {
            // might need to limit (4 gigs)
            for (i in 0..b.size) {
                b[i] = chunk[i % chunk.size] // Loop since chunks
            }
            var res = b.size
            if (res > -1) {
                bytesRead += res
                onBytesRead(res.toLong())
            }
            return res
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            // The Java Ktor client only reads using this function (as tested).
            // TODO MAKE SURE THIS IS CORRECT FOR THE OFFSET
            var res = chunk.size // Make it equal or less to chunk.size for granularity on the reads.
            if (len < chunk.size) {
                res = len
            }
            for (i in 0..(res - 1)) {
                b[off + i] = chunk[i]
            }
            if (res > -1) {
                bytesRead += res
                onBytesRead(res.toLong())
            }
            return res
        }

//        @Throws(IOException::class)
//        override fun skip(n: Long): Long {
//            val res = wrapped.skip(n)
//            if (res > -1) {
//                bytesRead += res
//                onBytesRead(bytesRead)
//            }
//            return res
//        }

//        @Throws(IOException::class)
//        override fun available(): Int {
//            return 4_000_000_000
//        }

        override fun available(): Int {
            // Stop Available to lower to keep the buffer from filling on the client side
            // Give it a smaller number like 600
            println("Available got called")
            return 600
        }


//        override fun markSupported(): Boolean {
//            return wrapped.markSupported()
//        }
//
//        override fun mark(readlimit: Int) {
//            wrapped.mark(readlimit)
//        }
//
//        @Throws(IOException::class)
//        override fun reset() {
//            wrapped.reset()
//        }

        @Throws(IOException::class)
        override fun close() {
//            wrapped.close()
        }
    }

    class ObservableOutputStream(private val chunk: ByteArray,
                                 private val onBytesWritten: (Long) -> Unit): OutputStream() {
        private var bytesWritten: Long = 0

        @Throws(IOException::class)
        override fun write(b: Int) {
            bytesWritten++
            onBytesWritten(bytesWritten)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray) {
            for (i in 0..b.size) {
                b[i] = chunk[i % chunk.size] // Loop since chunks
            }
            bytesWritten += b.size.toLong()
            onBytesWritten(bytesWritten)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray, off: Int, len: Int) {
            //wrapped.write(b, off, len)
            bytesWritten += len.toLong()
            onBytesWritten(bytesWritten)
        }

        @Throws(IOException::class)
        override fun flush() {
            //wrapped.flush()
        }

        @Throws(IOException::class)
        override fun close() {
            //wrapped.close()
        }
    }

    // Should have a class for the connection holding, Client, URL, Context, Active, ID.
    suspend fun startDownload(client: io.ktor.client.HttpClient, url: String, context: Context, id: Int, coroutineContext: CoroutineContext) {
        withContext(coroutineContext) {
            launch() {
                // TODO MAKE THIS A FUNCTION
                var exitLogging = false

                // Make sure that the object is thread safe
                var internalBytesRead = AtomicLong(0L) // This is for the body stream returning bytes read on each read call
                var internalBytesReadTotal = AtomicLong(0L)
                var bytesRead = AtomicLong(0L)
                var bytesReadTotal = AtomicLong(0L)
                var startTime = AtomicLong(System.nanoTime())
                var updateTime = AtomicLong(System.nanoTime())

                // Coroutine to log throughput every 500ms
                launch(currentCoroutineContext()) {
                    while(!exitLogging) {
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
                    setAttributes {
                        put(AttributeKey<String>("tag"), "D${id}")
                    }
                }.execute { httpResponse: HttpResponse ->
                    val channel: ByteReadChannel = httpResponse.body()

                    // To read the packets from the stream channel
                    var elapsedTimeReading = 0L
                    while (!channel.isClosedForRead) {
                        val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong()) //  // DEFAULT_BUFFER_SIZE = 8192 as tested
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
    }

    suspend fun startUpload(client: io.ktor.client.HttpClient, url: String, context: Context, id: Int, coroutineContext: CoroutineContext) {
        withContext(coroutineContext) {
            launch() {
                // TODO MAKE THIS A FUNCTION
                var exitLogging = false

                // Make sure that the object is thread safe
                var internalBytesRead = AtomicLong(0L) // This is for the observable stream returning bytes read on each read call
                var internalBytesReadTotal = AtomicLong(0L)
                var bytesRead = AtomicLong(0L)
                var bytesReadTotal = AtomicLong(0L)
                var startTime = AtomicLong(System.nanoTime())
                var updateTime = AtomicLong(System.nanoTime())

                // Coroutine to log throughput every 500ms
                launch(currentCoroutineContext()) {
                    while(!exitLogging) {
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
                    setAttributes {
                        put(AttributeKey<String>("tag"), "U${id}")
                    }
                }
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
        var socketStart = -1      // Socket command started
        var socketEnd = -1        // Socket command ended
        var socketAcquired = -1   // Socket acquired // -2 for failed acquire
    }



    class MyListener(val startTime: Long): EventListener() {
        // https://square.github.io/okhttp/3.x/okhttp/okhttp3/EventListener.html#EventListener--
        val connectionStats: MutableMap<String, ConnectionStats> = mutableMapOf<String, ConnectionStats>()
        override fun callStart(call: Call) {
            // This will never have the correct tag because it is passed before it gets called
            val tag = call.request().tag()
            println("${tag}: Call started/executed/enqueued by a client")
        }

        override fun callEnd(call: Call) {
            println("Call finished")
        }

        override fun callFailed(call: Call, ioe: IOException) {
            println("Call failed")
        }

        override fun dnsStart(call: Call, domainName: String) {
            val tag = call.request().tag()
            println("${tag}: DNS Search Started: ${currTimeDelta(startTime)} ms | for ${domainName}")
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            val tag = call.request().tag()
            println("${tag}: DNS Search Returned: ${currTimeDelta(startTime)} ms | for ${domainName}")
        }

        override fun secureConnectStart(call: Call) {
            val tag = call.request().tag()
            println("${tag}: TLS connection started: ${currTimeDelta(startTime)} ms")
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            val tag = call.request().tag()
            if (handshake != null) {
                println("${tag}: TLS connection finished: ${currTimeDelta(startTime)} ms | version: ${handshake.tlsVersion}")
            }
        }

        override fun responseBodyStart(call: Call) {
            val tag = call.request().tag()
            println("${tag}: ResponseBodyStarted: ${currTimeDelta(startTime)} ms")
        }



    }

    // Returns delta timeStart, currentTime(System.nanoTime()) normalized to ms
    fun currTimeDelta(startTime: Long): Double {
        return ((System.nanoTime() - startTime) / 1_000_000.0)
    }


//    class MyInterceptor() : Interceptor {
//        override fun intercept(chain: Interceptor.Chain): Response {
//
//        }
//
//    }


    override fun main(args: Array<String>): Unit {
        // Initialize global send
        SEND = "x"
        for (i in 0..16) {
            SEND += SEND
        }
        println("Send length: ${SEND.length}")

        // JAVA CLIENT
        // From https://ktor.io/docs/http-client-engines.html#java
//        val client = io.ktor.client.HttpClient(Java) {
//            engine {
//                protocolVersion = java.net.http.HttpClient.Version.HTTP_2
//            }
//        }


        // OKHTTP Client

        val client = io.ktor.client.HttpClient(MyOkHttp) {
            engine {
                config {
                    // TODO FIND A WAY TO FORCE H2
                    //protocols(listOf<Protocol>(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.H2_PRIOR_KNOWLEDGE))
                    //protocols(listOf<Protocol>(okhttp3.Protocol.H2_PRIOR_KNOWLEDGE))

                    // Add listener to measure RTT
                    eventListener(MyListener(System.nanoTime()))
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    writeTimeout(30, TimeUnit.SECONDS)
                    var d = Dispatcher()
                    d.maxRequestsPerHost = 30
                    dispatcher(d)
                    // Add interceptor to add a tag to all requests (did not manage to do so with the ktor HTTPclient)

//                    addInterceptor(MyInterceptor())
//                    addNetworkInterceptor { chain ->
//                        var request = chain.request()
//                        val id: String = UUID.randomUUID().toString()
//                        val builder = request.newBuilder().tag(id)
//                        val out = builder.build()
//                        chain.proceed(out)
//                    }
                }
            }
        }

        // Here I am going to intercept the send call to define my own
        // Using https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-okhttp/jvm/src/io/ktor/client/engine/okhttp/OkHttpEngine.kt
        client.plugin(HttpSend).intercept { request ->
            println(request.attributes.allKeys)

            execute(request)
        }


        // DEFAULT CLIENT (NO HTTP2)
//        val client = io.ktor.client.HttpClient(CIO)


        var context = Context(false)
        runBlocking {
            // Download Starts
            for (i in 1..4) {

                launch {
                    Thread.sleep(500)
                    println("Starting download: D${i}")
                    startDownload(client, URL_TO_GET, context, i, this.coroutineContext)
                }
            }
            // Upload Starts
            for (i in 1..5) {
                launch {
                    println("Starting upload: U${i}")
                    startUpload(client, URL_TO_POST, context, i, this.coroutineContext)
                }
            }
        }

//        runBlocking {
//            client.prepareGet(URL_TO_GET).execute { httpResponse: HttpResponse ->
//                val channel: ByteReadChannel = httpResponse.body()
//                var bytesRead = 0L
//                var bytesReadTotal = 0L
//                var startTime = System.nanoTime()
//                var updateTime = System.nanoTime()
//
//                // Coroutine to log throughput every 500ms
//                launch(currentCoroutineContext()) {
//                    while(!channel.isClosedForRead) {
//                        delay(500L) // Delay/Pause for 500ms
//                        val currentTime = System.nanoTime() - updateTime
//                        println("Received ${bytesRead} bytes | ${bytesToMegabytes(bytesRead)} megabytes in ${currentTime} ns | ${currentTime / 1_000_000} ms")
//
//                        // Update Values
//                        bytesReadTotal += bytesRead
//                        bytesRead = 0
//                        updateTime = System.nanoTime()
//                    }
//                }
//
//                // To read the packets from the stream channel
//                var elapsedTimeReading = 0L
//                while (!channel.isClosedForRead) {
//                    val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
//                    while (!packet.isEmpty) {
//                        val bytes: ByteArray
//                        val timeInNanos = measureNanoTime {
//                            bytes = packet.readBytes()
//                        }
//                        elapsedTimeReading += timeInNanos
//                        bytesRead += bytes.size
//                        //println("Read ${bytes.size} bytes from ${httpResponse.contentLength()} in ${timeInNanos} ns")
//                    }
//                }
//                println("Success!")
//            }
//        }
    }
}