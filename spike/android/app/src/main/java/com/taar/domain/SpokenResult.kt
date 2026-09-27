package com.taar.domain

/**
 * A measurement's result as one or two short spoken sentences.
 *
 * The phone is often pressed against a cable facing the wall, so the technician
 * hears the answer instead of turning the phone round to read it. The words follow
 * the same rules as the screen: say what was observed, lead with a warning when
 * there is one, and never call a wire safe. A switched-off circuit with no current
 * is reminded that the phone senses current, not voltage.
 */
object SpokenResult {

    fun of(
        outcome: Fusion.Outcome,
        line: LineState,
        circuitLabel: String?,
        impliedCurrentA: Double?,
        cost: EnergyCost.Estimate? = null,
    ): String {
        val flowing = impliedCurrentA?.takeIf { line == LineState.FLOWING && it >= 0.1 }
        // Written as the voice should say it: "2,100 rupees", never "₹2,100".
        val rupees = cost?.takeIf { flowing != null }?.let {
            val n = EnergyCost.grouped(EnergyCost.roundRupees(it.rupeesPerMonth))
            " That is about $n rupees a month at ${EnergyCost.hours(it.hoursPerDay).removeSuffix(" h")} hours a day."
        } ?: ""
        val amps = flowing?.let { " About ${"%.1f".format(it)} amps.$rupees" } ?: ""
        val body = when (outcome) {
            Fusion.Outcome.CURRENT_ON_ISOLATED ->
                "Warning. Current detected on a circuit you marked off. Do not touch it."
            Fusion.Outcome.ELECTRICAL_ANOMALY ->
                "Warning. Current and sound are both unusual. Have this wire checked.$amps"
            Fusion.Outcome.POSSIBLE_ARCING ->
                "Warning. Possible sparking. Have this wire checked.$amps"
            Fusion.Outcome.NEAR_BREAKER_LIMIT ->
                "Warning. Load is near the breaker's limit.$amps"
            Fusion.Outcome.CURRENT_ABNORMAL ->
                "Current is higher than normal. No sparking sound heard.$amps"
            Fusion.Outcome.ISOLATION_NOT_CONFIRMED ->
                "Switched-off circuit not confirmed. The signal is above room noise."
            Fusion.Outcome.ACOUSTIC_ONLY ->
                "Unusual sound, but no electrical fault confirmed."
            Fusion.Outcome.SOUND_NOT_FROM_CABLE ->
                "Spark-like sound, but no current in this cable. The sound is from something nearby."
            Fusion.Outcome.UNCLEAR ->
                "Unclear reading. Measure again."
            Fusion.Outcome.UNRELIABLE ->
                "Reading not reliable. Keep the phone still and measure again."
            Fusion.Outcome.NO_CURRENT_ISOLATED ->
                "No current seen. The phone senses current, not voltage. Use a voltage tester before touching."
            Fusion.Outcome.NO_ANOMALY -> when (line) {
                LineState.FLOWING -> "Current flowing. Normal for this wire. No sparking sound heard.$amps"
                LineState.NONE -> "No current seen. Normal for this wire."
                LineState.UNCLEAR -> "Normal for this wire, but the current signal is unclear."
            }
        }
        val name = circuitLabel?.trim()?.takeIf { it.isNotEmpty() }
        return if (name != null) "$name. $body" else body
    }
}
