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
    /** False when the field amplitude is not usable and load rules must be skipped. */
    val fieldUsable: Boolean,
    /**
     * The technician said this circuit's supply is switched off. Only the person at
     * the board knows this; the phone senses current, not voltage, so it cannot
     * tell an isolated circuit from a live one with nothing switched on.
     */
    val supplyIsolated: Boolean = false,
    /**
     * Current when the reference was recorded, amperes. Null unless the circuit is
     * calibrated. Reference captures with no clear current count as 0 A.
     */
    val referenceCurrentA: Double? = null,
) {
    val lineState: LineState get() = LineState.of(lineConfidence)

    /** Current is flowing now. */
    val isLive: Boolean get() = lineState == LineState.FLOWING

    /** Amperes now, counting "no clear current" as 0 A. Null unless calibrated. */
    val currentNowA: Double?
        get() = referenceCurrentA?.let { if (fieldUsable) impliedCurrentA ?: 0.0 else null }

    /**
     * Whether the load is up on the reference.
     *
     * In amperes when the circuit is calibrated. On a twin cord the field barely
     * moves -- the kettle raised it 0.18 uT, about 1 MAD against the 0.15 uT spread
     * floor -- so a 5 A load never reached the warn threshold. The same change in
     * amperes is 0 A to 5 A, which no one would call noise.
     *
     * Otherwise in MADs of field, as before.
     */
    fun loadAboveReference(t: Thresholds): Boolean {
        val now = currentNowA
        val ref = referenceCurrentA
        if (now == null || ref == null) return loadZ >= t.warningZ
        return now - ref >= MIN_CURRENT_RISE_A && now >= ref * MIN_CURRENT_RATIO
    }

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

        /**
         * A calibrated rise smaller than this is not called a higher load. The
         * kettle's five boiling captures spanned 0.18-0.27 uT, about +/-1 A at one
         * calibration, so a smaller change cannot be told from that scatter.
         */
        const val MIN_CURRENT_RISE_A = 1.0

        /** And the rise must be half as much again, so a busy circuit is not flagged for a lamp. */
        const val MIN_CURRENT_RATIO = 1.5

        fun derive(reading: Reading, circuit: Circuit): Metrics? {
            val baseline = circuit.baseline ?: return null
            if (!baseline.isSufficient) return null

            val loadZ = if (reading.fieldEstimateUsable)
                Stats.robustZ(reading.fieldAmplitudeUt, baseline.fieldAmplitudesUt,
                    MIN_FIELD_SPREAD_UT) else 0.0
            val arcZ = Stats.robustZ(reading.arcModulationIndex, baseline.arcModulationIndices,
                MIN_ARC_SPREAD)

            // No amperes unless current is clearly flowing. At idle the fitted
            // amplitude is room noise, and dividing noise by a calibration factor
            // produces a confident, fictional current.
            val current = circuit.utPerAmp
                ?.takeIf {
                    it > 0 && reading.fieldEstimateUsable &&
                        LineState.of(reading.lineConfidence) == LineState.FLOWING
                }
                ?.let { reading.fieldAmplitudeUt / it }

            val referenceCurrent = circuit.utPerAmp?.takeIf { it > 0 }?.let { k ->
                Stats.median(
                    DoubleArray(baseline.sampleCount) { i ->
                        if (LineState.of(baseline.lineConfidences[i]) == LineState.FLOWING)
                            baseline.fieldAmplitudesUt[i] / k else 0.0
                    },
                )
            }

            val vsRating = current?.let { c ->
                circuit.breakerRatingA?.takeIf { it > 0 }?.let { c / it }
            }

            return Metrics(
                loadZ = loadZ,
                arcZ = arcZ,
                loadVsRating = vsRating,
                impliedCurrentA = current,
                lineConfidence = reading.lineConfidence,
                fieldUsable = reading.fieldEstimateUsable,
                supplyIsolated = reading.supplyIsolated,
                referenceCurrentA = referenceCurrent,
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
