package com.taar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.taar.domain.RankedFault
import com.taar.domain.Reading
import com.taar.domain.Status

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
    onBack: () -> Unit,
) {
    var started by rememberSaveable { mutableStateOf(false) }
    val capturing = state.capture?.kind == TaarViewModel.CaptureKind.MEASURE
    val reading = state.lastReading

    TaarScreen(
        title = "Measure",
        subtitle = circuit?.let { "${state.installation?.name} › ${it.label}" },
        onBack = if (capturing) null else onBack,
    ) {
        if (circuit == null || circuit.baseline?.isSufficient != true) {
            ErrorCard("Choose a circuit and record its reference first. Go back to Home.")
            return@TaarScreen
        }

        when {
            !started -> SetupStage(
                supplyIsolated = state.supplyIsolated,
                onSupplyIsolated = onSupplyIsolated,
                onStart = { started = true; onMeasure() },
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
                Button(onClick = onMeasure, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Try again", style = MaterialTheme.typography.titleMedium)
                }
            }

            else -> ResultStage(
                state = state,
                reading = reading,
                circuit = circuit,
                onMeasureAgain = onMeasure,
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("1 · Place the phone", style = MaterialTheme.typography.titleSmall)
            Hint(
                "Flat on the cable, in the same spot as the reference. Don't hold it.",
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("2 · Is this circuit's supply on?", style = MaterialTheme.typography.titleSmall)
            Hint("The phone can't see voltage, only current, so it needs you to say.")
            SupplyOption(
                selected = !supplyIsolated,
                title = "ON — normal use",
                detail = "Taar tells you whether current is flowing and flags anything unusual.",
                onClick = { onSupplyIsolated(false) },
            )
            SupplyOption(
                selected = supplyIsolated,
                title = "OFF — I switched the breaker off",
                detail = "Taar warns you if current is still flowing, e.g. a mislabelled breaker.",
                onClick = { onSupplyIsolated(true) },
            )
        }
    }

    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Start measuring · 3 seconds", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SupplyOption(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(detail)
        }
    }
}

@Composable
private fun ResultStage(
    state: TaarViewModel.UiState,
    reading: Reading,
    circuit: Circuit,
    onMeasureAgain: () -> Unit,
    onChangeSetup: () -> Unit,
    onLabel: (Status) -> Unit,
    onDone: () -> Unit,
) {
    val line = LineState.of(reading.lineConfidence)
    val contrast = LineState.contrastOf(reading.lineConfidence)
    val problems = state.lastFaults.filter { it.fault.severity != Status.UNKNOWN }
    val unusable = state.lastFaults.any { it.fault.id == "reading_unreliable" }

    // Problems first: if something is dangerous it must be the first thing read.
    for (r in problems) ProblemCard(r)

    Headline(line, reading.supplyIsolated, state.lastImpliedCurrentA, calibrated = circuit.utPerAmp != null)

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
        Hint("✓ No problems found in this reading.", color = TaarPalette.Green)
    }

    Button(onClick = onMeasureAgain, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Measure again", style = MaterialTheme.typography.titleMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onChangeSetup, modifier = Modifier.weight(1f)) { Text("Change setup") }
        OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
    }
    Hint(
        "Supply: " + (if (reading.supplyIsolated) "OFF (you switched it off)" else "ON (normal use)") +
            " · saved to History at ${time(reading.epochMillis)}",
    )

    Details(state, reading, circuit)
    Teach(state.lastLabel, onLabel)

    Hint(
        "Taar is a triage aid. It does not replace a licensed electrician, a calibrated clamp " +
            "meter or a statutory inspection.",
    )
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
            Text(r.fault.labelTe, style = MaterialTheme.typography.bodyMedium)
            Text(r.fault.action, style = MaterialTheme.typography.bodyMedium)
            Text(r.fault.actionTe, style = MaterialTheme.typography.bodySmall)
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
    OutlinedButton(
        onClick = onClick,
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
