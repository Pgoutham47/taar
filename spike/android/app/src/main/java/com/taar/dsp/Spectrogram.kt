package com.taar.dsp

import kotlin.math.ln
import kotlin.math.max

/**
 * Spectrogram of the envelope band, for the result screen.
 *
 * Computed over the *envelope* rather than the raw audio. An arc's signature is a
 * pattern in how the high-frequency energy is modulated over time, so a picture of
 * the envelope's spectrum shows the thing being decided on. A raw 0-22 kHz
 * spectrogram would be prettier and would not show it.
 *
 * Output is small on purpose — a few dozen frames by a few dozen bins — because it
 * is drawn on a phone and is a visual check, not an analysis surface.
 */
object Spectrogram {

    data class Result(
        /** [frame][bin], normalised to 0..1. */
        val cells: Array<DoubleArray>,
        val frameCount: Int,
        val binCount: Int,
        val maxFrequencyHz: Double,
        val durationSeconds: Double,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            if (cells.size != other.cells.size) return false
            return cells.indices.all { cells[it].contentEquals(other.cells[it]) }
        }

        override fun hashCode(): Int = cells.sumOf { it.contentHashCode() }
    }

    private const val ENVELOPE_RATE_HZ = 2_000.0
    private const val MAX_DISPLAY_HZ = 400.0

    /**
     * @param frames how many time slices to produce
     * @param bins how many frequency rows
     */
    fun ofEnvelope(
        audio: DoubleArray,
        sampleRateHz: Double,
        frames: Int = 32,
        bins: Int = 24,
    ): Result? {
        require(frames > 0 && bins > 0) { "frames and bins must be positive" }
        if (audio.size < 2048) return null

        val band = Biquad.hfBandpass4kTo16k().filter(audio)
        for (i in band.indices) band[i] = kotlin.math.abs(band[i])
        val smoothed = Biquad.envLowpass500().filter(band)

        val step = (sampleRateHz / ENVELOPE_RATE_HZ).toInt().coerceAtLeast(1)
        val envRate = sampleRateHz / step
        val env = DoubleArray(smoothed.size / step) { smoothed[it * step] }
        if (env.size < frames * 8) return null

        val frameLen = env.size / frames
        // A power of two keeps the FFT exact-length rather than zero-padding each
        // frame differently, which would make the bins mean different things.
        val fftLen = Integer.highestOneBit(max(64, frameLen))
        val window = Fft.hann(fftLen)

        val cells = Array(frames) { DoubleArray(bins) }
        var peak = 0.0

        for (f in 0 until frames) {
            val start = f * frameLen
            val slice = DoubleArray(fftLen) {
                val idx = start + it
                if (idx < env.size) env[idx] else 0.0
            }
            val mean = slice.average()
            for (i in slice.indices) slice[i] = (slice[i] - mean) * window[i]

            val power = Fft.powerSpectrum(slice)
            val freqs = Fft.frequencies(fftLen, envRate)

            for (b in 0 until bins) {
                val lo = b * MAX_DISPLAY_HZ / bins
                val hi = (b + 1) * MAX_DISPLAY_HZ / bins
                var acc = 0.0
                var n = 0
                for (k in power.indices) {
                    if (freqs[k] in lo..hi) { acc += power[k]; n++ }
                }
                val v = if (n > 0) acc / n else 0.0
                cells[f][b] = v
                if (v > peak) peak = v
            }
        }

        // Log scale, then normalise. Linear power makes everything but the loudest
        // frame black, which is useless as a visual check.
        if (peak > 0) {
            for (f in 0 until frames) for (b in 0 until bins) {
                cells[f][b] = ln(1.0 + 99.0 * cells[f][b] / peak) / ln(100.0)
            }
        }

        return Result(
            cells = cells,
            frameCount = frames,
            binCount = bins,
            maxFrequencyHz = MAX_DISPLAY_HZ,
            durationSeconds = audio.size / sampleRateHz,
        )
    }
}
