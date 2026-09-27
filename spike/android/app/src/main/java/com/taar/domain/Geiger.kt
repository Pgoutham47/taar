package com.taar.domain

import com.taar.dsp.LombScargle
import kotlin.math.exp
import kotlin.math.ln

/**
 * Geiger mode: the 50 Hz signal turned into clicks that speed up near a wire
 * carrying current.
 *
 * Uses the same detector as a measurement -- 50 Hz contrast against neighbouring
 * frequencies, best of the three axes -- over a short sliding window, so it can
 * answer several times a second instead of once per 3 s capture. A shorter window
 * gives a lower contrast for the same field (it grows with the number of samples),
 * which is why the scale here is not the 8x / 13.5x of a measurement.
 *
 * It needs no reference and no circuit. It says only "stronger" or "weaker": the
 * contrast depends on distance, angle and the load on the wire, so it is never
 * turned into amperes, and silence never means "no wire".
 */
object Geiger {

    /** Seconds of magnetometer data behind each update. */
    const val WINDOW_SECONDS = 1.2

    /** How often the level is recomputed. */
    const val UPDATE_MILLIS = 120L

    /** Clicks per second at full strength. Faster than this blurs into a buzz. */
    const val MAX_CLICKS_PER_S = 25.0

    /** Clicks per second just above the quiet level: slow enough to hear as separate. */
    const val MIN_CLICKS_PER_S = 1.5

    /**
     * Contrast at which clicking starts, and at which it is fastest.
     *
     * Room noise sits around 1-3x. The kettle cord read 28-62x over 3 s, so about
     * a third of that over 1.2 s; 30x is reached with the phone on a working
     * appliance's cord.
     */
    const val DEFAULT_QUIET = 5.0
    const val DEFAULT_FULL = 30.0

    /** "Set quiet here" never lifts the floor above this: a floor that high would hide a real cable. */
    const val MAX_QUIET = 20.0

    /** Full strength is always at least this multiple of the quiet level, so the scale never collapses. */
    const val MIN_SPAN = 4.0

    /** How far the level is from quiet to full, in log terms: a cable feels the same at 2 cm and 6 cm apart. */
    data class Scale(val quiet: Double = DEFAULT_QUIET, val full: Double = DEFAULT_FULL) {
        init {
            require(quiet > 0 && full > quiet) { "full must be above quiet" }
        }

        /** 0 at or below quiet, 1 at or above full, logarithmic between. */
        fun level(contrast: Double): Double {
            if (contrast <= quiet) return 0.0
            return (ln(contrast / quiet) / ln(full / quiet)).coerceIn(0.0, 1.0)
        }

        /**
         * A new scale whose quiet level sits above the background here. Taken with
         * the phone held away from any cable, so a room full of chargers does not
         * click all the time.
         */
        fun quietAt(background: Double): Scale {
            val q = (background * 1.5).coerceIn(DEFAULT_QUIET, MAX_QUIET)
            return Scale(q, maxOf(DEFAULT_FULL, q * MIN_SPAN))
        }
    }

    enum class Zone(val title: String, val detail: String) {
        QUIET("No current nearby", "Move slowly along the wall or cable"),
        NEAR("Current nearby", "Keep moving; follow the faster clicks"),
        CLOSE("Very close to a wire carrying current", "The strongest spot is under the phone"),
        ;

        companion object {
            fun of(level: Double): Zone = when {
                level <= 0.0 -> QUIET
                level < CLOSE_LEVEL -> NEAR
                else -> CLOSE
            }

            const val CLOSE_LEVEL = 0.6
        }
    }

    /** Clicks per second for a level: none when quiet, then rising faster near the top. */
    fun clicksPerSecond(level: Double): Double {
        if (level <= 0.0) return 0.0
        val l = level.coerceAtMost(1.0)
        return MIN_CLICKS_PER_S + (MAX_CLICKS_PER_S - MIN_CLICKS_PER_S) * l * l
    }

    /**
     * Whether to click in a tick of [tickSeconds]. Clicks are random at the given
     * rate, as a real Geiger counter's are: a steady metronome sounds like a
     * timer, and random clicks read immediately as "detector".
     *
     * At most one click per tick, with probability rate x tick, so the average is
     * exactly the rate while ticks stay short (25/s x 10 ms = 0.25). The Poisson
     * form, 1 - e^(-rate x tick), undercounts: it asked for 10/s and gave 9.2.
     *
     * @param tickSeconds the time since the last tick as it actually elapsed.
     * @param u a uniform random number in [0, 1).
     */
    fun clickInTick(clicksPerSecond: Double, tickSeconds: Double, u: Double): Boolean =
        clicksPerSecond > 0 && u < (clicksPerSecond * tickSeconds).coerceAtMost(1.0)

    /** 50 Hz contrast of a window, best of the three axes, exactly as a measurement computes it. */
    fun contrast(tSeconds: DoubleArray, x: DoubleArray, y: DoubleArray, z: DoubleArray): Double = maxOf(
        LombScargle.contrast(tSeconds, x),
        LombScargle.contrast(tSeconds, y),
        LombScargle.contrast(tSeconds, z),
    )

    /**
     * Smooths contrast in log terms. Rises quickly, so moving onto a cable is heard
     * at once; falls more slowly, so one noisy window does not cut the clicking out.
     */
    class Smoother(private val attack: Double = 0.6, private val release: Double = 0.25) {
        private var logValue: Double? = null

        fun update(contrast: Double): Double {
            val x = ln(contrast.coerceAtLeast(1e-3))
            val prev = logValue
            val next = if (prev == null) x else prev + (if (x > prev) attack else release) * (x - prev)
            logValue = next
            return exp(next)
        }

        fun reset() { logValue = null }
    }
}
