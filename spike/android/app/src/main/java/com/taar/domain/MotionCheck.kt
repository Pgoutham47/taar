package com.taar.domain

import kotlin.math.sqrt

/**
 * Whether the phone held still enough during a capture to trust it.
 *
 * The magnetometer is a compass. Turning the phone swings the Earth's ~45 uT
 * across its axes, which is far larger than the field of the cable being
 * measured. Slow hand tremor mostly sits below the 30-70 Hz band the line contrast
 * is measured in, so a steady hand is fine; a phone that is turned or waved during
 * the three seconds is not. The gyroscope reports exactly that, independently of
 * the reading it is judging.
 *
 * Thresholds are first estimates, not measurements: a phone on a table reads a
 * few thousandths of a rad/s, a hand held against a cable a few hundredths, and
 * deliberate movement tenths. Set them from real captures before relying on them.
 */
object MotionCheck {

    enum class Level { STILL, STEADY_HAND, MOVED }

    /** RMS angular speed, rad/s. Below this the phone was resting on something. */
    const val STILL_BELOW = 0.05

    /** Above this the phone was turned during the capture (~9 degrees a second). */
    const val MOVED_ABOVE = 0.15

    /** Root-mean-square angular speed over the capture, from the three gyroscope axes. */
    fun rmsRadPerS(x: DoubleArray, y: DoubleArray, z: DoubleArray): Double {
        val n = minOf(x.size, y.size, z.size)
        if (n == 0) return 0.0
        var acc = 0.0
        for (i in 0 until n) acc += x[i] * x[i] + y[i] * y[i] + z[i] * z[i]
        return sqrt(acc / n)
    }

    fun level(rmsRadPerS: Double): Level = when {
        rmsRadPerS > MOVED_ABOVE -> Level.MOVED
        rmsRadPerS >= STILL_BELOW -> Level.STEADY_HAND
        else -> Level.STILL
    }
}
