package com.taar.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.taar.dsp.LineFrequency

/**
 * The magnetometer as a continuous stream, for Geiger mode.
 *
 * [MagCapture] collects one fixed window and stops; this keeps listening and
 * holds the last few seconds in a ring, so the newest window can be read at any
 * moment. The same two rules hold as in [MagCapture]: the rate goes through
 * [LineFrequency.safeRate], and every sample keeps its own timestamp, measured
 * against other sensor timestamps and never against a wall clock.
 */
class MagStream(private val sensorManager: SensorManager) {

    class Window(
        /** Seconds from the window's first sample. */
        val tSeconds: DoubleArray,
        val xUt: DoubleArray,
        val yUt: DoubleArray,
        val zUt: DoubleArray,
        val rateHz: Double,
    )

    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val isAvailable: Boolean get() = sensor != null

    private val nanos = LongArray(CAPACITY)
    private val xs = FloatArray(CAPACITY)
    private val ys = FloatArray(CAPACITY)
    private val zs = FloatArray(CAPACITY)
    private var next = 0
    private var count = 0
    private val lock = Any()

    private var thread: HandlerThread? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            synchronized(lock) {
                nanos[next] = event.timestamp
                xs[next] = event.values[0]
                ys[next] = event.values[1]
                zs[next] = event.values[2]
                next = (next + 1) % CAPACITY
                if (count < CAPACITY) count++
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Starts listening. Events arrive on a thread of their own, not the UI's. */
    fun start(preferredRateHz: Int = 100) {
        val s = sensor ?: return
        if (thread != null) return
        synchronized(lock) { next = 0; count = 0 }
        val t = HandlerThread("taar-geiger").also { it.start() }
        thread = t
        val periodUs = (1_000_000.0 / LineFrequency.safeRate(preferredRateHz)).toInt()
        sensorManager.registerListener(listener, s, periodUs, Handler(t.looper))
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        thread?.quitSafely()
        thread = null
    }

    /** The newest [seconds] of samples, or null until there are enough to analyse. */
    fun window(seconds: Double): Window? {
        val (ts, x, y, z) = synchronized(lock) {
            if (count < MIN_SAMPLES) return null
            val newest = nanos[(next - 1 + CAPACITY) % CAPACITY]
            val span = (seconds * 1e9).toLong()
            var n = 0
            while (n < count && newest - nanos[(next - 1 - n + 2 * CAPACITY) % CAPACITY] <= span) n++
            if (n < MIN_SAMPLES) return null
            val first = (next - n + CAPACITY) % CAPACITY
            val t0 = nanos[first]
            val idx = IntArray(n) { (first + it) % CAPACITY }
            listOf(
                DoubleArray(n) { (nanos[idx[it]] - t0) / 1e9 },
                DoubleArray(n) { xs[idx[it]].toDouble() },
                DoubleArray(n) { ys[idx[it]].toDouble() },
                DoubleArray(n) { zs[idx[it]].toDouble() },
            )
        }
        // A window shorter than asked for means the stream has only just started.
        val covered = ts.last()
        if (covered < seconds * 0.8) return null
        return Window(ts, x, y, z, rateHz = (ts.size - 1) / covered)
    }

    private companion object {
        /** About 10 s at 200 Hz: several windows' worth even on a fast sensor. */
        const val CAPACITY = 2048

        /** [com.taar.dsp.LombScargle.contrast] needs at least 32. */
        const val MIN_SAMPLES = 48
    }
}
