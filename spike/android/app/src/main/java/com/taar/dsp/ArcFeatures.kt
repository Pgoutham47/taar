package com.taar.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * The 89 numbers the on-device arc model reads from one 3 s capture.
 *
 * A transcription of spike/audio/arcfeatures.py, which is what the model was
 * trained on. A model fed numbers that differ slightly from its training numbers
 * fails without saying so, so nothing here is new DSP: the envelope is
 * [ArcDetector.modulationIndex]'s, with the same filters, decimation and FFT, and
 * GoldenChecks compares this against the Python output on real iQOO captures.
 *
 *     ENV    76  envelope power in 5 Hz bins over 20-400 Hz, as fractions of that band
 *     SHAPE  12  raw-audio power in log-spaced bands over 100 Hz-20 kHz, as fractions
 *     MI      1  power within 3 Hz of 100 Hz over 20-400 Hz -- the rule's statistic
 *
 * Every feature is a fraction, so loudness and distance cannot be what the model
 * learned. Each is returned as log10(fraction + 1e-6).
 */
object ArcFeatures {

    const val SAMPLE_RATE_HZ = 44_100.0
    const val COUNT = 89

    private const val ENVELOPE_RATE_HZ = 2_000.0
    const val ENV_LO_HZ = 20.0
    const val ENV_BIN_HZ = 5.0
    const val ENV_BINS = 76
    private const val ENV_HI_HZ = 400.0
    private const val ARC_REP_HZ = 100.0
    private const val PEAK_HALF_WIDTH_HZ = 3.0
    private const val SHAPE_FRAME = 1024
    private const val EPS = 1e-6

    /** numpy.geomspace(100, 20000, 13), printed to full precision so both sides agree exactly. */
    private val SHAPE_EDGES_HZ = doubleArrayOf(
        100.0, 155.50791539731856, 241.82711751219566, 376.0603093086394, 584.8035476425734,
        909.4158061085307, 1414.213562373095, 2199.214030112558, 3419.951893353397,
        5318.295896944989, 8270.371084000279, 12861.081668351451, 20000.0,
    )

    /** The features, and the envelope they were computed from, for display. */
    class Detailed(
        val features: FloatArray,
        /** The 4-16 kHz envelope the arc detector works on, decimated. */
        val envelope: DoubleArray,
        val envelopeRateHz: Double,
    ) {
        /** Share of envelope power in each 5 Hz bin from 20 Hz, as the model sees it. */
        fun envelopeSpectrum(): DoubleArray = DoubleArray(ENV_BINS) { 10.0.pow(features[it].toDouble()) - EPS }

        /** The rule's statistic: share of envelope power within 3 Hz of 100 Hz. */
        val modulationIndex: Double get() = 10.0.pow(features[COUNT - 1].toDouble()) - EPS
    }

    /** Null when the capture is not at the rate the model was trained on. */
    fun of(audio: DoubleArray, sampleRateHz: Double): FloatArray? = detailed(audio, sampleRateHz)?.features

    fun detailed(audio: DoubleArray, sampleRateHz: Double): Detailed? {
        if (sampleRateHz != SAMPLE_RATE_HZ || audio.size < SHAPE_FRAME * 4) return null

        val out = DoubleArray(COUNT)
        val (envelope, envRate) = envelopeFeatures(audio, sampleRateHz, out)
        shapeFeatures(audio, sampleRateHz, out)
        return Detailed(FloatArray(COUNT) { log10(out[it] + EPS).toFloat() }, envelope, envRate)
    }

    private fun envelopeFeatures(audio: DoubleArray, sampleRateHz: Double, out: DoubleArray): Pair<DoubleArray, Double> {
        val band = Biquad.hfBandpass4kTo16k().filter(audio)
        for (i in band.indices) band[i] = abs(band[i])
        val smoothed = Biquad.envLowpass500().filter(band)

        val step = (sampleRateHz / ENVELOPE_RATE_HZ).toInt().coerceAtLeast(1)
        val envRate = sampleRateHz / step
        val env = DoubleArray(smoothed.size / step) { smoothed[it * step] }

        val mean = env.average()
        val window = Fft.hann(env.size)
        val power = Fft.powerSpectrum(DoubleArray(env.size) { (env[it] - mean) * window[it] })
        val freqs = Fft.frequencies(env.size, envRate)

        var total = 0.0
        var peak = 0.0
        for (k in power.indices) {
            val f = freqs[k]
            if (f < ENV_LO_HZ || f > ENV_HI_HZ) continue
            total += power[k]
            if (abs(f - ARC_REP_HZ) <= PEAK_HALF_WIDTH_HZ) peak += power[k]
            // Same comparisons as the Python, bin by bin, so an edge case cannot land
            // in a different bin through rounding.
            for (b in 0 until ENV_BINS) {
                val lo = ENV_LO_HZ + b * ENV_BIN_HZ
                if (f >= lo && f < lo + ENV_BIN_HZ) { out[b] += power[k]; break }
            }
        }
        if (total > 0.0) for (b in 0 until ENV_BINS) out[b] /= total
        out[COUNT - 1] = if (total > 0.0) peak / total else 0.0
        return env to envRate
    }

    private fun shapeFeatures(audio: DoubleArray, sampleRateHz: Double, out: DoubleArray) {
        val frames = audio.size / SHAPE_FRAME
        val window = Fft.hann(SHAPE_FRAME)
        val mean = DoubleArray(SHAPE_FRAME / 2 + 1)
        val frame = DoubleArray(SHAPE_FRAME)
        for (i in 0 until frames) {
            val start = i * SHAPE_FRAME
            for (j in 0 until SHAPE_FRAME) frame[j] = audio[start + j] * window[j]
            val p = Fft.powerSpectrum(frame)
            for (k in mean.indices) mean[k] += p[k]
        }
        for (k in mean.indices) mean[k] /= frames.coerceAtLeast(1)

        val bands = SHAPE_EDGES_HZ.size - 1
        val shape = DoubleArray(bands)
        for (k in mean.indices) {
            val f = k * sampleRateHz / SHAPE_FRAME
            for (b in 0 until bands) {
                if (f >= SHAPE_EDGES_HZ[b] && f < SHAPE_EDGES_HZ[b + 1]) { shape[b] += mean[k]; break }
            }
        }
        val total = shape.sum()
        for (b in 0 until bands) out[ENV_BINS + b] = if (total > 0.0) shape[b] / total else shape[b]
    }
}
