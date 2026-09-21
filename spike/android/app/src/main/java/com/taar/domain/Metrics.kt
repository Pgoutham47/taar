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
    /** Mains detected now. */
    val isLive: Boolean get() = lineConfidence >= LIVE_CONFIDENCE

    /** Was live when the baseline was taken, so being dead now is a change. */
    val wasLive: Boolean get() = baselineLineConfidence >= LIVE_CONFIDENCE

    companion object {
        /**
         * Normalised Lomb-Scargle power above which a 50 Hz component is considered
         * present. The spike measured idle captures at 0.0003 and a 0.5 A load at
         * 0.977, so this sits far from both populations.
         */
        const val LIVE_CONFIDENCE = 0.30

        fun derive(reading: Reading, circuit: Circuit): Metrics? {
            val baseline = circuit.baseline ?: return null
            if (!baseline.isSufficient) return null

            val loadZ = if (reading.fieldEstimateUsable)
                Stats.robustZ(reading.fieldAmplitudeUt, baseline.fieldAmplitudesUt) else 0.0
            val arcZ = Stats.robustZ(reading.arcModulationIndex, baseline.arcModulationIndices)

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
