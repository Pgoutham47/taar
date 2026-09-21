package com.taar.dsp

import kotlin.math.abs

/**
 * Detects a series arc from its amplitude modulation at twice the line frequency.
 *
 * One physical claim drives the design: an arc re-ignites twice per mains cycle,
 * so its broadband noise is modulated at 100 Hz. Everything follows from that.
 *
 *     bandpass 4-16 kHz  ->  rectify  ->  low-pass  ->  decimate
 *                        ->  FFT of the envelope
 *                        ->  power near 100 Hz over power in 20-400 Hz
 *
 * The spike measured this against four confounders that each share one of an arc's
 * properties. A plain high-band energy detector fired on every one of them, 100% of
 * the time; this statistic held its 5% design point. See spike/audio/README.md,
 * including the part about what has not been validated — no real arc was recorded.
 *
 * The envelope uses rectify + low-pass rather than a Hilbert transform. That
 * substitution was checked against the Hilbert version before being written here;
 * separation was equivalent within noise, and it costs one filter pass instead of
 * an FFT over the full 44.1 kHz clip.
 */
object ArcDetector {

    const val ARC_REP_HZ = 100.0

    /** Envelope band searched. 20 Hz excludes drift; 400 Hz spans the first harmonics. */
    private const val ENV_LO_HZ = 20.0
    private const val ENV_HI_HZ = 400.0

    /** Tolerance around the line-locked frequency, for grid drift. */
    private const val PEAK_HALF_WIDTH_HZ = 3.0

    /** Envelope rate after decimation. Resolves a few hundred Hz and keeps the FFT small. */
    private const val ENVELOPE_RATE_HZ = 2_000.0

    /**
     * Fraction of envelope power sitting at the line-locked frequency, in [0, 1].
     *
     * Normalising by total envelope power makes this independent of loudness, so a
     * single threshold serves a quiet cupboard and a plant room alike. The threshold
     * itself is learned from the room's own baseline captures, never hard-coded.
     */
    fun modulationIndex(
        audio: DoubleArray,
        sampleRateHz: Double,
        freqHz: Double = ARC_REP_HZ,
    ): Double {
        if (audio.size < 1024) return 0.0

        val band = hfBandpass().filter(audio)
        for (i in band.indices) band[i] = abs(band[i])
        val smoothed = envLowpass().filter(band)

        val step = (sampleRateHz / ENVELOPE_RATE_HZ).toInt().coerceAtLeast(1)
        val envRate = sampleRateHz / step
        val env = DoubleArray(smoothed.size / step) { smoothed[it * step] }
        if (env.size < 64) return 0.0

        val mean = env.average()
        val window = Fft.hann(env.size)
        val windowed = DoubleArray(env.size) { (env[it] - mean) * window[it] }

        val power = Fft.powerSpectrum(windowed)
        val freqs = Fft.frequencies(windowed.size, envRate)

        var total = 0.0
        var peak = 0.0
        for (k in power.indices) {
            val f = freqs[k]
            if (f < ENV_LO_HZ || f > ENV_HI_HZ) continue
            total += power[k]
            if (abs(f - freqHz) <= PEAK_HALF_WIDTH_HZ) peak += power[k]
        }
        return if (total > 0.0) peak / total else 0.0
    }

    /**
     * Naive comparison detector, kept because the pre-check screen shows both.
     * On its own it fires on any sound in the room — see the spike findings.
     */
    fun highBandRms(audio: DoubleArray): Double {
        if (audio.isEmpty()) return 0.0
        val band = hfBandpass().filter(audio)
        var acc = 0.0
        for (v in band) acc += v * v
        return kotlin.math.sqrt(acc / band.size)
    }

    private fun hfBandpass() = Biquad.hfBandpass4kTo16k()
    private fun envLowpass() = Biquad.envLowpass500()
}
