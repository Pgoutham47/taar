package com.taar.domain

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * What a wire's load costs, in rupees a month.
 *
 * Only from a calibrated current: an uncalibrated circuit has a relative index, not
 * amperes, and a rupee figure made from an index would be invented. Power is taken
 * as volts times amps, which is exact for a heater or kettle and somewhat high for
 * a motor (a fridge or an AC), whose power factor is below one. The rate is the
 * energy charge per unit only; fixed charges and duty on the bill are left out.
 */
object EnergyCost {

    /** Nominal Indian mains voltage. */
    const val MAINS_VOLTS = 230.0

    /**
     * TSSPDCL (Hyderabad) domestic energy charge, FY 2025-26, for the slab a
     * typical city home is billed at: 201-300 units a month. Changeable, because
     * the slab depends on the whole home's use, which one wire cannot know.
     */
    const val DEFAULT_RATE = 7.70

    const val DEFAULT_HOURS = 4.0

    /** The usage choices offered: an hour, an evening, a working day, always on. */
    val HOUR_CHOICES = listOf(1.0, 4.0, 8.0, 24.0)

    const val DAYS_PER_MONTH = 30

    data class Estimate(
        val amps: Double,
        val kilowatts: Double,
        val hoursPerDay: Double,
        val ratePerUnit: Double,
        /** kWh, which is what a bill calls a unit. */
        val unitsPerMonth: Double,
        val rupeesPerMonth: Double,
    )

    /** Null when there is nothing to cost: no current, or a rate or hours that make no sense. */
    fun estimate(amps: Double?, hoursPerDay: Double, ratePerUnit: Double): Estimate? {
        if (amps == null || amps <= 0.0 || hoursPerDay <= 0.0 || hoursPerDay > 24.0 || ratePerUnit <= 0.0) return null
        val kw = amps * MAINS_VOLTS / 1000.0
        val units = kw * hoursPerDay * DAYS_PER_MONTH
        return Estimate(amps, kw, hoursPerDay, ratePerUnit, units, units * ratePerUnit)
    }

    /**
     * Rounded to two significant figures: the current is an estimate, so ₹2,127
     * would claim a precision the reading does not have. Indian digit grouping.
     */
    fun roundRupees(rupees: Double): Long {
        if (rupees < 10) return rupees.roundToLong()
        val step = 10.0.pow(floor(log10(rupees)) - 1)
        return ((rupees / step).roundToLong() * step).roundToLong()
    }

    fun rupees(rupees: Double): String = "₹" + grouped(roundRupees(rupees))

    /**
     * Indian digit grouping: 1,20,000. Done by hand because the JVM and Android
     * disagree on it for the same locale -- the desktop JDK printed 120,000.
     */
    fun grouped(n: Long): String {
        val digits = kotlin.math.abs(n).toString()
        if (digits.length <= 3) return (if (n < 0) "-" else "") + digits
        val head = digits.dropLast(3)
        val pairs = head.reversed().chunked(2).joinToString(",").reversed()
        return (if (n < 0) "-" else "") + pairs + "," + digits.takeLast(3)
    }

    /** Hours without a trailing ".0": "4 h", "24 h". */
    fun hours(h: Double): String = if (h == floor(h)) "${h.toLong()} h" else "$h h"
}
