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
