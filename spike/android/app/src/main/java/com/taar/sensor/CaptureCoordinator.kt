package com.taar.sensor

import com.taar.dsp.ArcDetector
import com.taar.dsp.ArcFeatures
import com.taar.dsp.LineFrequency
import com.taar.dsp.LombScargle
import com.taar.dsp.SineFit
import com.taar.dsp.Spectrogram
import com.taar.ml.ArcModel
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
    /** Null on a phone without a gyroscope; readings then carry no motion verdict. */
    private val motion: MotionCapture? = null,
    /** Null when the model failed to load; captures then carry the rule alone. */
    private val arcModel: ArcModel? = null,
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
        /** The on-device model's probability that the sound is arc-like. Advisory. */
        val aiArcProbability: Float? = null,
        /** RMS angular speed during the capture, rad/s. Null without a gyroscope. */
        val motionRadPerS: Double? = null,
        /** The capture's intermediate signals, for the live view. Only when asked for. */
        val view: LiveView? = null,
    )

    /**
     * What the detectors actually worked on in one capture, kept for display. Every
     * array here is a value the pipeline computed anyway, or a thinned copy of one.
     */
    class LiveView(
        val lineHz: Double,
        val magRateHz: Double,
        /** Seconds from the first sample, and the detrended values of the axis with the strongest fit. */
        val magT: DoubleArray,
        val magValuesUt: DoubleArray,
        val magAxis: Char,
        /** That axis's fitted line component: amplitudeUt * sin(2 pi f t + phaseRad). */
        val magFitAmplitudeUt: Double,
        val magFitPhaseRad: Double,
        /** Raw audio thinned to columns: the lowest and highest sample in each. */
        val audioMin: FloatArray,
        val audioMax: FloatArray,
        val audioSeconds: Double,
        /** 60 ms of the arc detector's 4-16 kHz envelope, where it was strongest. */
        val envelopeSnippet: DoubleArray,
        val envelopeRateHz: Double,
        /** Envelope power per 5 Hz bin from 20 Hz: the spectrum the model reads. */
        val envelopeSpectrum: DoubleArray,
    )

    /** @param detail also return [Reading.view]; costs a little extra work, so only the live view asks. */
    suspend fun capture(
        durationSeconds: Double = 3.0,
        lineHz: Double = LineFrequency.DEFAULT_LINE_HZ.toDouble(),
        detail: Boolean = false,
    ): Reading? = coroutineScope {
        val magJob = async(Dispatchers.IO) { mag.capture(durationSeconds) }
        val audioJob = async(Dispatchers.IO) { audio.capture(durationSeconds) }
        // Over the same window: a verdict on a different three seconds would be worthless.
        val motionJob = async(Dispatchers.IO) { motion?.capture(durationSeconds) }

        val m = magJob.await() ?: return@coroutineScope null
        val a = audioJob.await()
        val turning = motionJob.await()

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
        // With detail, the features are computed once and shared by the model and
        // the view, so the view never runs a second copy of the pipeline.
        val detailed = if (detail) a?.let { ArcFeatures.detailed(it.samples, it.sampleRateHz) } else null
        // Scored on every capture, reference and calibration included: the model is
        // cheap, and a reading should never be missing the second opinion.
        val aiArc = a?.let { clip ->
            arcModel?.let { model ->
                runCatching {
                    detailed?.let { model.probability(it.features) }
                        ?: model.probability(clip.samples, clip.sampleRateHz)
                }.getOrNull()
            }
        }
        val view = if (!detail) null else {
            val fits = listOf(Triple('x', dx, fitX), Triple('y', dy, fitY), Triple('z', dz, fitZ))
            val (axis, values, axisFit) = fits.maxByOrNull { it.third.amplitudeUt }!!
            val cols = AUDIO_COLUMNS
            val samples = a?.samples ?: DoubleArray(0)
            val per = (samples.size / cols).coerceAtLeast(1)
            val lo = FloatArray(if (samples.isEmpty()) 0 else cols) { c ->
                var v = Double.MAX_VALUE
                for (i in c * per until minOf(samples.size, (c + 1) * per)) v = minOf(v, samples[i])
                if (v == Double.MAX_VALUE) 0f else v.toFloat()
            }
            val hi = FloatArray(lo.size) { c ->
                var v = -Double.MAX_VALUE
                for (i in c * per until minOf(samples.size, (c + 1) * per)) v = maxOf(v, samples[i])
                if (v == -Double.MAX_VALUE) 0f else v.toFloat()
            }
            LiveView(
                lineHz = lineHz,
                magRateHz = m.measuredRateHz,
                magT = m.tSeconds,
                magValuesUt = values,
                magAxis = axis,
                magFitAmplitudeUt = axisFit.amplitudeUt,
                magFitPhaseRad = axisFit.phaseRad,
                audioMin = lo,
                audioMax = hi,
                audioSeconds = a?.let { it.samples.size / it.sampleRateHz } ?: 0.0,
                envelopeSnippet = detailed?.let { strongestWindow(it.envelope, (0.060 * it.envelopeRateHz).toInt()) }
                    ?: DoubleArray(0),
                envelopeRateHz = detailed?.envelopeRateHz ?: 0.0,
                envelopeSpectrum = detailed?.envelopeSpectrum() ?: DoubleArray(0),
            )
        }

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
            aiArcProbability = aiArc,
            motionRadPerS = turning,
            view = view,
        )
    }

    /** The window of [n] samples with the most energy: where a pattern is easiest to see. */
    private fun strongestWindow(x: DoubleArray, n: Int): DoubleArray {
        if (x.size <= n || n <= 0) return x.copyOf()
        var sum = 0.0
        for (i in 0 until n) sum += x[i] * x[i]
        var best = sum
        var start = 0
        for (i in n until x.size) {
            sum += x[i] * x[i] - x[i - n] * x[i - n]
            if (sum > best) { best = sum; start = i - n + 1 }
        }
        return x.copyOfRange(start, start + n)
    }

    private companion object {
        /** Columns the raw audio is thinned to for drawing. */
        const val AUDIO_COLUMNS = 300

        /**
         * Below this the sin/cos basis is close to collinear over the phases the
         * sampling actually visited, and the amplitude is not meaningful. Reporting
         * a number here rather than refusing is how a plausible wrong reading gets
         * in front of an electrician.
         */
        const val MIN_CONDITIONING = 0.05
    }
}
