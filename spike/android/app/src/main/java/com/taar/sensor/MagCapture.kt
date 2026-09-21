package com.taar.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.taar.dsp.LineFrequency
import kotlin.math.sqrt

/**
 * Magnetometer capture.
 *
 * Two things here are not incidental:
 *
 *  - The requested rate goes through [LineFrequency.safeRate]. Android's default
 *    magnetometer rate is 100 Hz, which is one of the three rates below 150 Hz that
 *    cannot see a 50 Hz signal at all. This is the single largest technical risk in
 *    the project and it is removed by one call.
 *
 *  - Every sample keeps its own timestamp. SensorManager delivers irregularly and
 *    stamps each event; that irregularity is what makes 50 Hz recoverable below the
 *    uniform Nyquist limit, so discarding timestamps and assuming a fixed interval
 *    would throw away the property the whole approach depends on.
 */
class MagCapture(private val sensorManager: SensorManager) {

    data class Samples(
        /** Seconds from the first sample. */
        val tSeconds: DoubleArray,
        /**
         * Field magnitude in microtesla. Useful for a noise-floor figure and for
         * showing the technician something stable, but NOT for detecting the line
         * component -- see [xUt].
         */
        val valuesUt: DoubleArray,
        /**
         * The three axes, unreduced.
         *
         * Magnitude is the wrong reduction for an AC field. The Earth contributes a
         * static ~45 uT, and a small alternating field perpendicular to it changes
         * |B| only to second order -- a 1 uT AC field moves the magnitude by about
         * 0.01 uT. Fitting each axis separately keeps the full component, and the
         * true AC amplitude is the vector magnitude of the three fitted amplitudes.
         *
         * Found on hardware: line confidence read 0.000 with a charger running.
         */
        val xUt: DoubleArray,
        val yUt: DoubleArray,
        val zUt: DoubleArray,
        /** Rate actually achieved, from the timestamps. */
        val measuredRateHz: Double,
        /** Standard deviation of inter-sample interval, as a fraction of the mean. */
        val jitter: Double,
        /** Rate requested after the lock guard. */
        val requestedRateHz: Int,
    )

    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private companion object {
        /** Below this a rate and jitter estimate is meaningless. */
        const val MIN_SAMPLES = 16
    }

    val isAvailable: Boolean get() = sensor != null

    /**
     * Collects for [durationSeconds]. Blocking is deliberate — a capture is a
     * discrete user action, not a stream — so call it off the main thread.
     */
    fun capture(durationSeconds: Double = 3.0, preferredRateHz: Int = 100): Samples? {
        val s = sensor ?: return null
        val safeHz = LineFrequency.safeRate(preferredRateHz)
        val periodUs = (1_000_000.0 / safeHz).toInt()

        val nanos = ArrayList<Long>(((durationSeconds * safeHz) * 1.5).toInt())
        val values = ArrayList<Double>(nanos.size)
        val xs = ArrayList<Double>(nanos.size)
        val ys = ArrayList<Double>(nanos.size)
        val zs = ArrayList<Double>(nanos.size)
        val done = java.util.concurrent.CountDownLatch(1)
        val durationNanos = (durationSeconds * 1e9).toLong()

        // The window is measured against the first event's own timestamp, never
        // against a wall clock.
        //
        // SensorEvent.timestamp is CLOCK_BOOTTIME (it includes deep sleep), while
        // System.nanoTime() is CLOCK_MONOTONIC (it does not). On a phone that has
        // been asleep the two differ by hours, so comparing them made the first
        // event look already past the deadline and every capture returned nothing.
        // Found on hardware; the unit tests could not have caught it.
        var originNanos = Long.MIN_VALUE

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (originNanos == Long.MIN_VALUE) originNanos = event.timestamp
                if (event.timestamp - originNanos > durationNanos) {
                    done.countDown()
                    return
                }
                nanos.add(event.timestamp)
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                xs.add(x); ys.add(y); zs.add(z)
                values.add(sqrt(x * x + y * y + z * z))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, s, periodUs)
        try {
            done.await((durationSeconds * 1000).toLong() + 500,
                java.util.concurrent.TimeUnit.MILLISECONDS)
        } finally {
            sensorManager.unregisterListener(listener)
        }

        // Too few samples is a failure, not a quiet zero. The caller surfaces it.
        if (nanos.size < MIN_SAMPLES) return null

        val t0 = nanos.first()
        val tSeconds = DoubleArray(nanos.size) { (nanos[it] - t0) / 1e9 }
        val intervals = DoubleArray(tSeconds.size - 1) { tSeconds[it + 1] - tSeconds[it] }
        val meanInterval = intervals.average()
        val variance = intervals.sumOf { (it - meanInterval) * (it - meanInterval) } / intervals.size

        return Samples(
            tSeconds = tSeconds,
            valuesUt = DoubleArray(values.size) { values[it] },
            xUt = DoubleArray(xs.size) { xs[it] },
            yUt = DoubleArray(ys.size) { ys[it] },
            zUt = DoubleArray(zs.size) { zs[it] },
            measuredRateHz = if (meanInterval > 0) 1.0 / meanInterval else 0.0,
            jitter = if (meanInterval > 0) sqrt(variance) / meanInterval else 0.0,
            requestedRateHz = safeHz,
        )
    }
}
