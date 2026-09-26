package com.taar.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.taar.domain.MotionCheck
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Gyroscope over the same window as a capture, reduced to one number: how fast
 * the phone was turning. See [MotionCheck] for why turning is what matters.
 *
 * Returns null on a phone without a gyroscope; the reading is then shown without
 * a motion verdict rather than with a guessed one.
 */
class MotionCapture(private val sensorManager: SensorManager) {

    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** Blocking, like [MagCapture.capture]: call it off the main thread. */
    fun capture(durationSeconds: Double = 3.0): Double? {
        val s = sensor ?: return null
        val xs = ArrayList<Double>(256)
        val ys = ArrayList<Double>(256)
        val zs = ArrayList<Double>(256)
        val done = CountDownLatch(1)
        val durationNanos = (durationSeconds * 1e9).toLong()
        // Timed against the first event's own clock, for the reason given in MagCapture.
        var originNanos = Long.MIN_VALUE

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (originNanos == Long.MIN_VALUE) originNanos = event.timestamp
                if (event.timestamp - originNanos > durationNanos) {
                    done.countDown()
                    return
                }
                xs.add(event.values[0].toDouble())
                ys.add(event.values[1].toDouble())
                zs.add(event.values[2].toDouble())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME)
        try {
            done.await((durationSeconds * 1000).toLong() + 500, TimeUnit.MILLISECONDS)
        } finally {
            sensorManager.unregisterListener(listener)
        }
        if (xs.size < MIN_SAMPLES) return null
        return MotionCheck.rmsRadPerS(xs.toDoubleArray(), ys.toDoubleArray(), zs.toDoubleArray())
    }

    private companion object {
        const val MIN_SAMPLES = 10
    }
}
