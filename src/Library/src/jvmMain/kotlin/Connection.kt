package org.responsiveness.main

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import kotlin.coroutines.coroutineContext
import kotlin.system.measureNanoTime
import kotlin.text.toByteArray

// UTILS
fun bytesToMegabytes(bytes: Long): Float {
    return bytes.toFloat() / 1024 / 1024
}
// Utility function for output
// Nanoseconds to MS
fun ms(time: Long): Float{
    return time / 1_000_000f
}

// TODO rewrite throughput as a request rather than a channel

class ProbeDataPoint (override var value: Float, var connectionID: String,
                      var probeID: String, var measurementType: String) : DataPoint {
    // The "value" is the RTT(ms)

    override var ID: String = "${connectionID}:${probeID}"

    override fun toCSV(): String {
        return "${connectionID}, ${probeID}, ${value}, ${measurementType}\n"
    }

    override fun getHeaderCSV(): String {
        return "connectionID, probeID, rtt(ms), measurementType\n"
    }
}

// instant should be instant bytes read, time should be in ms
class ThroughputDataPoint (var instant: Long, var connectionID: String, val time: Float) : DataPoint {
    // The "value" is the Bytes
    override var value = bytesToMegabytes(instant) / (time / 1000)

    override var ID: String = connectionID

    override fun toCSV(): String {
        return "${connectionID}, ${value}\n"
    }

    override fun getHeaderCSV(): String {
        return "connectionID, throughput(MB/s)\n"
    }
}

class ThroughputHookDataPoint(val block: () -> DataPoint): HookDataPoint {
    override fun getValue(): DataPoint {
        return block()
    }

}


// TODO Should this be an interface?
abstract class Connection(latencyChannel: Channel<DataPoint>, val connectionID: String) {
    // public val id: Int = Connection.getID() // TODO("Should this be used? We pass the connectionID, do we have any need for forcing uniqueness this way")
    public var probes: ArrayList<Probe> = ArrayList<Probe>()
    public val listenerFactory: ListenerFactory = ListenerFactory(latencyChannel, connectionID)

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
                    // TODO('FIND A WAY TO FORCE H2; Is this even necessary? 1/26 According to latest no it should be decided
                    // by "http" or "https"')
                    //protocols(listOf<Protocol>(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.H2_PRIOR_KNOWLEDGE))
                    //protocols(listOf<Protocol>(okhttp3.Protocol.H2_PRIOR_KNOWLEDGE)) // Clear Text

                    // Create a new connection pool to make sure we don't reuse the old ones
                    // Force max connections to be 1
                    // TODO Test that it is only 1 connection; Seems so
                    val connectionPool_ = ConnectionPool(2, 60, TimeUnit.SECONDS)
                    connectionPool_.evictAll() // Force Evict any current
                    connectionPool(connectionPool_)

                    connectTimeout(Duration.ZERO) // No Timeout
                    readTimeout(Duration.ZERO) // No Timeout
                    writeTimeout(Duration.ZERO) // No Timeout

                    // Add listener to measure RTT
                    eventListenerFactory(listenerFactory)

                    // In the case we time out early for some reason
                    // connectTimeout(30, TimeUnit.SECONDS)
                    // readTimeout(30, TimeUnit.SECONDS)
                    // writeTimeout(30, TimeUnit.SECONDS)

                    // Change the Dispatcher to make sure we only ever use one connection
                    var d = Dispatcher()
                    // TODO we may need more because we may have more than 10 simultaneous probes on one connection (bad delay/dropped packet)
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

    // Load the connection (SHOULD only be called once)
    abstract suspend fun loadConnection(): Boolean

    // Probe the connection
    fun startProbes(url: String, waitTime: Long = 100, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) {
        println("${connectionID}: PROBES STARTED")
        runBlocking {
            launch() {
                while (true) {
                    // TODO Utilize eventListener Factory
                    val probe = Probe(url, System.nanoTime())
                    client.get(probe.url) {
                        header("ID", "P${probe.id}")
                    }
                    delay(timeUnit.toMillis(waitTime))
                }
            }
        }
    }
}


class DownloadConnection(private val url: String, latencyChannel: Channel<DataPoint>, val throughputChannel: Channel<HookDataPoint>, connectionID: String) : Connection(latencyChannel, connectionID) {
    override suspend fun loadConnection(): Boolean {
        // Can only call this once and return true
        if (isLoaded) {
            return false
        }
        isLoaded = true
        // TODO Confirm Async?
        with(CoroutineScope(coroutineContext)) {
            launch(newSingleThreadContext("loadConnection:${connectionID}")) {
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

                throughputChannel.send(ThroughputHookDataPoint {
                    val currentTime = System.nanoTime() - updateTime.getAndSet(System.nanoTime())
                    val localByteRead = bytesRead.get()
                    // Update Values
                    bytesReadTotal.getAndAdd(bytesRead.get())
                    bytesRead.set(0)

                    // Update Values for internal
                    internalBytesReadTotal.getAndAdd(bytesRead.get())
                    internalBytesRead.set(0)


                    ThroughputDataPoint(localByteRead, connectionID, (currentTime / 1_000_000).toFloat())
                })
                // Coroutine to log throughput every 500ms
//                launch(currentCoroutineContext()) {
//                    while (!exitLogging) {
//                        delay(1000L) // Delay/Pause for 1000ms
//                        val currentTime = System.nanoTime() - updateTime.get()
//                        // These help to determine if the Bytes read from the body are the close to
//                        // those reported from the onDownload callback.
//                        // println("D${connectionID}: Received ${bytesRead.get()} bytes | ${bytesToMegabytes(bytesRead.get())} megabytes in ${currentTime} ns | ${currentTime / 1_000_000} ms")
//                        // println("D${connectionID}: Read ${internalBytesRead.get()} bytes | ${bytesToMegabytes(internalBytesRead.get())} megabytes in ${currentTime} ns | ${currentTime / 1_000_000} ms")
//
//                        // Update Values
//                        bytesReadTotal.getAndAdd(bytesRead.get())
//                        bytesRead.set(0)
//
//                        // Update Values for internal
//                        internalBytesReadTotal.getAndAdd(bytesRead.get())
//                        internalBytesRead.set(0)
//
//                        updateTime.set(System.nanoTime())
//                    }
//                }

                var lastBytesReadTotal = 0L
                client.prepareGet(url) {
                    onDownload { _bytesReadTotal, contentLength ->
                        bytesRead.getAndAdd(_bytesReadTotal - lastBytesReadTotal)
                        lastBytesReadTotal = _bytesReadTotal
                    }
                    header("ID", connectionID)
                }.execute { httpResponse: HttpResponse ->
                    val channel: ByteReadChannel = httpResponse.body()

                    // To read the packets from the stream channel
                    var elapsedTimeReading = 0L
                    while (!channel.isClosedForRead) {
                        val packet =
                            channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong() * 2L) //  // DEFAULT_BUFFER_SIZE = 8192 as tested
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
                    println("Reached After Download Call")
                }
            }
        }
        return true
    }
}

class UploadConnection(private val url: String, latencyChannel: Channel<DataPoint>, val throughputChannel: Channel<HookDataPoint>, connectionID: String) : Connection(latencyChannel, connectionID) {

    override suspend fun loadConnection(): Boolean {
        // Can only call this once and return true
        if (isLoaded) {
            return false
        }
        isLoaded = true

        // TODO Confirm Async?
        with(CoroutineScope(coroutineContext)) {
            launch(newSingleThreadContext("loadConnection:${connectionID}")) {
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

                throughputChannel.send(ThroughputHookDataPoint {
                    val currentTime = System.nanoTime() - updateTime.getAndSet(System.nanoTime())
                    val localByteRead = bytesRead.get()
                    // Update Values
                    bytesReadTotal.getAndAdd(bytesRead.get())
                    bytesRead.set(0)

                    // Update Values for internal
                    internalBytesReadTotal.getAndAdd(bytesRead.get())
                    internalBytesRead.set(0)


                    ThroughputDataPoint(localByteRead, connectionID, (currentTime / 1_000_000).toFloat())
                })

                // Coroutine to log throughput every 500ms
//                launch(currentCoroutineContext()) {
//                    while (!exitLogging) {
//                        delay(1000L) // Delay/Pause for 1000ms
//                        val currentTime = System.nanoTime() - updateTime.get()
//                        // These help to determine if the Bytes read from the InputStream in the body are the close to
//                        // those reported from the onUpload callback.
//                        // println("U${id}: Sent ${bytesRead.get()} bytes | ${bytesToMegabytes(bytesRead.get())} megabytes in ${currentTime} ns | ${currentTime / 1_000_000} ms")
//                        // println("U${id}: Read ${internalBytesRead.get()} bytes | ${bytesToMegabytes(internalBytesRead.get())} megabytes in ${currentTime} ns | ${currentTime / 1_000_000} ms")
//
//                        // Update Values
//                        bytesReadTotal.getAndAdd(bytesRead.get())
//                        bytesRead.set(0)
//
//                        // Update Values for internal
//                        internalBytesReadTotal.getAndAdd(bytesRead.get())
//                        internalBytesRead.set(0)
//
//                        // Reset timer
//                        updateTime.set(System.nanoTime())
//                    }
//                }

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
                    header("ID", connectionID)
                }
            }
        }
        return true
    }
}

class ForeignConnection(private val url: String, latencyChannel: Channel<DataPoint>, connectionID: String) : Connection(latencyChannel, connectionID) {
    companion object {
        val connections = ArrayList<okhttp3.Connection>()
    }

    // TODO CLEAN
    override suspend fun loadConnection(): Boolean {
        // Can only call this once and return true
        if (isLoaded) {
            return false
        }
        isLoaded = true

        // TODO Confirm Async?
        with(CoroutineScope(coroutineContext)) {
            runBlocking() {
                client.get(url) {
                    header("ID", connectionID)
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

// Reference for events: https://square.github.io/okhttp/features/events/
// We can use an event listener factory to create a unique event listener for EACH call
class ListenerFactory(val channel: Channel<DataPoint>, val connectionID: String): EventListener.Factory {
    var loadListener: Listener? = null
    var probeListeners = HashMap<String, Listener>()
    var loadConnection: okhttp3.Connection? = null


    // Says it is an error to mutate Call here. DO NOT MUTATE Call.
    override fun create(call: Call): EventListener {
        var callID = call.request().headers.get("ID")
        if (callID == null) {
            println("ERROR: NO ID HEADER IN REQUEST")
            callID = ""
        }
        // Regardless of what happens we have to return a listener
        // This one does nothing with the connection
        var listener = Listener(callID, connectionID, System.nanoTime(), channel) { connection ->
            true
        }

        // If it is a probe
        if (callID.startsWith("P")) {
            if (probeListeners.containsKey(callID)) {
                println("CRITICAL ERROR: PROBE ID IS NOT UNIQUE")
            } else {
                listener = Listener(callID, connectionID, System.nanoTime(), channel) { connection ->
                    (loadConnection != null) && (connection.socket() == loadConnection!!.socket())
                }
                probeListeners[callID] = listener
            }

        // If it is a data load call
        } else {
            listener = Listener(callID, connectionID, System.nanoTime(), channel) { connection ->
                // https://docs.oracle.com/javase/8/docs/api/java/net/Socket.html?is-external=true
                // connection.socket().setPerformancePreferences(0,2,0) // No effect after socket acquired
                loadConnection = connection
                (connection != null)
            }
        }
        return listener
    }

    class Listener(val callID: String, val connectionID: String, val startTime: Long, val latencyChannel: Channel<DataPoint>,
        val gotConnectionCallback: (okhttp3.Connection) -> Boolean) : EventListener() {
        val connectionStats = ConnectionStats(startTime)
        lateinit var connection: okhttp3.Connection
        var writeResults = true // Used to stop probes sent on wrong connections from writing

        // https://square.github.io/okhttp/3.x/okhttp/okhttp3/EventListener.html#EventListener--

        // OKHttp3 Call events
        override fun callStart(call: Call) {
            connectionStats.callStart = System.nanoTime()
            // println("${this.ID}: Call started/executed/enqueued by a client")
        }

        override fun callEnd(call: Call) {
            connectionStats.callEnd = System.nanoTime()
            // println("${this.ID}: Call ended")
        }

        override fun callFailed(call: Call, ioe: IOException) {
            connectionStats.callEnd = System.nanoTime()
            println("${this.callID}:${this.connectionID}: Call Failed | ${ioe}")
        }

        // DNS events
        override fun dnsStart(call: Call, domainName: String) {
            connectionStats.dnsStart = System.nanoTime()
            // println("${this.ID}: DNS Search Started: ${JvmMain.currTimeDelta(startTime)} ms | for ${domainName}")
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            connectionStats.dnsEnd = System.nanoTime()
            // println("${this.ID}: DNS Search Returned: ${JvmMain.currTimeDelta(startTime)} ms | for ${domainName} | ${inetAddressList}")
            // println("${this.callID}:${this.connectionID}: DNS search took: ${ms(connectionStats.dnsEnd - connectionStats.dnsStart)} ms")
        }

        // TLS events
        override fun secureConnectStart(call: Call) {
            connectionStats.tlsStart = System.nanoTime()
            // println("${this.ID}: TLS connection started: ${JvmMain.currTimeDelta(startTime)} ms")
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            connectionStats.tlsEnd = System.nanoTime()
            if (handshake != null) {
                // println("${this.ID}: TLS connection finished: ${JvmMain.currTimeDelta(startTime)} ms | version: ${handshake.tlsVersion}")
                // println("${this.callID}:${this.connectionID}: TLS connection took: ${ms(connectionStats.tlsEnd - connectionStats.tlsStart)} ms | version: ${handshake.tlsVersion}")
                if (this.callID.startsWith("F")) {
                    val myself = this
                    GlobalScope.launch {
                        latencyChannel.send(
                            ProbeDataPoint(
                                ms(connectionStats.tlsEnd - connectionStats.tlsStart),
                                myself.connectionID,
                                myself.callID,
                                handshake.tlsVersion.toString()
                            )
                        )
                    }
                }
            } else {
                // println("${this.ID}: TLS connection failed: ${JvmMain.currTimeDelta(startTime)} ms")
                println("${this.callID}:${this.connectionID}: TLS connection FAILED! took: ${ms(connectionStats.tlsEnd - connectionStats.tlsStart)} ms")
            }
        }

        // Request events
        override fun requestHeadersStart(call: Call) {
            connectionStats.requestHeaderStart = System.nanoTime()
            // println("${this.ID}: RequestHeaderStarted: ${JvmMain.currTimeDelta(startTime)} ms")
        }

        override fun requestHeadersEnd(call: Call, request: Request) {
            connectionStats.requestHeaderEnd = System.nanoTime()
            // println("${this.ID}: RequestHeaderEnded: ${JvmMain.currTimeDelta(startTime)} ms")
        }

        override fun requestBodyStart(call: Call) {
            connectionStats.requestBodyStart = System.nanoTime()
            // println("${this.ID}: RequestBodyStarted: ${JvmMain.currTimeDelta(startTime)} ms")
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            connectionStats.requestBodyEnd = System.nanoTime()
            // println("${this.ID}: RequestBodyEnded: ${JvmMain.currTimeDelta(startTime)} ms")
        }

        // requestFailed

        // Response events
        override fun responseHeadersStart(call: Call) {
            connectionStats.responseHeaderStart = System.nanoTime()
            // println("${this.ID}: ResponseHeaderStarted: ${JvmMain.currTimeDelta(startTime)} ms")

            // println("${this.callID}:${this.connectionID}: HTTP (request header start - response header start) took: ${ms(connectionStats.responseHeaderStart - connectionStats.requestHeaderStart)} ms")
            if (this.callID.startsWith("P") || this.callID.startsWith("F")) {
                if (writeResults) {
                    val myself = this
                    GlobalScope.launch {
                        latencyChannel.send(
                            ProbeDataPoint(
                                ms(connectionStats.responseHeaderStart - connectionStats.requestHeaderStart),
                                myself.connectionID,
                                myself.callID,
                                "HTTP"
                            )
                        )
                    }
                }
            }
    }

        override fun responseBodyStart(call: Call) {
            connectionStats.responseBodyStart = System.nanoTime()
            // println("${this.ID}: ResponseBodyStarted: ${JvmMain.currTimeDelta(startTime)} ms")
        }


        // Socket events
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectionStats.socketStart = System.nanoTime()
            // println("${this.callID}:${this.connectionID}: connectStart SOCKET START ATTEMPT: ${JvmMain.currTimeDelta(startTime)} ms")
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            connectionStats.socketEnd = System.nanoTime()
            // If our tls start is defined then we have a secure socket so our actual socket connection time
            // is from connectStart to when we start our tls connection (tlsStart)
            if (connectionStats.tlsStart != -1L) {
                connectionStats.socketEnd = connectionStats.tlsStart
            }

            // println("${this.callID}:${this.connectionID}: connectEnd SOCKET STARTED: ${JvmMain.currTimeDelta(startTime)} ms | protocol: ${protocol}")
            if (this.callID.startsWith("F")) {
//                val myself = this
//                GlobalScope.launch {
//                    latencyChannel.send(
//                        ProbeDataPoint(
//                            ms(connectionStats.socketEnd - connectionStats.socketStart),
//                            myself.connectionID,
//                            myself.callID,
//                            "Socket"
//                        )
//                    )
//                }
            }
        }

        override fun connectionAcquired(call: Call, connection: okhttp3.Connection) {
            // Use this connection and the load connection to make sure they match
            // Confirm the load connection is a h2
            // Record the tls version to calculate the # of round trips it contains
            connectionStats.socketAcquired = System.nanoTime()
            // println("${this.callID} socket acquired")

            // If our connection was the same as the load connection or is the load connection
            if (gotConnectionCallback(connection)) {
                // Do nothing
            } else {
                writeResults = false
                println("${this.callID} : ${this.connectionID}: connection acquired is not the same as load connection")
            }


            // println("${this.ID}: SOCKET ACQUIRED: ${JvmMain.currTimeDelta(startTime)} ms | Local Port: ${connection.socket().localPort}")
        }
    }
}