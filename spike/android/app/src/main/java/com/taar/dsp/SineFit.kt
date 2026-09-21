package com.taar.dsp

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Least-squares amplitude of a sinusoid at a *known* frequency, over irregularly
 * sampled data.
 *
 * The grid frequency is known, so this is not a search — it is a two-parameter
 * linear fit of `a*sin(wt) + b*cos(wt)`, solved from the 2x2 normal equations
 * directly. That is exact, allocation-free and fast enough to run on every capture.
 *
 * Irregular sample timing is not a problem here; it is the reason this works at
 * rates below the uniform Nyquist limit.
 */
object SineFit {

    /** Result of the fit. [amplitudeUt] is the peak amplitude in microtesla. */
    data class Result(val amplitudeUt: Double, val phaseRad: Double, val conditioning: Double)

    /**
     * @param tSeconds sample timestamps, seconds, need not be uniform
     * @param values sensor readings; the mean is removed internally
     * @param freqHz frequency to fit at, normally the line frequency
     */
    fun fit(tSeconds: DoubleArray, values: DoubleArray, freqHz: Double): Result {
        require(tSeconds.size == values.size) { "timestamps and values differ in length" }
        if (values.size < 4) return Result(0.0, 0.0, 0.0)

        val mean = values.average()
        val w = 2.0 * Math.PI * freqHz

        var sss = 0.0; var scc = 0.0; var ssc = 0.0
        var sys = 0.0; var syc = 0.0

        for (i in values.indices) {
            val s = sin(w * tSeconds[i])
            val c = cos(w * tSeconds[i])
            val y = values[i] - mean
            sss += s * s; scc += c * c; ssc += s * c
            sys += y * s; syc += y * c
        }

        val det = sss * scc - ssc * ssc
        // Conditioning near zero is the harmonic-lock case: the sin and cos basis
        // vectors are collinear over the sample phases actually visited.
        val conditioning = if (sss * scc > 0) det / (sss * scc) else 0.0
        if (det == 0.0 || conditioning < 1e-6) return Result(0.0, 0.0, conditioning)

        val a = (sys * scc - syc * ssc) / det
        val b = (syc * sss - sys * ssc) / det
        return Result(hypot(a, b), kotlin.math.atan2(b, a), conditioning)
    }
}
