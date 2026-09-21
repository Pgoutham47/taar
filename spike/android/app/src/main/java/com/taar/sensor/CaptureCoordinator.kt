package com.taar.sensor

import com.taar.dsp.ArcDetector
import com.taar.dsp.LineFrequency
import com.taar.dsp.LombScargle
import com.taar.dsp.SineFit
import com.taar.dsp.Spectrogram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Runs the magnetometer and the microphone over the same window and returns one
 * reading.
 *
 * Both sensors are started together on purpose: an arc and the current that feeds
 * it are the same event, and comparing a field measurement to a sound recorded
 * thirty seconds later would be comparing two different moments.
 */
class CaptureCoordinator(
    private val mag: MagCapture,
    private val audio: AudioCapture,
) {

    data class Reading(
        val lineHz: Double,
        /** Peak amplitude of the 50 Hz field component, microtesla. */
        val fieldAmplitudeUt: Double,
        /** Normalised Lomb-Scargle power at 50 Hz, in [0, 1]. */
        /** Contrast-derived confidence a line component is present, in [0, 1]. */
        val lineConfidence: Double,
        /** Raw contrast ratio at the line frequency. ~1 is nothing, 100 is unmistakable. */
        val lineContrast: Double,
        /** Envelope power at 100 Hz as a fraction of the envelope band. */
        val arcModulationIndex: Double,
        val magMeasuredRateHz: Double,
        val magJitter: Double,
        val magRequestedRateHz: Int,
        /** False when the fit was ill-conditioned — see LineFrequency. */
        val fieldEstimateUsable: Boolean,
        val unprocessedAudioGranted: Boolean,
        /** Null when no audio was captured. Purely for display. */
        val spectrogram: Spectrogram.Result? = null,
    )

    suspend fun capture(
        durationSeconds: Double = 3.0,
        lineHz: Double = LineFrequency.DEFAULT_LINE_HZ.toDouble(),
    ): Reading? = coroutineScope {
        val magJob = async(Dispatchers.IO) { mag.capture(durationSeconds) }
        val audioJob = async(Dispatchers.IO) { audio.capture(durationSeconds) }

        val m = magJob.await() ?: return@coroutineScope null
        val a = audioJob.await()

        // Fit each axis, then combine. The AC field is a vector: its amplitude is
        // the root-sum-square of the per-axis amplitudes, and it is detected on
        // whichever axis is best aligned with it.
        // Detrended: a drifting baseline biases a least-squares sinusoid fit.
        val dx = LombScargle.detrend(m.tSeconds, m.xUt)
        val dy = LombScargle.detrend(m.tSeconds, m.yUt)
        val dz = LombScargle.detrend(m.tSeconds, m.zUt)

        val fitX = SineFit.fit(m.tSeconds, dx, lineHz)
        val fitY = SineFit.fit(m.tSeconds, dy, lineHz)
        val fitZ = SineFit.fit(m.tSeconds, dz, lineHz)
        val amplitude = kotlin.math.sqrt(
            fitX.amplitudeUt * fitX.amplitudeUt +
                fitY.amplitudeUt * fitY.amplitudeUt +
                fitZ.amplitudeUt * fitZ.amplitudeUt,
        )
        val fit = SineFit.Result(
            amplitudeUt = amplitude,
            phaseRad = fitX.phaseRad,
            conditioning = maxOf(fitX.conditioning, fitY.conditioning, fitZ.conditioning),
        )
        // Contrast against neighbouring frequencies, not against total variance.
        val contrast = maxOf(
            LombScargle.contrast(m.tSeconds, m.xUt, lineHz),
            LombScargle.contrast(m.tSeconds, m.yUt, lineHz),
            LombScargle.contrast(m.tSeconds, m.zUt, lineHz),
        )
        val confidence = LombScargle.confidenceFromContrast(contrast)
        val modulation = a?.let {
            ArcDetector.modulationIndex(it.samples, it.sampleRateHz, 2 * lineHz)
        } ?: 0.0
        val spectrogram = a?.let { Spectrogram.ofEnvelope(it.samples, it.sampleRateHz) }

        Reading(
            lineHz = lineHz,
            fieldAmplitudeUt = fit.amplitudeUt,
            lineConfidence = confidence,
            lineContrast = contrast,
            arcModulationIndex = modulation,
            magMeasuredRateHz = m.measuredRateHz,
            magJitter = m.jitter,
            magRequestedRateHz = m.requestedRateHz,
            fieldEstimateUsable = fit.conditioning >= MIN_CONDITIONING,
            unprocessedAudioGranted = a?.unprocessedGranted ?: false,
            spectrogram = spectrogram,
        )
    }

    private companion object {
        /**
         * Below this the sin/cos basis is close to collinear over the phases the
         * sampling actually visited, and the amplitude is not meaningful. Reporting
         * a number here rather than refusing is how a plausible wrong reading gets
         * in front of an electrician.
         */
        const val MIN_CONDITIONING = 0.05
    }
}
