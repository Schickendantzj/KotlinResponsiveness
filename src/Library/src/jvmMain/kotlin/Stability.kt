package org.responsiveness.main

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.xml.crypto.Data
import kotlin.math.pow
import kotlin.math.sqrt

interface DataPoint {
    var ID: String
    var value: Float // Value to be used in the stability calculation

    // Expects items in "<>, <>, <>/n" format; NO CHECKS
    fun toCSV(): String

    // Expects column names in "<>, <>, <>/n" format; NO CHECKS
    fun getHeaderCSV(): String

}

interface HookDataPoint {
    fun getValue(): DataPoint
}


class StabilityConfig {

}

abstract class Stability(protected var path: File, protected var writeFiles: Boolean = true) {
    companion object {
        val timestamp = LocalDateTime.now()
    }
    val fileFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")


    protected abstract var stable: Boolean

    // isStable should return if it is stable at the time it is called
    abstract fun isStable (): Boolean

    // start should start the receiving of the channel (and stability)
    // caller controls the context it runs within
    abstract suspend fun start()
    protected abstract suspend fun receive()
    abstract fun getLastMovingPoint(): Float

    // Internal
    protected fun P90(points: ArrayList<DataPoint>): Float {
        val sorted = points.sortedWith(compareBy({ it.value }))
        return points[(sorted.size * .90).toInt()].value
    }

    protected fun P90Float(points: ArrayList<Float>): Float {
        val sorted = points.sorted()
        return points[(sorted.size * .90).toInt()]
    }

    // External for RPM Measurement
    abstract fun getP90(): Float

    fun calculateMean(points: ArrayList<Float>): Double {
        var mean = 0.0
        for (point in points) {
            mean += point
        }
        mean = mean / points.size
        return mean
    }

    // Expects an ArrayList or Slice of an ArrayList that holds all the datapoints to be calculated
    fun calculateStandardDeviation(points: ArrayList<Float>): Double {
        var mean = calculateMean(points)
        var error = 0.0
        for (point in points) {
            error += (mean - point).pow(2.0)
        }

        return sqrt(error / points.size)
    }

    fun trimToPercent90(points: ArrayList<DataPoint>): ArrayList<DataPoint> {
        val sorted = points.sortedWith(compareBy({ it.value }))
        return ArrayList(sorted.slice(0 until (sorted.size * .90).toInt()))
    }


}

// Expects percentage as 5 for 5%
class ThroughputStability(val ID: String, val hooksChannel: Channel<HookDataPoint>, val numberOfMoving: Int, val percent: Float, path: File, writeFiles: Boolean): Stability(path, writeFiles) {
    override var stable = false
    var initializedFiles = false
    var fileLock = Mutex()

    // Locks used whenever accessing each array
    var instantPointsLock = Mutex()
    var movingPointsLock = Mutex()


    private val hooks: ArrayList<HookDataPoint> = ArrayList<HookDataPoint>()

    private val channel: Channel<DataPoint> = Channel<DataPoint>()

    // TODO: Consider changing to Queue
    private val instantDataPoints: ArrayList<DataPoint> = ArrayList<DataPoint>()

    // TODO: Consider changing to Queue
    private val movingDataPoints: ArrayList<Float> = ArrayList<Float>()

    private var directory = "./Data/${timestamp.format(fileFormatter)}"
    private var instantDataPointsFileName = "instantThroughput${ID}.csv"
    private var movingDataPointsFileName = "movingThroughput${ID}.csv"

    private var instantDataPointsFile = File("./Data/${timestamp.format(fileFormatter)}/instantThroughput${ID}.csv")
    private var movingDataPointsFile = File("./Data/${timestamp.format(fileFormatter)}/movingThroughput${ID}.csv")

    override fun getLastMovingPoint(): Float {
        var last = 0f
        try {
            runBlocking {
                movingPointsLock.withLock {
                    last = movingDataPoints.last()
                }
            }
        } catch (e: NoSuchElementException) {
            // Do nothing
        }
        return last
    }

    override fun isStable(): Boolean {
        // If we don't have enough data points yet don't calculate
        if (movingDataPoints.size < (numberOfMoving + 1)) {
            return false
        }

        var sd: Double
        var mean: Double
        var percentOfMeasurement: Double
        // Percentage should be based upon the amount of points that its checking
        // I.E. percentage
        // Calculate SD and Percentage of mean of the measurements
        runBlocking {
            movingPointsLock.withLock {
                sd = calculateStandardDeviation(ArrayList(movingDataPoints.slice((movingDataPoints.size - numberOfMoving - 1) until movingDataPoints.size)))
                mean = calculateMean(ArrayList(movingDataPoints.slice((movingDataPoints.size - numberOfMoving - 1) until movingDataPoints.size)))
                percentOfMeasurement = mean * (percent / 100.0)
                println("||Throughput${ID}|| SD: ${sd} | percentAmount: ${percentOfMeasurement} | mean: ${mean} | percent: ${percent}")
            }
        }
        return (sd < percentOfMeasurement)
    }

    override suspend fun start() {
        coroutineScope {
            launch {
                receive()
            }
            launch {
                internalReceive()
            }
            launch {
                calculateMovingDataPoint(1000)
            }
        }
    }

    fun initializeFiles(datapoint: DataPoint) {
        // TODO("Make sure directory exists/Have write permissions")
        initializedFiles = true
        if (writeFiles) {
            val runPath = File(path, directory)
            if (runPath.mkdirs()) {
                println("Success")
            }

            instantDataPointsFile = File(runPath, instantDataPointsFileName)
            movingDataPointsFile = File(runPath, movingDataPointsFileName)

            instantDataPointsFile.writeText("DateTime, " + datapoint.getHeaderCSV())
            movingDataPointsFile.writeText("DateTime, 1 Second bytes sum\n")
        }
    }

    override suspend fun receive() {
        for (hook in hooksChannel) {
            hooks.add(hook)
        }
        println("Error: Throughput Hooks Closed")
    }

    suspend fun internalReceive() {
        for (datapoint in channel) {
            instantPointsLock.withLock {
                instantDataPoints.add(datapoint)
            }
            val current = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            fileLock.withLock {
                if (!initializedFiles) {
                    initializeFiles(datapoint)
                }
                if (writeFiles) {
                    instantDataPointsFile.appendText("${current.format(formatter)}, ${datapoint.toCSV()}")
                }
            }
        }
        println("Throughput Stability Closed")
    }

    private suspend fun calculateMovingDataPoint(delayMS: Long) {
        var startSize = 0
        var endSize = 0
        var sum = 0f
        var avg = 0f

        while (true) {
            // If our hooks are unpopulated: wait and try again
            if (hooks.size == 0) {
                delay(delayMS + 100L)
                continue
            }

            // Grab all my values
            for (hook in hooks) {
                val dp = hook.getValue()
                channel.send(dp)
            }

            // Give time to populate my arrays
            while (instantDataPoints.size == 0) {
                delay(100L)
            }

            startSize = endSize
            endSize = instantDataPoints.size - 1


            sum = 0f
            instantPointsLock.withLock {
                for (point in ArrayList(instantDataPoints.slice(startSize until endSize))) {
                    sum += point.value
                }
            }

            movingPointsLock.withLock {
                movingDataPoints.add(sum)
            }

            val current = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            fileLock.withLock {
                if (!initializedFiles) {
                    initializeFiles(instantDataPoints[0])
                }
                if (writeFiles) {
                    movingDataPointsFile.appendText("${current.format(formatter)}, ${sum}\n")
                }
            }
            delay(delayMS)
        }
    }
    override fun getP90(): Float {
        var out = 0f
        if (instantDataPoints.size == 0) {
            return out
        }
        runBlocking {
            movingPointsLock.withLock {
                out = P90Float(movingDataPoints)
            }
        }
        return out
    }
}

// Expects percentage as 5 for 5%
class LatencyStability(val ID: String, val channel: Channel<DataPoint>, val numberOfMoving: Int, val percent: Float, path: File, writeFiles: Boolean): Stability(path, writeFiles) {
    override var stable = false
    var initializedFiles = false
    var fileLock = Mutex()

    // Locks used whenever accessing each array
    var instantPointsLock = Mutex()
    var movingPointsLock = Mutex()




    // TODO: Consider changing to Queue
    private val instantDataPoints: ArrayList<DataPoint> = ArrayList<DataPoint>()

    // TODO: Consider changing to Queue
    private val movingDataPoints: ArrayList<Float> = ArrayList<Float>()

    private var directory = "./Data/${timestamp.format(fileFormatter)}"
    private var instantDataPointsFileName = "instantLatency${ID}.csv"
    private var movingDataPointsFileName = "movingLatency${ID}.csv"

    private var instantDataPointsFile = File("./Data/${timestamp.format(fileFormatter)}/instantLatency${ID}.csv")
    private var movingDataPointsFile = File("./Data/${timestamp.format(fileFormatter)}/movingLatency${ID}.csv")

    override fun getLastMovingPoint(): Float {
        var last = 0f
        try {
            runBlocking {
                movingPointsLock.withLock {
                    last = movingDataPoints.last()
                }
            }
        } catch (e: NoSuchElementException) {
            // Do nothing
        }
        return last
    }

    override fun isStable(): Boolean {


        // If we don't have enough data points yet don't calculate
        if (movingDataPoints.size < (numberOfMoving + 1)) {
            return false
        }

        var sd: Double
        var mean: Double
        var percentOfMeasurement: Double
        // Percentage should be based upon the amount of points that its checking
        // I.E. percentage
        // Calculate SD and Percentage of mean of the measurements
        runBlocking {
            movingPointsLock.withLock {
                sd = calculateStandardDeviation(ArrayList(movingDataPoints.slice((movingDataPoints.size - numberOfMoving - 1) until movingDataPoints.size)))
                mean = calculateMean(ArrayList(movingDataPoints.slice((movingDataPoints.size - numberOfMoving - 1) until movingDataPoints.size)))
                percentOfMeasurement = mean * (percent / 100.0)
                println("||Latency${ID}|| SD: ${sd} | percentAmount: ${percentOfMeasurement} | mean: ${mean} | percent: ${percent}")
            }
        }
        return (sd < percentOfMeasurement)
    }

    override suspend fun start() {
        coroutineScope {
            launch {
                receive()
            }
            launch {
                calculateMovingDataPoint(1000)
            }
        }

    }

    fun initializeFiles(datapoint: DataPoint) {
        // TODO("Make sure directory exists/Have write permissions")
        initializedFiles = true
        if (writeFiles) {
            val runPath = File(path, directory)
            if (runPath.mkdirs()) {
                println("Success")
            }
            instantDataPointsFile = File(runPath, instantDataPointsFileName)
            movingDataPointsFile = File(runPath, movingDataPointsFileName)

            instantDataPointsFile.writeText("DateTime, " + datapoint.getHeaderCSV())
            movingDataPointsFile.writeText("DateTime, 1 Second Avg RTT (ms)\n")
        }
    }

    override suspend fun receive() {
        for (datapoint in channel) {
            instantPointsLock.withLock {
                instantDataPoints.add(datapoint)
            }
            val current = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            fileLock.withLock {
                if (!initializedFiles) {
                    initializeFiles(datapoint)
                }
                if (writeFiles) {
                    instantDataPointsFile.appendText("${current.format(formatter)}, ${datapoint.toCSV()}")
                }
            }
        }
        println("Latency Stability Closed")
    }

    private suspend fun calculateMovingDataPoint(delayMS: Long) {
        var startSize = 0
        var endSize = 0
        var sum = 0f
        var avg = 0f

        while (true) {
            startSize = endSize
            endSize = instantDataPoints.size - 1


            // If our list is unpopulated: wait and try again
            if (endSize == -1) {
                endSize = 0
                delay(delayMS)
                continue
            }

            var trimmedP90: ArrayList<DataPoint>
            instantPointsLock.withLock {
                trimmedP90 = trimToPercent90(ArrayList(instantDataPoints.slice(startSize until endSize)))
            }

            sum = 0f
            for (i in trimmedP90) {
                sum += i.value
            }
            avg = sum / (trimmedP90.size)

            movingPointsLock.withLock {
                movingDataPoints.add(avg)
            }

            val current = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            fileLock.withLock {
                if (!initializedFiles) {
                    initializeFiles(instantDataPoints[0])
                }
                if (writeFiles) {
                    movingDataPointsFile.appendText("${current.format(formatter)}, ${avg}\n")
                }
            }
            delay(delayMS)
        }
    }

    override fun getP90(): Float {
        var out = 0f
        if (instantDataPoints.size == 0) {
            return out
        }
        runBlocking {
            instantPointsLock.withLock {
                out = P90(instantDataPoints)
            }
        }
        return out
    }
}
