package org.responsiveness.main

import kotlinx.coroutines.*

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*


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
import io.ktor.http.Headers
import io.ktor.http.cio.Request
import io.ktor.http.content.*
import io.ktor.serialization.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.utils.io.* // ByteReadChannel
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.EventListener
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.TypeVariable
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.system.measureNanoTime

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore.TrustedCertificateEntry
import java.security.cert.Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.crypto.Data
import kotlin.collections.ArrayList
import kotlin.properties.Delegates
import kotlin.system.exitProcess

// CONFIG
val URL_CONFIG =   "https://mensura.cdn-apple.com/api/v1/gm/config" // "https://rpm.obs.cr:4043/config" //
val URL_TO_GET =  "https://mensura.cdn-apple.com/api/v1/gm/large" // "https://rpm.obs.cr/large/" //
val URL_TO_POST =  "https://mensura.cdn-apple.com/api/v1/gm/slurp" //"https://rpm.obs.cr:443/slurp" //
val URL_TO_PROBE = "https://mensura.cdn-apple.com/api/v1/gm/small" //

val INITIAL_CONNECTION_COUNT = 4  // Initial Amount of Load Generating Connections (for upload and download)
val CONNECTION_INCREASE_COUNT = 1 // Amount to increase per cycle (second usually) of the algorithm
val FOREIGN_CONNECTION_COUNT = 1  // Amount of foreign connections to establish per second


// GLOBAL USED
var SEND = ""


@Serializable
data class Config(
    val version: Float,
    val test_endpoint: String? = null,
    val urls: URLS,
)


// Allow missing values to be null
@Serializable
data class URLS(
    val small_https_download_url: String? = null,
    val large_https_download_url: String? = null,
    val https_upload_url: String? = null,
    val small_download_url: String? = null,
    val large_download_url: String? = null,
    val upload_url: String? = null,
)


class URL(val config: Config, val bodyText: String) {
    lateinit var download: String
    lateinit var upload: String
    lateinit var probe: String
    var error: String? = null  // Is null unless there is a problem

    init {
        // Always try https first
        // Upload
        if (config.urls.https_upload_url != null) {
            this.upload = config.urls.https_upload_url
        } else if (config.urls.upload_url != null) {
            this.upload = config.urls.upload_url
        } else {
            if (error == null) {
                error = "No http/https upload url parsed from json\n"
            } else {
                error += "No http/https upload url parsed from json\n"
            }
        }

        // Download (download large)
        if (config.urls.large_https_download_url != null) {
            this.download = config.urls.large_https_download_url
        } else if (config.urls.large_download_url != null) {
            this.download = config.urls.large_download_url
        } else {
            if (error == null) {
                error = "No http/https large download url parsed from json\n"
            } else {
                error += "No http/https large download url parsed from json\n"
            }
        }

        // Probe (download small)
        if (config.urls.small_https_download_url != null) {
            this.probe = config.urls.small_https_download_url
        } else if (config.urls.small_download_url != null) {
            this.probe = config.urls.small_download_url
        } else {
            if (error == null) {
                error = "No http/https (probe) small download url parsed from json\n"
            } else {
                error += "No http/https (probe) small download url parsed from json\n"
            }
        }
    }
}


suspend fun getURLS(client: HttpClient, url: String): URL {
    val response = client.get(url) {}
    // val bodyText = response.bodyAsText()
    // println(response.bodyAsText())
    val config: Config = response.body()
    return URL(config, response.bodyAsText())
}


actual object MainFactory {
    actual fun createMain(): Main = JvmMain
}


object JvmMain : Main {

    //https://gaumala.com/posts/2020-01-27-working-with-streams-kotlin.html
    class ObservableInputStream(private val chunk: ByteArray, private val onBytesRead: (Long) -> Unit) : InputStream() {
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
            return 4096 // 4KB
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

    class ObservableOutputStream(
        private val chunk: ByteArray,
        private val onBytesWritten: (Long) -> Unit
    ) : OutputStream() {
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

    // Returns delta timeStart, currentTime(System.nanoTime()) normalized to ms
    fun currTimeDelta(startTime: Long): Double {
        return ((System.nanoTime() - startTime) / 1_000_000.0)
    }

    override fun main(args: Array<String>, outputChannel: Channel<String>): Unit {

        // Initialize global send
        SEND = "x"
        for (i in 0..16) {
            SEND += SEND
        }


        // Initial Config Retrieval
        val client = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                    isLenient = true
                })
            }
        }

        var urls: URL
        runBlocking{
            urls = getURLS(client, URL_CONFIG)
        }

        if (urls.error != null) {
            println("Had a problem parsing config json urls: ${urls.error}")
            exitProcess(0)
        }

        // Initialize Test Channels for Stability

        var context = StopperContext(false)

        // Latency Channels & Stability
        var uploadLatencyChannel= Channel<DataPoint>()
        var uploadLatencyStability = LatencyStability("Upload", uploadLatencyChannel, 5, 10f)
        var downloadLatencyChannel = Channel<DataPoint>()
        var downloadLatencyStability = LatencyStability("Download", downloadLatencyChannel, 5, 10f)

        // Throughput Channels & Stability
        var uploadThroughputChannel = Channel<HookDataPoint>()
        var uploadThroughputStability = ThroughputStability("Upload", uploadThroughputChannel, 5, 10f)
        var downloadThroughputChannel = Channel<HookDataPoint>()
        var downloadThroughputStability = ThroughputStability("Download", downloadThroughputChannel, 5, 10f)

        // Foreign Latency Channel & Stability
        var foreignLatencyChannel = Channel<DataPoint>()
        var foreignLatencyStability = LatencyStability("Foreign", foreignLatencyChannel, 5, 10f)


        var numUploadConnections = 0
        var numDownloadConnections = 0
        var numForeignConnections = 0

        runBlocking {
            // Start internal receiving
            launch {uploadLatencyStability.start()}
            launch {downloadLatencyStability.start()}
            launch {uploadThroughputStability.start()}
            launch {downloadThroughputStability.start()}
            launch {foreignLatencyStability.start()}


            // Download Starts
            for (i in 1..INITIAL_CONNECTION_COUNT) {
                numDownloadConnections += 1
                val numDown = numDownloadConnections
                launch(newSingleThreadContext("D:$numDown")) {
                    val connection = org.responsiveness.main.DownloadConnection(urls.download, downloadLatencyChannel, downloadThroughputChannel, "D$numDown")
                    connection.loadConnection()
                    delay(1000)
                    connection.startProbes(urls.probe)
                }
            }
            // Upload Starts
            for (i in 1..INITIAL_CONNECTION_COUNT) {
                numUploadConnections += 1
                val numUp = numUploadConnections
                launch(newSingleThreadContext("U:$numUp")) {
                    val connection = org.responsiveness.main.UploadConnection(urls.upload, uploadLatencyChannel, uploadThroughputChannel, "U$numUp")
                    connection.loadConnection()
                    delay(1000)
                    connection.startProbes(urls.probe)
                }
            }

            // Main responsiveness test loop
            launch {
                var testStartTime = System.nanoTime()
                var uploadLatencyStable = false
                var downloadLatencyStable = false
                var foreignLatencyStable = false
                var uploadThroughputStable = false
                var downloadThroughputStable = false

                delay(1000)
                // While we don't have stability across the board
                while (!(uploadLatencyStable && downloadLatencyStable && uploadThroughputStable && downloadThroughputStable && foreignLatencyStable)) {
                    // Spawn new connections
                    // DownloadsConnections
                    for (i in 1..CONNECTION_INCREASE_COUNT) {
                        numDownloadConnections += 1
                        val numDown = numDownloadConnections
                        launch(newSingleThreadContext("D:$numDown")) {
                            val connection = org.responsiveness.main.DownloadConnection(urls.download, downloadLatencyChannel, downloadThroughputChannel, "D$numDown")
                            connection.loadConnection()
                            delay(1000)
                            connection.startProbes(urls.probe)
                        }
                    }
                    // UploadConnections
                    for (i in 1..CONNECTION_INCREASE_COUNT) {
                        numUploadConnections += 1
                        val numUp = numUploadConnections
                        launch(newSingleThreadContext("U:$numUp")) {
                            val connection = org.responsiveness.main.UploadConnection(urls.upload, uploadLatencyChannel, uploadThroughputChannel, "U$numUp")
                            connection.loadConnection()
                            delay(1000)
                            connection.startProbes(urls.probe)
                        }
                    }

                    // Spawn all foreign connections
                    // We don't want to add delay normal test loop
                    launch {
                        for (i in 1..FOREIGN_CONNECTION_COUNT) {
                            for (x in 1..10) {
                                numForeignConnections += 1
                                val numForeign = numForeignConnections
                                // TODO use single thread context?
                                launch {
                                    val connection = org.responsiveness.main.ForeignConnection(urls.probe, foreignLatencyChannel, "F$numForeign")
                                    connection.loadConnection()
                                }
                                // Pause for the amount needed
                                delay((1000 / 10 / FOREIGN_CONNECTION_COUNT).toLong())
                            }
                        }
                    }


                    delay(1000)
                    // Retest Stability at the end
                    uploadLatencyStable = uploadLatencyStability.isStable()
                    downloadLatencyStable = downloadLatencyStability.isStable()
                    foreignLatencyStable = foreignLatencyStability.isStable()
                    uploadThroughputStable = uploadThroughputStability.isStable()
                    downloadThroughputStable = downloadThroughputStability.isStable()


                    outputChannel.send("###${(ms(System.nanoTime() - testStartTime) / 1000)}s: UploadLatency:${if (uploadLatencyStable) "" else " not"} stable | DownloadLatency:${if (downloadLatencyStable) "" else " not"} stable | ForeignLatency:${if (foreignLatencyStable) "" else " not"} stable | UploadThroughput:${if (uploadThroughputStable) "" else " not"} stable | DownloadThroughput:${if (downloadThroughputStable) "" else " not"} stable")
                    outputChannel.send("###Upload Latency: ${uploadLatencyStability.getLastMovingPoint()} ms | Download Latency: ${downloadLatencyStability.getLastMovingPoint()} ms | Foreign Latency: ${foreignLatencyStability.getLastMovingPoint()} ms")
                    outputChannel.send("###Upload Throughput: ${uploadThroughputStability.getLastMovingPoint()} Mb/s | Download Throughput: ${downloadThroughputStability.getLastMovingPoint()} Mb/s")

                    println("###${(ms(System.nanoTime() - testStartTime) / 1000)}s: UploadLatency:${if (uploadLatencyStable) "" else " not"} stable | DownloadLatency:${if (downloadLatencyStable) "" else " not"} stable | ForeignLatency:${if (foreignLatencyStable) "" else " not"} stable | UploadThroughput:${if (uploadThroughputStable) "" else " not"} stable | DownloadThroughput:${if (downloadThroughputStable) "" else " not"} stable")
                    println("###Upload Latency: ${uploadLatencyStability.getLastMovingPoint()} ms | Download Latency: ${downloadLatencyStability.getLastMovingPoint()} ms | Foreign Latency: ${foreignLatencyStability.getLastMovingPoint()} ms")
                    println("###Upload Throughput: ${uploadThroughputStability.getLastMovingPoint()} Mb/s | Download Throughput: ${downloadThroughputStability.getLastMovingPoint()} Mb/s")
                }
                System.exit(0)
            }
            println("REACHED AFTER LAUNCHES ####################################")
        }
        println("REACHED AFTER BLOCK ####################################")
        System.exit(0)
    }
}