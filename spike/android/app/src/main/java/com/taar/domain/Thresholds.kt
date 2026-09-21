package com.taar.domain

/**
 * Per-circuit thresholds, calibrated from that circuit's own readings.
 *
 * A constant in the source would be wrong on the first board it met. A quiet
 * cupboard and a plant room have different noise floors, and the same absolute
 * field means different things on a lighting circuit and a motor feed.
 *
 * Expressed in MADs from the baseline median, so one pair of numbers has the same
 * meaning everywhere.
 */
data class Thresholds(
    /** Deviation at which a reading becomes a warning. */
    val warningZ: Double,
    /** Deviation at which it becomes critical. */
    val criticalZ: Double,
    /** How many labelled readings went into this. */
    val basedOn: Int,
) {
    init {
        require(criticalZ >= warningZ) { "critical must not be below warning" }
    }

    val isProvisional: Boolean get() = basedOn < MIN_LABELLED

    companion object {
        /** Until this many labelled readings exist, thresholds are marked provisional. */
        const val MIN_LABELLED = 5

        /**
         * Defaults before any labelling. Deliberately wide: a false critical on the
         * first use teaches a technician to ignore the tool, and that is not
         * recoverable.
         */
        val DEFAULT = Thresholds(warningZ = 4.0, criticalZ = 8.0, basedOn = 0)

        /**
         * Calibrate from labelled readings.
         *
         * With healthy labels only, thresholds sit above the healthy spread. Once
         * faulty labels exist, the critical threshold moves to halfway between the
         * healthy and faulty populations, which is where it separates them best.
         */
        fun calibrate(
            healthy: DoubleArray,
            faulty: DoubleArray = DoubleArray(0),
            baseline: DoubleArray,
        ): Thresholds {
            if (healthy.size < 2 || baseline.size < 2) return DEFAULT

            val healthyZ = DoubleArray(healthy.size) { Stats.robustZ(healthy[it], baseline) }
            // p95 of healthy, floored so a very tight cluster does not produce a
            // threshold that any noise trips.
            val warning = maxOf(Stats.quantile(healthyZ, 0.95), MIN_WARNING_Z)

            val critical = if (faulty.size >= 2) {
                val faultyZ = DoubleArray(faulty.size) { Stats.robustZ(faulty[it], baseline) }
                val midpoint = (warning + Stats.median(faultyZ)) / 2.0
                maxOf(midpoint, warning * 1.5)
            } else {
                warning * 2.0
            }

            return Thresholds(warning, critical, healthy.size + faulty.size)
        }

        private const val MIN_WARNING_Z = 3.0
    }

    fun classify(z: Double): Status = when {
        z >= criticalZ -> Status.CRITICAL
        z >= warningZ -> Status.WARNING
        else -> Status.HEALTHY
    }
}
