package com.taar.domain

/**
 * The domain model.
 *
 * An installation is one distribution board; a circuit is one conductor at it. The
 * baseline belongs to the circuit rather than to the app, because a reference for
 * this board has no meaning anywhere else — which is also why there is nothing here
 * a server would usefully hold.
 */

/** Field produced at 3 cm by one RMS ampere: mu0*sqrt(2)*I/(2*pi*r), in microtesla. */
const val UT_PER_AMP_AT_3CM = 9.4281

enum class Status { HEALTHY, WARNING, CRITICAL, UNKNOWN }

/** How a reading's current figure should be presented, given what was calibrated. */
enum class CurrentBasis {
    /** Calibrated against a known load on this circuit — amperes are meaningful. */
    CALIBRATED,

    /** Geometry assumed. Report a relative index, never a number with "A" after it. */
    UNCALIBRATED,
}

data class Circuit(
    val id: String,
    val label: String,
    /** Breaker rating in amperes, as printed on the device. Null when unknown. */
    val breakerRatingA: Double? = null,
    val baseline: Baseline? = null,
    /**
     * Microtesla per ampere for this circuit, established by measuring a known
     * load. Null means uncalibrated, and readings are reported as an index.
     */
    val utPerAmp: Double? = null,
) {
    val basis: CurrentBasis
        get() = if (utPerAmp != null) CurrentBasis.CALIBRATED else CurrentBasis.UNCALIBRATED
}

data class Installation(
    val id: String,
    val name: String,
    val circuits: List<Circuit> = emptyList(),
    /**
     * Excludes this installation's readings from threshold calibration. A bench rig
     * on a table should never teach the thresholds that will be applied to a real
     * board.
     */
    val isBenchRig: Boolean = false,
)

/** One capture, as produced by the sensor layer. */
data class Reading(
    val circuitId: String,
    val epochMillis: Long,
    /** Peak amplitude of the 50 Hz field component, microtesla. */
    val fieldAmplitudeUt: Double,
    /** Contrast-derived confidence a line component is present, in [0, 1]. */
    val lineConfidence: Double,
    /** Raw contrast ratio at the line frequency. ~1 is nothing, 100 is unmistakable. */
    val lineContrast: Double = 0.0,
    /** Envelope power at twice line frequency, as a fraction. */
    val arcModulationIndex: Double,
    /** False when the sine fit was ill-conditioned; the amplitude is not usable. */
    val fieldEstimateUsable: Boolean = true,
)

/**
 * A circuit's reference, recorded while it was known to be healthy.
 *
 * Stores the readings themselves rather than only their summary, so thresholds can
 * be recomputed as more labelled data arrives without asking the technician to
 * re-baseline.
 */
data class Baseline(
    val recordedAtMillis: Long,
    val fieldAmplitudesUt: DoubleArray,
    val arcModulationIndices: DoubleArray,
    val lineConfidences: DoubleArray,
) {
    val sampleCount: Int get() = fieldAmplitudesUt.size

    val medianFieldUt: Double get() = Stats.median(fieldAmplitudesUt)
    val medianArcIndex: Double get() = Stats.median(arcModulationIndices)
    val medianLineConfidence: Double get() = Stats.median(lineConfidences)

    /** A baseline from fewer than this many captures is not trustworthy. */
    val isSufficient: Boolean get() = sampleCount >= MIN_SAMPLES

    companion object {
        const val MIN_SAMPLES = 3
    }

    // DoubleArray gives reference equality by default, which silently breaks
    // comparisons and test assertions.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Baseline) return false
        return recordedAtMillis == other.recordedAtMillis &&
            fieldAmplitudesUt.contentEquals(other.fieldAmplitudesUt) &&
            arcModulationIndices.contentEquals(other.arcModulationIndices) &&
            lineConfidences.contentEquals(other.lineConfidences)
    }

    override fun hashCode(): Int {
        var result = recordedAtMillis.hashCode()
        result = 31 * result + fieldAmplitudesUt.contentHashCode()
        result = 31 * result + arcModulationIndices.contentHashCode()
        result = 31 * result + lineConfidences.contentHashCode()
        return result
    }
}
