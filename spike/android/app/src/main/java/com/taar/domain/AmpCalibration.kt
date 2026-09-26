package com.taar.domain

/**
 * Turns field amplitude into amperes for one cable, by measuring a known load.
 *
 * Needed because the geometry cannot be assumed. The textbook figure is 9.43 uT per
 * amp at 3 cm from a single conductor; the kettle's twin cord, where live and
 * neutral sit side by side and mostly cancel, measured about 0.048 -- two hundred
 * times less. Any ampere figure not calibrated on the cable itself is fiction.
 *
 * Captures are taken with the appliance off and then on, so a failure can say which
 * half went wrong rather than just that the number looks odd.
 */
object AmpCalibration {

    const val DEFAULT_VOLTAGE = 230.0

    /** Fewer captures than this per state and a median is one lucky reading. */
    const val MIN_CAPTURES = 2

    sealed interface Result {
        data class Ok(
            val utPerAmp: Double,
            val amps: Double,
            val offMedianUt: Double,
            val onMedianUt: Double,
        ) : Result

        data class Failed(val reason: String) : Result
    }

    fun ampsFromWatts(watts: Double, volts: Double = DEFAULT_VOLTAGE): Double = watts / volts

    /**
     * @param offFields field amplitudes with the appliance switched off
     * @param offConfidences line confidences for the same captures
     * @param onFields field amplitudes with it running
     * @param onConfidences line confidences for the same captures
     * @param amps the appliance's current, from its rating plate
     */
    fun calibrate(
        offFields: List<Double>,
        offConfidences: List<Double>,
        onFields: List<Double>,
        onConfidences: List<Double>,
        amps: Double,
    ): Result {
        if (!(amps > 0.0)) return Result.Failed("Enter the appliance's power in watts first.")
        if (offFields.size < MIN_CAPTURES || onFields.size < MIN_CAPTURES) {
            return Result.Failed("Not enough captures succeeded. Run the phone check, then try again.")
        }

        val offState = LineState.of(Stats.median(offConfidences.toDoubleArray()))
        val onState = LineState.of(Stats.median(onConfidences.toDoubleArray()))

        if (offState == LineState.FLOWING) {
            return Result.Failed(
                "Current was already flowing with the appliance OFF. Something else on this " +
                    "cable is drawing power. Switch it off, or use a different cable.",
            )
        }
        if (onState != LineState.FLOWING) {
            return Result.Failed(
                "Taar did not see clear current with the appliance ON. Check it was heating, " +
                    "move the phone closer to the cable, and try again.",
            )
        }

        val offMedian = Stats.median(offFields.toDoubleArray())
        val onMedian = Stats.median(onFields.toDoubleArray())
        if (onMedian <= offMedian) {
            return Result.Failed(
                "The field did not rise when the appliance came on. Move the phone and try again.",
            )
        }

        // The ratio uses the running field alone, not the rise over idle. Amperes
        // are only ever reported while current is flowing (see Metrics.derive), and
        // a reading is then divided by this same factor, so the calibration point
        // reads back exactly.
        return Result.Ok(
            utPerAmp = onMedian / amps,
            amps = amps,
            offMedianUt = offMedian,
            onMedianUt = onMedian,
        )
    }
}
