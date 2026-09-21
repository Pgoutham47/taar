package com.taar.domain

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Robust statistics.
 *
 * Median and MAD rather than mean and standard deviation throughout, because the
 * readings these are computed over are labelled by a technician on a live site. One
 * capture taken while a lift motor started, or with the phone knocked, should not
 * move a threshold. A single outlier moves a mean; it does not move a median.
 */
object Stats {

    /** Scale factor making MAD a consistent estimator of sigma for normal data. */
    const val MAD_TO_SIGMA = 1.4826

    fun median(values: DoubleArray): Double {
        require(values.isNotEmpty()) { "median of empty" }
        val sorted = values.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** Median absolute deviation, scaled to be comparable with a standard deviation. */
    fun mad(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        val m = median(values)
        return MAD_TO_SIGMA * median(DoubleArray(values.size) { abs(values[it] - m) })
    }

    fun mean(values: DoubleArray): Double =
        if (values.isEmpty()) 0.0 else values.sum() / values.size

    fun stdDev(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    /** Linear-interpolated quantile, [q] in [0, 1]. */
    fun quantile(values: DoubleArray, q: Double): Double {
        require(values.isNotEmpty()) { "quantile of empty" }
        require(q in 0.0..1.0) { "q out of range: $q" }
        val sorted = values.sortedArray()
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo])
    }

    /**
     * Deviation from the median in MADs — the unit every threshold in this app is
     * expressed in, so a threshold means the same thing on a quiet board and a
     * noisy one.
     *
     * Returns 0 when the spread is zero, rather than infinity: identical readings
     * mean no information, not infinite confidence.
     */
    fun robustZ(value: Double, reference: DoubleArray, minSpread: Double = 0.0): Double {
        if (reference.size < 2) return 0.0
        // A baseline can be flatter than the sensor can actually resolve -- three
        // captures of a quiet circuit may all report the same value. Dividing by
        // that spread turns sensor quantisation into a large z-score and a
        // confident wrong answer. Found on hardware: a 0.21 uT change reported as
        // 71 MAD above baseline.
        val spread = maxOf(mad(reference), minSpread)
        if (spread <= 0.0) return 0.0
        return (value - median(reference)) / spread
    }
}
