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
import io.ktor.http.Headers
import io.ktor.http.cio.Request
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.utils.io.* // ByteReadChannel
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
import java.security.cert.Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.crypto.Data
import kotlin.collections.ArrayList
import kotlin.properties.Delegates


//var io: io.ktor.client.plugins.HttpCallValidator

// CONFIG
val URL_TO_GET =  "https://mensura.cdn-apple.com/api/v1/gm/large" // "https://rpm.obs.cr/large/" //
val URL_TO_POST =  "https://mensura.cdn-apple.com/api/v1/gm/slurp" //"https://rpm.obs.cr:443/slurp" //
val URL_TO_PROBE = "https://mensura.cdn-apple.com/api/v1/gm/small" //

val INITIAL_CONNECTION_COUNT = 4  // Initial Amount of Load Generating Connections (for upload and download)
val CONNECTION_INCREASE_COUNT = 1 // Amount to increase per cycle (second usually) of the algorithm


// GLOBAL USED
var SEND = ""


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

    fun API() {

    }

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


//        // Here I am going to intercept the send call to define my own
//        // Using https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-okhttp/jvm/src/io/ktor/client/engine/okhttp/OkHttpEngine.kt
//        client.plugin(HttpSend).intercept { request ->
//            println(request.headers.get("ID"))
//
//            execute(request)
//        }

        // DEFAULT CLIENT (NO HTTP2)
//        val client = io.ktor.client.HttpClient(CIO)


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

        var numUploadConnections = 0
        var numDownloadConnections = 0
        runBlocking {
            // Start internal receiving
            launch {uploadLatencyStability.start()}
            launch {downloadLatencyStability.start()}
            launch {uploadThroughputStability.start()}
            launch {downloadThroughputStability.start()}


            // Download Starts
            for (i in 1..INITIAL_CONNECTION_COUNT) {
                numDownloadConnections += 1
                launch(newSingleThreadContext("D:${numDownloadConnections}")) {
                    val connection = org.responsiveness.main.DownloadConnection(URL_TO_GET, downloadLatencyChannel, downloadThroughputChannel, "D${numDownloadConnections}")
                    connection.loadConnection()
                    delay(1000)
                    connection.startProbes(URL_TO_PROBE)
                }
            }
            // Upload Starts
            for (i in 1..INITIAL_CONNECTION_COUNT) {
                numUploadConnections += 1
                launch(newSingleThreadContext("U:${numUploadConnections}")) {
                    val connection = org.responsiveness.main.UploadConnection(URL_TO_POST, uploadLatencyChannel, uploadThroughputChannel, "U${numUploadConnections}")
                    connection.loadConnection()
                    delay(1000)
                    connection.startProbes(URL_TO_PROBE)
                }
            }

            // Main responsiveness test loop
            launch {
                var testStartTime = System.nanoTime()
                var uploadLatencyStable = false
                var downloadLatencyStable = false
                var uploadThroughputStable = false
                var downloadThroughputStable = false

                delay(1000)
                // While we don't have stability across the board
                while (!(uploadLatencyStable && downloadLatencyStable && uploadThroughputStable && downloadThroughputStable)) {
                    // Spawn new connections
                    // DownloadsConnections
                    for (i in 1..CONNECTION_INCREASE_COUNT) {
                        numDownloadConnections += 1
                        launch(newSingleThreadContext("D:${numDownloadConnections}")) {
                            val connection = org.responsiveness.main.DownloadConnection(URL_TO_GET, downloadLatencyChannel, downloadThroughputChannel, "D${numDownloadConnections}")
                            connection.loadConnection()
                            delay(1000)
                            connection.startProbes(URL_TO_PROBE)
                        }
                    }
                    // UploadConnections
                    for (i in 1..CONNECTION_INCREASE_COUNT) {
                        numUploadConnections += 1
                        launch(newSingleThreadContext("U:${numUploadConnections}")) {
                            val connection = org.responsiveness.main.UploadConnection(URL_TO_POST, uploadLatencyChannel, uploadThroughputChannel, "U${numUploadConnections}")
                            connection.loadConnection()
                            delay(1000)
                            connection.startProbes(URL_TO_PROBE)
                        }
                    }

                    delay(1000)
                    // Retest Stability at the end
                    uploadLatencyStable = uploadLatencyStability.isStable()
                    downloadLatencyStable = downloadLatencyStability.isStable()
                    uploadThroughputStable = uploadThroughputStability.isStable()
                    downloadThroughputStable = downloadThroughputStability.isStable()

                    println("###${(ms(System.nanoTime() - testStartTime) / 1000)}s: UploadLatency:${if (uploadLatencyStable) "" else " not"} stable | DownloadLatency:${if (downloadLatencyStable) "" else " not"} stable | UploadThroughput:${if (uploadThroughputStable) "" else " not"} stable | DownloadThroughput:${if (downloadThroughputStable) "" else " not"} stable")
                }
                System.exit(0)
            }
            println("REACHED AFTER LAUNCHES ####################################")
        }
        println("REACHED AFTER BLOCK ####################################")
        System.exit(0)
    }
}