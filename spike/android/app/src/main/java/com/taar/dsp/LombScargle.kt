package com.taar.dsp

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Normalised Lomb-Scargle power at a single frequency.
 *
 * Matches `scipy.signal.lombscargle(..., normalize=True)`, which the spike used:
 * the raw periodogram scaled by `2 / sum(y^2)` over the mean-removed series. That
 * equivalence was checked numerically before this was written, so the golden
 * vectors in golden/magnetometer.json apply directly.
 *
 * Only one frequency is ever evaluated — the grid is 50 Hz — so there is no need
 * for a spectrum or an FFT.
 */
object LombScargle {

    /**
     * Removes a linear trend.
     *
     * A hand-held capture drifts: the phone settles, the wrist moves, the sensor
     * warms. That drift is large compared with the line component and carries no
     * information about current.
     */
    fun detrend(tSeconds: DoubleArray, values: DoubleArray): DoubleArray {
        val n = values.size
        if (n < 3) return values.copyOf()
        val meanT = tSeconds.average()
        val meanY = values.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            val dt = tSeconds[i] - meanT
            sxy += dt * (values[i] - meanY)
            sxx += dt * dt
        }
        val slope = if (sxx > 0) sxy / sxx else 0.0
        return DoubleArray(n) { values[it] - (meanY + slope * (tSeconds[it] - meanT)) }
    }

    /**
     * Power at [freqHz] relative to the median power of neighbouring frequencies.
     *
     * [power] normalises by the series' total variance, which is the right thing on
     * a bench and the wrong thing in a hand. Drift and low-frequency movement
     * dominate that variance and drive the statistic to zero even when a clean line
     * component is present -- measured on hardware, where a real 0.45 uT signal
     * reported a confidence of 0.000.
     *
     * Comparing 50 Hz against 30-48 and 52-70 Hz asks a better question: is there
     * more here than in the neighbourhood? Drift affects the whole neighbourhood
     * equally and divides out.
     *
     * @return a ratio. Around 1 means nothing; a real mains signal runs to 100x.
     */
    fun contrast(tSeconds: DoubleArray, values: DoubleArray, freqHz: Double = LINE_HZ): Double {
        if (values.size < 32) return 0.0
        val y = detrend(tSeconds, values)

        val at = power(tSeconds, y, freqHz)
        if (at <= 0.0) return 0.0

        val neighbours = ArrayList<Double>(36)
        var f = freqHz - 20.0
        while (f <= freqHz + 20.0) {
            if (kotlin.math.abs(f - freqHz) >= 2.0 && f >= 5.0) {
                neighbours.add(power(tSeconds, y, f))
            }
            f += 1.0
        }
        if (neighbours.isEmpty()) return 0.0

        neighbours.sort()
        val median = neighbours[neighbours.size / 2]
        return if (median > 1e-12) at / median else 0.0
    }

    /**
     * Contrast expressed on a 0..1 scale for thresholds and display.
     *
     * The mapping is `c / (c + 9)`, so no signal (~1x) reads about 0.10 and a clear
     * one (~100x) reads about 0.92.
     */
    fun confidenceFromContrast(contrast: Double): Double =
        if (contrast <= 0.0) 0.0 else contrast / (contrast + 9.0)

    private const val LINE_HZ = 50.0

    /**
     * @return normalised power in [0, 1]; near 1 means the series is almost
     *   entirely a sinusoid at [freqHz].
     */
    fun power(tSeconds: DoubleArray, values: DoubleArray, freqHz: Double): Double {
        require(tSeconds.size == values.size) { "timestamps and values differ in length" }
        if (values.size < 8) return 0.0

        val mean = values.average()
        var sumYY = 0.0
        for (v in values) {
            val y = v - mean
            sumYY += y * y
        }
        if (sumYY <= 0.0) return 0.0

        val w = 2.0 * Math.PI * freqHz

        // Offset tau makes the sin and cos sums orthogonal for irregular sampling.
        var s2 = 0.0; var c2 = 0.0
        for (t in tSeconds) {
            s2 += sin(2.0 * w * t)
            c2 += cos(2.0 * w * t)
        }
        val tau = atan2(s2, c2) / (2.0 * w)

        var ycSum = 0.0; var ysSum = 0.0
        var ccSum = 0.0; var ssSum = 0.0
        for (i in values.indices) {
            val dt = tSeconds[i] - tau
            val c = cos(w * dt)
            val s = sin(w * dt)
            val y = values[i] - mean
            ycSum += y * c; ysSum += y * s
            ccSum += c * c; ssSum += s * s
        }

        val cTerm = if (ccSum > 0) ycSum * ycSum / ccSum else 0.0
        val sTerm = if (ssSum > 0) ysSum * ysSum / ssSum else 0.0
        val raw = 0.5 * (cTerm + sTerm)

        return 2.0 * raw / sumYY
    }
}
