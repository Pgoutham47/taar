package com.taar.domain

import kotlin.math.abs

/**
 * The evidence a reading provides, derived once and then consumed by the rules
 * engine.
 *
 * Separating derivation from interpretation means the catalogue can be edited
 * without touching any arithmetic, and the numbers shown to the technician are the
 * same ones the rules saw.
 */
data class Metrics(
    /** Field amplitude in MADs above the circuit's healthy baseline. */
    val loadZ: Double,
    /** Arc modulation in MADs above baseline. */
    val arcZ: Double,
    /** Current as a fraction of the breaker rating. Null when either is unknown. */
    val loadVsRating: Double?,
    /** Implied RMS current. Null unless the circuit is calibrated. */
    val impliedCurrentA: Double?,
    /** Raw line confidence, in [0, 1]. */
    val lineConfidence: Double,
    /** Baseline line confidence, for deciding whether a dead circuit is unexpected. */
    val baselineLineConfidence: Double,
    /** False when the field amplitude is not usable and load rules must be skipped. */
    val fieldUsable: Boolean,
) {
    val lineState: LineState get() = LineState.of(lineConfidence)

    /** Current is flowing now. */
    val isLive: Boolean get() = lineState == LineState.FLOWING

    /** Was live when the baseline was taken, so being dead now is a change. */
    val wasLive: Boolean get() = LineState.of(baselineLineConfidence) == LineState.FLOWING

    companion object {
        /**
         * Confidence at or above which current is considered to be flowing.
         *
         * Set from measurement. Contrast maps to confidence as c / (c + 9).
         *
         *     fridge   21 Sept  idle  3x 2x 2x                 running  9x 16x
         *     charger  21 Sept  idle  6x                       on       1x
         *     kettle   26 Sept  idle  3x 1x 6x 3x 0x 4x 5x 7x  on       28x 37x 43x 58x 62x
         *
         * The kettle is 1200 W, about 5.2 A. The charger's "on" was a third of an
         * amp through a twin cable and detected nothing.
         *
         * The old 0.40 (6x) sat inside the no-current range: the kettle's last idle
         * capture read 7x and was reported as live. 0.60 is 13.5x, roughly midway
         * on a log scale between the highest idle (7x) and the lowest kettle
         * reading (28x).
         */
        const val LIVE_CONFIDENCE = 0.60

        /**
         * Confidence below which no current is considered to be flowing. 0.47 is 8x,
         * just above the highest idle capture seen so far.
         *
         * Between this and [LIVE_CONFIDENCE] the reading is [LineState.UNCLEAR].
         * The band is deliberate: the fridge's 9x sits in it, and a small load that
         * close to room noise should be measured again rather than called either way.
         */
        const val IDLE_CONFIDENCE = 0.47

        /**
         * Floors for the baseline spread. Below these, a baseline is flat because
         * the statistic cannot resolve finer, not because the circuit is
         * exceptionally steady.
         *
         * [MIN_ARC_SPREAD] was originally 0.002, chosen by guesswork. Nine captures
         * on real hardware put the modulation index between 0.0062 and 0.0525 with
         * a MAD of 0.0104 -- five times that floor. Dividing ordinary variation by
         * a floor five times too small reported a fridge at 20 MAD above baseline
         * and raised an arcing warning on it.
         *
         * Set from the measured spread. Still provisional: nine captures on one
         * phone, none of them next to a real arc.
         */
        const val MIN_FIELD_SPREAD_UT = 0.15
        const val MIN_ARC_SPREAD = 0.010

        fun derive(reading: Reading, circuit: Circuit): Metrics? {
            val baseline = circuit.baseline ?: return null
            if (!baseline.isSufficient) return null

            val loadZ = if (reading.fieldEstimateUsable)
                Stats.robustZ(reading.fieldAmplitudeUt, baseline.fieldAmplitudesUt,
                    MIN_FIELD_SPREAD_UT) else 0.0
            val arcZ = Stats.robustZ(reading.arcModulationIndex, baseline.arcModulationIndices,
                MIN_ARC_SPREAD)

            val current = circuit.utPerAmp
                ?.takeIf { it > 0 && reading.fieldEstimateUsable }
                ?.let { reading.fieldAmplitudeUt / it }

            val vsRating = current?.let { c ->
                circuit.breakerRatingA?.takeIf { it > 0 }?.let { c / it }
            }

            return Metrics(
                loadZ = loadZ,
                arcZ = arcZ,
                loadVsRating = vsRating,
                impliedCurrentA = current,
                lineConfidence = reading.lineConfidence,
                baselineLineConfidence = baseline.medianLineConfidence,
                fieldUsable = reading.fieldEstimateUsable,
            )
        }
    }

    /** Magnitude of change, ignoring direction, for ranking how far off a reading is. */
    val worstZ: Double get() = maxOf(abs(loadZ), abs(arcZ))
}

/** What the 50 Hz signal says about current in the cable, in the terms shown on screen. */
enum class LineState {
    /** Nothing above room noise. Says nothing about voltage. */
    NONE,

    /** Above room noise but not clearly current. Measure again. */
    UNCLEAR,

    /** Current is flowing in the cable. */
    FLOWING;

    companion object {
        fun of(confidence: Double): LineState = when {
            confidence >= Metrics.LIVE_CONFIDENCE -> FLOWING
            confidence >= Metrics.IDLE_CONFIDENCE -> UNCLEAR
            else -> NONE
        }

        /** Inverse of the contrast-to-confidence mapping, for display. */
        fun contrastOf(confidence: Double): Double =
            if (confidence >= 1.0) Double.POSITIVE_INFINITY
            else 9.0 * confidence / (1.0 - confidence)
    }
}
