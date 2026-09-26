package com.taar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taar.domain.Circuit
import com.taar.domain.LineState
import com.taar.domain.Metrics
import com.taar.domain.MotionCheck
import com.taar.domain.RankedFault
import com.taar.domain.Reading
import com.taar.domain.Status
import com.taar.ml.ArcModel
import kotlin.math.roundToInt

/**
 * Measuring, in three stages on one screen: set up, capture, result.
 *
 * The result leads with one sentence -- current flowing, no current, or unclear --
 * because that is the question the person is asking. The numbers behind it are one
 * tap away rather than first, and every number carries a line saying what it means.
 */
@Composable
fun MeasureScreen(
    state: TaarViewModel.UiState,
    circuit: Circuit?,
    onSupplyIsolated: (Boolean) -> Unit,
    onMeasure: () -> Unit,
    onLabel: (Status) -> Unit,
    onAsk: (String) -> Unit,
    onImportModel: (android.net.Uri) -> Unit,
    onBack: () -> Unit,
) {
    var started by rememberSaveable { mutableStateOf(false) }
    var showWhy by rememberSaveable { mutableStateOf(false) }
    val capturing = state.capture?.kind == TaarViewModel.CaptureKind.MEASURE
    val reading = state.lastReading
    val measure = { showWhy = false; onMeasure() }
    val why = state.fusion?.takeIf { showWhy && reading != null && !capturing }

    TaarScreen(
        title = if (why != null) "Why this result" else "Measure",
        subtitle = circuit?.let { "${state.installation?.name} › ${it.label}" },
        onBack = when {
            capturing -> null
            why != null -> { { showWhy = false } }
            else -> onBack
        },
    ) {
        if (circuit == null || circuit.baseline?.isSufficient != true) {
            ErrorCard("Choose a circuit and record its reference first. Go back to Home.")
            return@TaarScreen
        }

        when {
            !started -> SetupStage(
                supplyIsolated = state.supplyIsolated,
                onSupplyIsolated = onSupplyIsolated,
                onStart = { started = true; measure() },
            )

            // Also covers the moment between tapping and the capture starting, so
            // the previous result never flashes up.
            capturing || (reading == null && state.error == null) -> CapturingPanel(
                title = "Measuring",
                step = 1,
                total = 1,
                what = "Listening for the 50 Hz hum of current in the cable.",
            )

            reading == null -> {
                state.error?.let { ErrorCard(it) }
                Button(onClick = measure, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Try again", style = MaterialTheme.typography.titleMedium)
                }
            }

            why != null -> WhyStage(why, state.lastFaults, onBack = { showWhy = false })

            else -> ResultStage(
                state = state,
                reading = reading,
                circuit = circuit,
                onWhy = { showWhy = true },
                onAsk = onAsk,
                onImportModel = onImportModel,
                onMeasureAgain = measure,
                onChangeSetup = { started = false },
                onLabel = onLabel,
                onDone = onBack,
            )
        }
    }
}

@Composable
private fun SetupStage(
    supplyIsolated: Boolean,
    onSupplyIsolated: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    SectionLabel("Step 1 · Place the phone")
    TaarCard {
        Text("Flat on the cable, in the same spot as the reference.", style = MaterialTheme.typography.bodyLarge)
        Hint("Keep it still for 3 seconds. Keep chargers and other live cables away.")
    }

    SectionLabel("Step 2 · Is this circuit's supply on?")
    SupplyOption(
        selected = !supplyIsolated,
        title = "On · normal use",
        detail = "Taar tells you whether current is flowing and flags anything unusual.",
        onClick = { onSupplyIsolated(false) },
    )
    SupplyOption(
        selected = supplyIsolated,
        title = "Off · I switched the breaker off",
        detail = "Taar warns you if current is still flowing, for example a mislabelled breaker.",
        onClick = { onSupplyIsolated(true) },
    )
    Hint("The phone senses current, not voltage, so it needs you to say.")

    PrimaryButton("Start measuring · 3 seconds", onClick = onStart)
}

/** A selectable option card: the chosen one is outlined in the brand colour. */
@Composable
private fun SupplyOption(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) TaarPalette.Yellow.copy(alpha = 0.08f) else TaarPalette.Surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) TaarPalette.Yellow else TaarPalette.Outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.padding(start = 4.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Hint(detail)
            }
        }
    }
}

@Composable
private fun ResultStage(
    state: TaarViewModel.UiState,
    reading: Reading,
    circuit: Circuit,
    onWhy: () -> Unit,
    onAsk: (String) -> Unit,
    onImportModel: (android.net.Uri) -> Unit,
    onMeasureAgain: () -> Unit,
    onChangeSetup: () -> Unit,
    onLabel: (Status) -> Unit,
    onDone: () -> Unit,
) {
    // One result first: what every signal adds up to. Without a fusion result
    // (no metrics) the individual results are all there is, so they open.
    state.fusion?.let {
        FusionCard(it, onWhy)
        AssistantPanel(state.assistant, onAsk, onImportModel)
    }

    PrimaryButton("Measure again", onClick = onMeasureAgain)
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        SecondaryButton("Change setup", onClick = onChangeSetup, modifier = Modifier.weight(1f))
        SecondaryButton("Done", onClick = onDone, modifier = Modifier.weight(1f))
    }
    Hint(
        "Supply: " + (if (reading.supplyIsolated) "OFF (you switched it off)" else "ON (normal use)") +
            " · saved to History at ${time(reading.epochMillis)}",
    )

    IndividualResults(state, reading, circuit, startOpen = state.fusion == null)
    Details(state, reading, circuit)
    Teach(state.lastLabel, onLabel)

    Hint(
        "Taar is a triage aid. It does not replace a licensed electrician, a calibrated clamp " +
            "meter or a statutory inspection.",
    )
}

/**
 * The per-signal results the fusion card is built from, unchanged, for anyone who
 * wants each one on its own.
 */
@Composable
private fun IndividualResults(state: TaarViewModel.UiState, reading: Reading, circuit: Circuit, startOpen: Boolean) {
    var open by rememberSaveable { mutableStateOf(startOpen) }
    TextButton(onClick = { open = !open }) {
        Text(if (open) "Hide individual results ▴" else "Show individual results ▾")
    }
    if (!open) return

    val line = LineState.of(reading.lineConfidence)
    val contrast = LineState.contrastOf(reading.lineConfidence)
    val problems = state.lastFaults.filter { it.fault.severity != Status.UNKNOWN }
    val unusable = state.lastFaults.any { it.fault.id == "reading_unreliable" }

    for (r in problems) ProblemCard(r)

    Headline(line, reading.supplyIsolated, state.lastImpliedCurrentA, calibrated = circuit.utPerAmp != null)

    AiSoundCard(
        probability = state.aiArcProbability,
        ruleFlaggedArc = state.lastFaults.any { it.fault.id == "arcing" },
        line = line,
        modelLoaded = state.aiSelfCheck != null,
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Signal", style = MaterialTheme.typography.titleSmall)
                Text("%.0f×".format(contrast), style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace)
            }
            SignalMeter(contrast)
        }
    }

    if (unusable) {
        Hint(
            "The field strength couldn't be measured reliably this time, so load and amps are " +
                "left out. The current/no-current answer above is still valid. If this keeps " +
                "happening, redo the phone check.",
            color = TaarPalette.Amber,
        )
    }
    if (problems.isEmpty()) {
        Hint("✓ No rule warnings in this reading.", color = TaarPalette.Green)
    }
}

@Composable
private fun Headline(line: LineState, isolated: Boolean, amps: Double?, calibrated: Boolean) {
    val (title, body, colour) = when (line) {
        LineState.FLOWING -> Triple(
            "Current is flowing",
            if (isolated) "You said this circuit is switched off. See the warning above."
            else "The cable is carrying current. Normal when something on this circuit is switched on.",
            if (isolated) TaarPalette.Red else TaarPalette.Yellow,
        )
        LineState.NONE -> Triple(
            "No current flowing",
            if (isolated) "A good sign, but not proof. The phone senses current, not voltage: a " +
                "switched-off circuit can still be live. Test with a voltage tester before touching."
            else "Nothing on this cable is drawing power right now, or its supply is off.",
            TaarPalette.Blue,
        )
        LineState.UNCLEAR -> Triple(
            "Unclear — measure again",
            "The signal is above room noise but not clearly current. Keep the phone still and " +
                "measure again. If it stays unclear, the load may be very small or the phone " +
                "too far from the wire.",
            TaarPalette.Amber,
        )
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = colour,
                fontWeight = FontWeight.SemiBold)
            if (line == LineState.FLOWING) {
                if (amps != null) {
                    Text("≈ %.1f A".format(amps), style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold)
                } else if (!calibrated) {
                    Hint("Calibrate amps from Home to see how much.")
                }
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The on-device model's second opinion on the sound.
 *
 * Never replaces the rule. Where the two disagree the text leans to safety: a rule
 * warning the model does not hear stays a warning until a repeat clears it.
 */
@Composable
private fun AiSoundCard(probability: Float?, ruleFlaggedArc: Boolean, line: LineState, modelLoaded: Boolean) {
    if (probability == null) {
        Hint(
            if (modelLoaded) "On-device AI could not score this capture's sound."
            else "On-device AI is not available on this phone. The sparking check uses the rule alone.",
        )
        return
    }
    val arcLike = probability >= ArcModel.THRESHOLD
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("On-device AI · sound", style = MaterialTheme.typography.titleSmall)
                Text("arc-like ${(probability * 100).roundToInt()}%", style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace)
            }
            Text(
                if (arcLike) "Sounds like sparking" else "No sparking sound",
                style = MaterialTheme.typography.titleMedium,
                color = if (arcLike) TaarPalette.Amber else TaarPalette.Green,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (arcLike) "The model hears noise pulsing 100 times a second, the pattern an arc makes."
                else "The model does not hear the 100-times-a-second pulsing an arc makes.",
                style = MaterialTheme.typography.bodyMedium,
            )
            when {
                ruleFlaggedArc && !arcLike -> Hint(
                    "The sparking warning above comes from the rule. The AI does not hear it, but " +
                        "treat the warning as real until a repeat measurement clears it.",
                    color = TaarPalette.Amber,
                )
                // The model hears sound; only the magnetometer knows whether this cable
                // carries current, and an arc cannot exist without it.
                arcLike && line == LineState.NONE -> Hint(
                    "No current is flowing in this cable, and an arc needs current. The sound is " +
                        "coming from something nearby, not from this cable.",
                    color = TaarPalette.Blue,
                )
                arcLike && line == LineState.UNCLEAR -> Hint(
                    "It is unclear whether current is flowing, and an arc needs current. Keep the " +
                        "phone still on the cable and measure again.",
                    color = TaarPalette.Amber,
                )
                !ruleFlaggedArc && arcLike -> Hint(
                    "Current is flowing and the AI hears sparking, but it has not risen enough above " +
                        "this cable's reference for the rule to warn. Measure again; if it repeats, " +
                        "have the connections on this circuit checked.",
                    color = TaarPalette.Amber,
                )
            }
            Hint("A second opinion from a model running on this phone. The result above is what to act on.")
        }
    }
}

@Composable
private fun ProblemCard(r: RankedFault) {
    val critical = r.fault.severity == Status.CRITICAL
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (critical) TaarPalette.RedSurface else TaarPalette.AmberSurface,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (critical) "STOP · " else "WARNING · ") + r.fault.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (critical) TaarPalette.Red else TaarPalette.Amber,
                fontWeight = FontWeight.Bold,
            )
            Text(r.fault.action, style = MaterialTheme.typography.bodyMedium)
            Text("Why:", style = MaterialTheme.typography.labelMedium, color = TaarPalette.Grey)
            for (e in r.evidence) {
                Text("${if (e.holds) "✓" else "✕"} ${e.description}",
                    style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey)
            }
        }
    }
}

@Composable
private fun Details(state: TaarViewModel.UiState, reading: Reading, circuit: Circuit) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide details ▴" else "Show details ▾") }
    if (!open) return

    val metrics = Metrics.derive(reading, circuit)
    val t = state.thresholds
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric(
                "Signal strength", "%.1f×".format(LineState.contrastOf(reading.lineConfidence)),
                "How much the 50 Hz hum stands out from nearby frequencies.",
            )
            Metric(
                "Magnetic field at 50 Hz",
                if (reading.fieldEstimateUsable) "%.2f µT".format(reading.fieldAmplitudeUt) else "not usable",
                "Strength of the field from current in the cable.",
            )
            Metric(
                "Current",
                when {
                    state.lastImpliedCurrentA != null -> "%.2f A".format(state.lastImpliedCurrentA)
                    circuit.utPerAmp == null -> "not calibrated"
                    else -> "—"
                },
                "Only shown when calibrated and current is clearly flowing.",
            )
            metrics?.let { m ->
                HorizontalDivider()
                Hint(
                    "Compared with this cable's reference, in steps of its normal variation. " +
                        "Warns at %.1f, critical at %.1f%s."
                            .format(t.warningZ, t.criticalZ, if (t.isProvisional) " (defaults until you teach Taar)" else ""),
                )
                m.referenceCurrentA?.let { ref ->
                    Metric(
                        "Current now / at reference",
                        "%.1f A / %.1f A".format(m.currentNowA ?: 0.0, ref),
                        "Warns when current is at least %.0f A and 1.5 times higher than at the reference."
                            .format(Metrics.MIN_CURRENT_RISE_A),
                    )
                } ?: Metric("Load vs reference", "%+.1f".format(m.loadZ))
                Metric("Sparking signal vs reference", "%+.1f".format(m.arcZ))
                Metric("Sparking signal (raw)", "%.4f".format(reading.arcModulationIndex))
            }
            state.spectrogram?.let {
                HorizontalDivider()
                Text("Sound envelope", style = MaterialTheme.typography.titleSmall)
                Hint("From the microphone. Sparking shows up as a bright line near 100 Hz.")
                SpectrogramView(it)
            }
            state.lastMotion?.let { rad ->
                HorizontalDivider()
                Metric(
                    "Phone movement", "%.3f rad/s · %s".format(rad, motionWord(MotionCheck.level(rad))),
                    "How fast the phone turned during the 3 seconds, from the gyroscope. Above " +
                        "%.2f the reading is marked as moved.".format(MotionCheck.MOVED_ABOVE),
                )
            }
            state.aiSelfCheck?.let { c ->
                HorizontalDivider()
                Metric(
                    "On-device AI model",
                    if (c.passed) "self-check passed" else "self-check FAILED",
                    "TFLite, 16.6 KB, runs on this phone with no internet. At start-up it re-scored " +
                        "${c.rows} reference inputs from training; largest difference %.1e."
                            .format(c.worstDifference),
                )
            }
            state.prediction?.let {
                HorizontalDivider()
                Text("Looks like readings you marked '${it.label.lowercase()}'",
                    style = MaterialTheme.typography.titleSmall)
                Hint("Learned from your own answers below. A hint only; the result above is what to act on.")
                Text("distance %.2f · margin %.2f".format(it.distance, it.margin),
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    color = TaarPalette.Grey)
            }
        }
    }
}

@Composable
private fun Teach(label: Status?, onLabel: (Status) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text(if (open) "Teach Taar (optional) ▴" else "Teach Taar (optional) ▾")
    }
    if (!open) return

    OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, TaarPalette.Grey.copy(alpha = 0.4f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Was this circuit really OK?", style = MaterialTheme.typography.titleSmall)
            Hint(
                "Only answer if you know from another check, such as a clamp meter or an " +
                    "electrician. Your answers tune the warning levels for this circuit.",
            )
            if (label != null) {
                Text("✓ Saved as ${labelName(label)}", color = TaarPalette.Green,
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelButton("Normal", TaarPalette.Green) { onLabel(Status.HEALTHY) }
                    LabelButton("Warning", TaarPalette.Amber) { onLabel(Status.WARNING) }
                    LabelButton("Fault", TaarPalette.Red) { onLabel(Status.CRITICAL) }
                }
            }
        }
    }
}

@Composable
private fun LabelButton(text: String, colour: Color, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, colour),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colour),
    ) { Text(text) }
}

fun labelName(s: Status) = when (s) {
    Status.HEALTHY -> "Normal"
    Status.WARNING -> "Warning"
    Status.CRITICAL -> "Fault"
    Status.UNKNOWN -> "Unknown"
}

fun motionWord(level: MotionCheck.Level) = when (level) {
    MotionCheck.Level.STILL -> "still"
    MotionCheck.Level.STEADY_HAND -> "steady hand"
    MotionCheck.Level.MOVED -> "moved"
}
