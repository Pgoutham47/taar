package com.taar.domain

/**
 * Explainable sensor fusion: one diagnostic result from everything a reading
 * already produced.
 *
 * Sits on top of the existing layers and changes none of them. It reads the
 * metrics, the rules engine's warnings, the on-device model's score, the motion
 * check and this circuit's earlier readings, and says what they add up to --
 * including when they disagree.
 *
 * Deliberately a readable decision list rather than a learned model. Every result
 * names the signals behind it, so a technician can see why it was reached and
 * disagree with it on site.
 *
 * Three rules hold throughout:
 *  - It never claims a wire is safe or unsafe. Results say what was observed
 *    ("possible", "not confirmed", "no anomaly observed").
 *  - It never hides a critical warning from the rules. A reading can be marked
 *    unreliable, but the warning's guidance is still shown.
 *  - A poor measurement is reported as unreliable, not interpreted.
 */
object Fusion {

    /** How a signal bears on "something is abnormal here". */
    enum class Verdict { SUPPORTS, AGAINST, NEUTRAL, UNAVAILABLE }

    enum class Quality { GOOD, FAIR, POOR }

    enum class Strength { LOW, MODERATE, HIGH }

    enum class Tone { CRITICAL, WARNING, ADVISORY, NORMAL, UNRELIABLE }

    enum class Outcome(val title: String, val summary: String, val tone: Tone) {
        CURRENT_ON_ISOLATED(
            "Current detected on a circuit marked off",
            "You marked this supply as off, but the magnetometer sees current in the cable.",
            Tone.CRITICAL,
        ),
        ELECTRICAL_ANOMALY(
            "Possible electrical anomaly",
            "Current and sound both differ from this wire's normal. Further inspection recommended.",
            Tone.CRITICAL,
        ),
        POSSIBLE_ARCING(
            "Possible arcing / loose connection",
            "An arc-like sound pattern was observed while current was flowing. Further inspection recommended.",
            Tone.CRITICAL,
        ),
        NEAR_BREAKER_LIMIT(
            "Load near the breaker's limit",
            "The estimated current is close to or above this circuit's breaker rating.",
            Tone.CRITICAL,
        ),
        CURRENT_ABNORMAL(
            "Current higher than normal; no arc pattern detected",
            "Abnormal current behaviour compared with this wire's reference. No arc-like sound was observed.",
            Tone.WARNING,
        ),
        ISOLATION_NOT_CONFIRMED(
            "Switched-off circuit not confirmed",
            "You marked this supply as off, but the signal is above room noise.",
            Tone.WARNING,
        ),
        ACOUSTIC_ONLY(
            "Possible acoustic anomaly; electrical fault not confirmed",
            "The sound looked unusual, but the other signals did not confirm an electrical fault.",
            Tone.ADVISORY,
        ),
        SOUND_NOT_FROM_CABLE(
            "Arc-like sound, but no current in this cable",
            "An arc needs current, and none was detected here. The sound is likely from something nearby.",
            Tone.ADVISORY,
        ),
        UNCLEAR(
            "Unclear reading — measure again",
            "The current signal is between 'none' and 'flowing', so the reading is not interpreted.",
            Tone.UNRELIABLE,
        ),
        UNRELIABLE(
            "Unreliable reading",
            "The measurement did not pass the quality checks, so its result is not interpreted.",
            Tone.UNRELIABLE,
        ),
        NO_CURRENT_ISOLATED(
            "No current detected on the switched-off circuit",
            "Consistent with the supply being off. The phone senses current, not voltage.",
            Tone.NORMAL,
        ),
        NO_ANOMALY(
            "No anomaly observed",
            "This reading matches this wire's normal. This is an observation, not a safety certificate.",
            Tone.NORMAL,
        ),
    }

    data class Signal(val name: String, val value: String, val verdict: Verdict, val detail: String)

    data class Guidance(val text: String)

    data class Analysis(
        val outcome: Outcome,
        val strength: Strength,
        val quality: Quality,
        /** Every signal considered, in a fixed order, for the "Why?" screen. */
        val signals: List<Signal>,
        /** The short rows for the result card. */
        val summaryRows: List<Signal>,
        val why: List<String>,
        /** Signals that pointed the other way. Empty when everything agreed. */
        val conflicts: List<String>,
        val whatToDo: List<Guidance>,
        /** How [strength] was reached, in one sentence. */
        val strengthReason: String,
    )

    data class Input(
        val metrics: Metrics,
        val thresholds: Thresholds,
        val faults: List<RankedFault>,
        /** Null when the model is unavailable or could not score the capture. */
        val aiArcProbability: Float?,
        val aiThreshold: Float,
        /** Null on a phone without a gyroscope. */
        val motionRadPerS: Double?,
        val audioCaptured: Boolean,
        val unprocessedAudio: Boolean,
        val breakerRatingA: Double?,
        /** Earlier readings on this circuit, newest first. */
        val history: List<Metrics>,
    )

    const val CAVEAT = "Taar reports what the phone's sensors observed. It does not prove a wire is " +
        "safe or unsafe; where there is doubt, have it inspected by a qualified electrician."

    private enum class Deviation { HIGH, ELEVATED, NORMAL, LOWER, UNAVAILABLE }

    fun analyse(input: Input): Analysis {
        val m = input.metrics
        val t = input.thresholds
        val ids = input.faults.map { it.fault.id }.toSet()

        // ---- the signals ----

        val line = lineSignal(m)
        val deviation = deviationOf(m, t)
        val deviationSignal = deviationSignal(m, t, deviation)
        val breaker = breakerSignal(m, input.breakerRatingA)
        val arcRule = input.audioCaptured && m.arcZ >= t.warningZ
        val arcRuleSignal = if (!input.audioCaptured)
            Signal("Arc signal (rule)", "Not available", Verdict.UNAVAILABLE, "No sound was captured.")
        else Signal(
            "Arc signal (rule)",
            if (arcRule) "DETECTED" else "Not detected",
            if (arcRule) Verdict.SUPPORTS else Verdict.AGAINST,
            "The 100 Hz sparking pattern is %+.1f steps from this wire's normal sound (warns at %.1f)."
                .format(m.arcZ, t.warningZ),
        )
        val ai = input.aiArcProbability?.let { it >= input.aiThreshold }
        val aiSignal = when (val p = input.aiArcProbability) {
            null -> Signal("On-device AI", "Not available", Verdict.UNAVAILABLE,
                "The model did not score this capture.")
            else -> Signal(
                "On-device AI",
                "${if (ai == true) "Arc-like" else "Not arc-like"} ${(p * 100).toInt()}%",
                if (ai == true) Verdict.SUPPORTS else Verdict.AGAINST,
                if (ai == true) "The model heard noise pulsing 100 times a second, the pattern an arc makes."
                else "The model did not hear the pulsing pattern an arc makes.",
            )
        }
        val history = historySignal(input.history, t, arcRule || deviation == Deviation.ELEVATED ||
            deviation == Deviation.HIGH)
        val (quality, qualityReasons) = qualityOf(m, input)
        val qualitySignal = Signal(
            // Not evidence for or against an anomaly: it decides how much the rest counts.
            "Measurement quality", quality.name, Verdict.NEUTRAL,
            qualityReasons.joinToString(" "),
        )
        val supply = Signal(
            "Supply (your input)",
            if (m.supplyIsolated) "Marked OFF" else "Marked ON",
            Verdict.NEUTRAL,
            if (m.supplyIsolated) "You said this circuit's supply is switched off."
            else "You said the circuit is in normal use.",
        )

        val acousticAny = arcRule || ai == true
        val arcCombined = Signal(
            "Arc signal",
            when {
                arcRule && ai == true -> "DETECTED"
                acousticAny -> "POSSIBLE"
                !input.audioCaptured -> "Not available"
                else -> "Not detected"
            },
            when {
                acousticAny -> Verdict.SUPPORTS
                !input.audioCaptured -> Verdict.UNAVAILABLE
                else -> Verdict.AGAINST
            },
            when {
                arcRule && ai == true -> "Both the rule and the on-device AI observed an arc-like pattern."
                arcRule -> "The rule observed an arc-like pattern; the AI did not confirm it."
                ai == true -> "The AI heard an arc-like pattern; the rule did not confirm it."
                else -> "Neither the rule nor the AI observed an arc-like pattern."
            },
        )
        val electrical = deviation == Deviation.HIGH || deviation == Deviation.ELEVATED
        val baselineAbnormal = electrical || arcRule
        val baselineSignal = Signal(
            "Baseline comparison",
            if (baselineAbnormal) "ABNORMAL" else "NORMAL",
            if (baselineAbnormal) Verdict.SUPPORTS else Verdict.AGAINST,
            if (baselineAbnormal) "At least one measurement is outside this wire's recorded normal."
            else "Current and sound are within this wire's recorded normal.",
        )

        val signals = listOf(line, deviationSignal, breaker, arcRuleSignal, aiSignal, history, qualitySignal, supply)
        val summaryRows = listOf(line, deviationSignal.copy(name = "Current deviation"), arcCombined,
            baselineSignal, qualitySignal)

        // ---- the decision, most safety-relevant first ----

        val live = m.lineState == LineState.FLOWING
        val outcome = when {
            // Never downgraded: current on a circuit someone is about to work on.
            "unexpectedly_live" in ids -> Outcome.CURRENT_ON_ISOLATED
            quality == Quality.POOR -> Outcome.UNRELIABLE
            "isolation_unclear" in ids -> Outcome.ISOLATION_NOT_CONFIRMED
            m.supplyIsolated && m.lineState == LineState.NONE -> Outcome.NO_CURRENT_ISOLATED
            live && arcRule && electrical -> Outcome.ELECTRICAL_ANOMALY
            "arcing" in ids -> Outcome.POSSIBLE_ARCING
            "overload" in ids -> Outcome.NEAR_BREAKER_LIMIT
            electrical -> Outcome.CURRENT_ABNORMAL
            acousticAny && m.lineState == LineState.NONE -> Outcome.SOUND_NOT_FROM_CABLE
            acousticAny && live -> Outcome.ACOUSTIC_ONLY
            m.lineState == LineState.UNCLEAR -> Outcome.UNCLEAR
            else -> Outcome.NO_ANOMALY
        }

        // ---- which signals bear on that outcome, and how strongly they agree ----

        val relevant: List<Signal> = when (outcome) {
            Outcome.CURRENT_ON_ISOLATED -> listOf(line)
            Outcome.ELECTRICAL_ANOMALY -> listOf(deviationSignal, breaker, arcRuleSignal, aiSignal, history)
            Outcome.POSSIBLE_ARCING -> listOf(arcRuleSignal, aiSignal, history)
            Outcome.NEAR_BREAKER_LIMIT -> listOf(breaker, deviationSignal, history)
            Outcome.CURRENT_ABNORMAL -> listOf(deviationSignal, breaker, history)
            Outcome.ACOUSTIC_ONLY, Outcome.SOUND_NOT_FROM_CABLE -> listOf(aiSignal, arcRuleSignal, history)
            Outcome.ISOLATION_NOT_CONFIRMED, Outcome.UNCLEAR, Outcome.UNRELIABLE -> emptyList()
            Outcome.NO_CURRENT_ISOLATED -> listOf(line)
            Outcome.NO_ANOMALY -> listOf(deviationSignal, arcRuleSignal, aiSignal, history)
        }
        val (strength, strengthReason) = strengthOf(outcome, relevant, quality)

        val why = buildList {
            when (outcome) {
                Outcome.CURRENT_ON_ISOLATED -> {
                    add("You marked this supply as off.")
                    add("The magnetic signal shows current flowing (${line.value}).")
                }
                Outcome.UNRELIABLE -> addAll(qualityReasons)
                Outcome.ISOLATION_NOT_CONFIRMED -> {
                    add("You marked this supply as off.")
                    add("The current signal is above room noise but not clearly current (${line.value}).")
                }
                Outcome.UNCLEAR -> add("The current signal is between the no-current and flowing thresholds (${line.value}).")
                Outcome.NO_CURRENT_ISOLATED -> add("No current signal above room noise (${line.value}).")
                Outcome.SOUND_NOT_FROM_CABLE -> {
                    add("An arc-like sound was observed.")
                    add("No current is flowing in this cable (${line.value}), and an arc needs current.")
                }
                Outcome.NO_ANOMALY -> relevant.filter { it.verdict == Verdict.AGAINST }.forEach { add(it.detail) }
                else -> relevant.filter { it.verdict == Verdict.SUPPORTS }.forEach { add(it.detail) }
            }
            if (quality == Quality.GOOD && outcome != Outcome.UNRELIABLE) {
                add("Measurement quality passed the required checks.")
            }
        }

        val conflicts = buildList {
            val against = if (outcome == Outcome.NO_ANOMALY) Verdict.SUPPORTS else Verdict.AGAINST
            if (outcome != Outcome.UNRELIABLE) {
                relevant.filter { it.verdict == against }.forEach { add("${it.name}: ${it.detail}") }
            }
            if (quality == Quality.FAIR) add("Measurement quality is fair: ${qualityReasons.joinToString(" ")}")
            // A critical warning is never silently dropped by a poor reading.
            if (outcome == Outcome.UNRELIABLE) {
                input.faults.filter { it.fault.severity == Status.CRITICAL }.forEach {
                    add("The rules also raised \"${it.fault.label}\". Do not dismiss it: repeat the measurement.")
                }
            }
        }

        return Analysis(
            outcome = outcome,
            strength = strength,
            quality = quality,
            signals = signals,
            summaryRows = summaryRows,
            why = why,
            conflicts = conflicts,
            whatToDo = guidance(outcome, input.faults),
            strengthReason = strengthReason,
        )
    }

    // ---- signals ----

    private fun lineSignal(m: Metrics): Signal {
        val contrast = "%.0f×".format(LineState.contrastOf(m.lineConfidence))
        val amps = m.impliedCurrentA?.let { " · ≈%.1f A".format(it) } ?: ""
        return when (m.lineState) {
            LineState.FLOWING -> Signal("Current in cable", "Flowing ($contrast)$amps",
                if (m.supplyIsolated) Verdict.SUPPORTS else Verdict.NEUTRAL,
                "The 50 Hz magnetic signal is $contrast room noise (flowing above 13.5×).")
            LineState.UNCLEAR -> Signal("Current in cable", "Unclear ($contrast)", Verdict.NEUTRAL,
                "The 50 Hz signal is $contrast room noise, between 8× and 13.5×.")
            LineState.NONE -> Signal("Current in cable", "Not detected ($contrast)",
                if (m.supplyIsolated) Verdict.AGAINST else Verdict.NEUTRAL,
                "The 50 Hz signal is $contrast room noise (none below 8×).")
        }
    }

    private fun deviationOf(m: Metrics, t: Thresholds): Deviation = when {
        !m.fieldUsable -> Deviation.UNAVAILABLE
        (m.loadVsRating ?: 0.0) >= OVERLOAD_FRACTION -> Deviation.HIGH
        m.currentNowA == null && m.loadZ >= t.criticalZ -> Deviation.HIGH
        m.loadAboveReference(t) -> Deviation.ELEVATED
        m.loadZ <= -t.warningZ -> Deviation.LOWER
        else -> Deviation.NORMAL
    }

    private fun deviationSignal(m: Metrics, t: Thresholds, d: Deviation): Signal {
        val now = m.currentNowA
        val ref = m.referenceCurrentA
        val detail = if (now != null && ref != null)
            "%.1f A now, %.1f A when the reference was recorded.".format(now, ref)
        else "Magnetic field is %+.1f steps from this wire's normal (warns at %.1f).".format(m.loadZ, t.warningZ)
        return when (d) {
            Deviation.UNAVAILABLE -> Signal("Current vs normal", "Not measurable", Verdict.UNAVAILABLE,
                "The field strength could not be measured reliably this time.")
            Deviation.HIGH -> Signal("Current vs normal", "HIGH", Verdict.SUPPORTS, detail)
            Deviation.ELEVATED -> Signal("Current vs normal", "ELEVATED", Verdict.SUPPORTS, detail)
            // Lower is not abnormal: switching a load off looks exactly like this.
            Deviation.LOWER -> Signal("Current vs normal", "LOWER", Verdict.NEUTRAL,
                "$detail Lower than the reference, as when something is switched off.")
            Deviation.NORMAL -> Signal("Current vs normal", "NORMAL", Verdict.AGAINST, detail)
        }
    }

    private fun breakerSignal(m: Metrics, ratingA: Double?): Signal = when {
        ratingA == null -> Signal("Breaker load", "Not available", Verdict.UNAVAILABLE,
            "No breaker rating set for this circuit.")
        m.loadVsRating == null -> Signal("Breaker load", "Not available", Verdict.UNAVAILABLE,
            "Needs amps calibration and flowing current to compare with the %.0f A breaker.".format(ratingA))
        else -> {
            val over = m.loadVsRating >= OVERLOAD_FRACTION
            Signal("Breaker load", "%.0f%% of %.0f A".format(m.loadVsRating * 100, ratingA),
                if (over) Verdict.SUPPORTS else Verdict.AGAINST,
                if (over) "Estimated current is at or above 90% of the breaker rating."
                else "Estimated current is within the breaker rating.")
        }
    }

    private fun historySignal(history: List<Metrics>, t: Thresholds, anomalyNow: Boolean): Signal {
        if (history.isEmpty()) return Signal("History", "No earlier readings", Verdict.UNAVAILABLE,
            "This is the first reading on this circuit.")
        val recent = history.take(HISTORY_WINDOW)
        val repeats = recent.count { it.arcZ >= t.warningZ || (it.fieldUsable && it.loadAboveReference(t)) }
        return when {
            anomalyNow && repeats > 0 -> Signal("History", "Repeated ($repeats of ${recent.size})",
                Verdict.SUPPORTS, "A similar deviation was seen in $repeats of the last ${recent.size} readings.")
            anomalyNow -> Signal("History", "First time", Verdict.NEUTRAL,
                "None of the last ${recent.size} readings showed a deviation.")
            repeats > 0 -> Signal("History", "Earlier deviations ($repeats of ${recent.size})", Verdict.NEUTRAL,
                "$repeats of the last ${recent.size} readings deviated; this one did not.")
            else -> Signal("History", "Consistent", Verdict.AGAINST,
                "None of the last ${recent.size} readings showed a deviation.")
        }
    }

    private fun qualityOf(m: Metrics, input: Input): Pair<Quality, List<String>> {
        val reasons = mutableListOf<String>()
        var quality = Quality.GOOD
        when (input.motionRadPerS?.let(MotionCheck::level)) {
            MotionCheck.Level.MOVED -> {
                quality = Quality.POOR
                reasons += "The phone moved during the capture."
            }
            MotionCheck.Level.STEADY_HAND -> reasons += "Held by hand, steady enough."
            MotionCheck.Level.STILL -> reasons += "Phone held still."
            null -> reasons += "Movement not checked (no gyroscope)."
        }
        fun fair(reason: String) {
            if (quality == Quality.GOOD) quality = Quality.FAIR
            reasons += reason
        }
        if (!m.fieldUsable) fair("The field strength could not be measured reliably.")
        if (m.lineState == LineState.UNCLEAR) fair("The current signal was unclear.")
        if (!input.audioCaptured) fair("No sound was captured, so arc checks are unavailable.")
        else if (!input.unprocessedAudio) fair("The microphone ran in processed mode, which can weaken arc checks.")
        return quality to reasons
    }

    // ---- strength ----

    private fun strengthOf(outcome: Outcome, relevant: List<Signal>, quality: Quality): Pair<Strength, String> {
        if (quality == Quality.POOR || outcome == Outcome.UNRELIABLE || outcome == Outcome.UNCLEAR ||
            outcome == Outcome.ISOLATION_NOT_CONFIRMED
        ) return Strength.LOW to "The reading is not clear enough to support a conclusion."

        val agreeing = if (outcome.tone == Tone.NORMAL) Verdict.AGAINST else Verdict.SUPPORTS
        val disagreeing = if (outcome.tone == Tone.NORMAL) Verdict.SUPPORTS else Verdict.AGAINST
        val agree = relevant.count { it.verdict == agreeing }
        val disagree = relevant.count { it.verdict == disagreeing }
        // A single, direct observation of current needs no corroboration to be strong.
        val score = if (outcome == Outcome.CURRENT_ON_ISOLATED || outcome == Outcome.NO_CURRENT_ISOLATED) 3
        else agree - disagree
        var strength = when {
            score >= 3 -> Strength.HIGH
            score == 2 -> Strength.MODERATE
            else -> Strength.LOW
        }
        if (quality == Quality.FAIR && strength != Strength.LOW) strength = Strength.values()[strength.ordinal - 1]
        val reason = if (outcome == Outcome.CURRENT_ON_ISOLATED || outcome == Outcome.NO_CURRENT_ISOLATED)
            "Based on a direct measurement of the current signal."
        else "$agree signal${if (agree == 1) "" else "s"} agree, $disagree disagree" +
            (if (quality == Quality.FAIR) ", and measurement quality is only fair." else ".")
        return strength to reason
    }

    // ---- what to do ----

    private fun guidance(outcome: Outcome, faults: List<RankedFault>): List<Guidance> = buildList {
        // The existing guidance first, in the rules engine's order.
        faults.filter { it.fault.severity != Status.UNKNOWN }
            .forEach { add(Guidance(it.fault.action)) }
        when (outcome) {
            Outcome.ELECTRICAL_ANOMALY, Outcome.POSSIBLE_ARCING -> {
                add(Guidance("Inspect the connections on this circuit, or have a qualified electrician do it."))
                add(Guidance("Compare with this circuit's earlier readings in History."))
            }
            Outcome.CURRENT_ABNORMAL, Outcome.NEAR_BREAKER_LIMIT ->
                add(Guidance("Compare with this circuit's earlier readings in History."))
            Outcome.ACOUSTIC_ONLY -> {
                add(Guidance("Measure again with the phone pressed still against the cable."))
                add(Guidance("If the sound pattern repeats, have the connections on this circuit checked."))
            }
            Outcome.SOUND_NOT_FROM_CABLE ->
                add(Guidance("Look for the source nearby: another cable, an appliance or a speaker."))
            Outcome.UNRELIABLE -> {
                add(Guidance("Press the phone flat against the cable, hold it still for 3 seconds, and measure again."))
                add(Guidance("If this keeps happening, redo the phone check."))
            }
            Outcome.UNCLEAR ->
                add(Guidance("Keep the phone still on the cable and measure again."))
            Outcome.NO_CURRENT_ISOLATED, Outcome.CURRENT_ON_ISOLATED, Outcome.ISOLATION_NOT_CONFIRMED ->
                add(Guidance("Confirm with a voltage tester before touching. The phone senses current, not voltage."))
            Outcome.NO_ANOMALY ->
                add(Guidance("No action suggested by this reading. Measure again if something changes."))
        }
    }

    private const val OVERLOAD_FRACTION = 0.9
    private const val HISTORY_WINDOW = 5
}
