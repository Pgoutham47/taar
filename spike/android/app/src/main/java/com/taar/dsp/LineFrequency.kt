package com.taar.dsp

/**
 * Guards against sampling the mains frequency at a rate that cannot see it.
 *
 * The pre-event spike found that the failure mode is not a slow sensor: 24 Hz
 * recovers a 50 Hz amplitude to 0.3%, while 100 Hz — the common Android default —
 * fails at 22% and 50 Hz fails completely.
 *
 * What governs it is how many distinct phases of the waveform a uniform stream
 * visits per cycle. Fitting a sinusoid of known frequency has two free parameters,
 * so fewer than three distinct phases leaves the fit ill-conditioned and the signal
 * indistinguishable from a constant — which mean-removal then deletes.
 *
 * See spike/dsp/README.md.
 */
object LineFrequency {

    const val DEFAULT_LINE_HZ = 50

    /** Distinct phases of the line waveform visited per cycle by a uniform stream. */
    fun distinctPhases(rateHz: Int, lineHz: Int = DEFAULT_LINE_HZ): Int {
        require(rateHz > 0) { "rateHz must be positive" }
        return rateHz / gcd(rateHz, lineHz)
    }

    /** True when a uniform stream at this rate cannot resolve the line frequency. */
    fun isLocked(rateHz: Int, lineHz: Int = DEFAULT_LINE_HZ): Boolean =
        distinctPhases(rateHz, lineHz) < 3

    /**
     * Nearest rate to [preferredHz] that is not harmonically locked.
     *
     * Called once at sensor registration. 100 -> 99, 50 -> 49, 25 -> 24.
     */
    fun safeRate(preferredHz: Int, lineHz: Int = DEFAULT_LINE_HZ): Int {
        if (!isLocked(preferredHz, lineHz)) return preferredHz
        for (delta in 1 until preferredHz) {
            for (candidate in intArrayOf(preferredHz - delta, preferredHz + delta)) {
                if (candidate >= MIN_USABLE_HZ && !isLocked(candidate, lineHz)) return candidate
            }
        }
        return preferredHz
    }

    /** Below this there are too few samples in a 3 s capture for a stable fit. */
    private const val MIN_USABLE_HZ = 8

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
