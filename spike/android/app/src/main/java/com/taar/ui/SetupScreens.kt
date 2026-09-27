package com.taar.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taar.domain.AmpCalibration
import com.taar.domain.Circuit
import com.taar.domain.LineState
import com.taar.domain.MotionCheck
import com.taar.ml.ArcModel
import kotlin.math.roundToInt

/**
 * Recording a reference, as a guided sequence: what it is, how to set up, a start
 * button, visible progress, and a confirmation that says what was seen.
 */
@Composable
fun ReferenceScreen(
    state: TaarViewModel.UiState,
    circuit: Circuit?,
    onStart: () -> Unit,
    onMeasure: () -> Unit,
    onBack: () -> Unit,
) {
    val capturing = state.capture?.kind == TaarViewModel.CaptureKind.REFERENCE
    TaarScreen(
        title = "Record reference",
        subtitle = circuit?.let { "${state.installation?.name} › ${it.label}" },
        onBack = if (capturing) null else onBack,
    ) {
        if (circuit == null) {
            ErrorCard("Choose a circuit first.")
            return@TaarScreen
        }

        Text(
            "A reference is Taar's picture of normal for this cable. Every measurement is compared with it " +
                "to spot unusual load or sparking.",
            style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Grey,
        )

        when {
            capturing -> CapturingPanel(
                title = "Recording reference",
                step = state.capture!!.step,
                total = state.capture.total,
                what = "Listening for the 50 Hz hum of current in the cable.",
                settling = state.capture.settling,
            )

            state.referenceJustRecorded && circuit.baseline != null -> {
                val b = circuit.baseline
                val seen = LineState.of(b.medianLineConfidence)
                val ai = state.referenceAi
                val motion = state.referenceMotion
                val aiHeard = ai.count { it >= ArcModel.THRESHOLD }
                val moved = motion.indices.filter { MotionCheck.level(motion[it]) == MotionCheck.Level.MOVED }
                TaarCard(tone = Tone.SUCCESS) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Filled.CheckCircle, contentDescription = null,
                            tint = TaarPalette.Green, modifier = Modifier.size(28.dp),
                        )
                        Text("Reference saved", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        when (seen) {
                            LineState.NONE -> "No current was flowing while it was recorded."
                            LineState.UNCLEAR -> "The current signal was unclear while it was recorded."
                            LineState.FLOWING -> "Current was flowing while it was recorded."
                        },
                        style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Grey,
                    )
                    Rule()
                    Metric("Signal", b.lineConfidences.joinToString(" · ") { times(LineState.contrastOf(it)) }, mono = false)
                    if (ai.isNotEmpty()) Metric("Sparking (AI)", ai.joinToString(" · ") { "${(it * 100).roundToInt()}%" }, mono = false)
                    if (motion.isNotEmpty()) Metric("Movement", motion.joinToString(" · ") {
                        motionWord(MotionCheck.level(it))
                    }, mono = false)
                }
                if (aiHeard > 0) {
                    Banner("The AI heard a sparking-like sound in $aiHeard of ${ai.size} captures. If this circuit is " +
                        "not known to be healthy, redo the reference somewhere quiet.", Tone.WARNING)
                }
                if (moved.isNotEmpty()) {
                    Banner("The phone moved in capture ${moved.joinToString(", ") { "${it + 1}" }}. Every measurement " +
                        "is compared with this reference, so redo it holding the phone still.", Tone.WARNING)
                }
                PrimaryButton("Measure now", onClick = onMeasure)
                SecondaryButton("Back to home", onClick = onBack)
            }

            else -> {
                TaarCard {
                    SectionLabel("Before you start")
                    Instruction(1, "Lay the phone flat on the cable, in the spot you will measure from later. Tape it if you can.")
                    Instruction(2, "Leave the circuit as it normally is. For an appliance: plugged in, switched off.")
                    Instruction(3, "Tap Start and don't touch the phone until it finishes.")
                }
                circuit.baseline?.takeIf { it.isSufficient }?.let {
                    Banner("This replaces the reference recorded at ${time(it.recordedAtMillis)}.", Tone.WARNING)
                }
                state.error?.let { ErrorCard(it) }
                PrimaryButton("Start · about 10 seconds", onClick = onStart)
            }
        }
    }
}

/**
 * Amp calibration against an appliance of known power: off, then on. Each step
 * unlocks the next, and a failure says which step went wrong and what to change.
 */
@Composable
fun CalibrateScreen(
    state: TaarViewModel.UiState,
    circuit: Circuit?,
    onWatts: (String) -> Unit,
    onCapture: (applianceOn: Boolean) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val cal = state.calibration
    val watts = cal.watts.toDoubleOrNull()?.takeIf { it > 0 }
    val kind = state.capture?.kind
    TaarScreen(
        title = "Calibrate amps",
        subtitle = circuit?.let { "${state.installation?.name} › ${it.label}" },
        onBack = if (state.busy) null else onBack,
    ) {
        if (circuit == null) {
            ErrorCard("Choose a circuit first.")
            return@TaarScreen
        }

        Hint(
            "Taar can show current in amps if you measure one appliance whose power you know. " +
                "Use something big: a kettle, iron or heater (1000 W or more).",
            color = MaterialTheme.colorScheme.onSurface,
        )
        circuit.utPerAmp?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Already calibrated: %.3f µT per amp".format(it), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onClear) { Text("Remove calibration") }
                }
            }
        }

        // Step 1: power.
        CalStep(1, "Appliance power", done = watts != null) {
            OutlinedTextField(
                value = cal.watts,
                onValueChange = { v -> onWatts(v.filter { it.isDigit() || it == '.' }) },
                label = { Text("Watts, from the appliance's label") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            watts?.let {
                Hint("= %.1f A at %.0f V".format(AmpCalibration.ampsFromWatts(it), AmpCalibration.DEFAULT_VOLTAGE))
            }
        }

        // Step 2: appliance off.
        CalStep(2, "Appliance OFF", done = cal.offDone) {
            Hint("Phone flat on the appliance's cable. Appliance plugged in, switched OFF.")
            when {
                kind == TaarViewModel.CaptureKind.CALIBRATE_OFF -> CapturingPanel(
                    "Appliance OFF", state.capture!!.step, state.capture.total,
                    "Measuring the background with nothing running.", settling = state.capture.settling,
                )
                cal.offDone -> Hint("✓ " + summary(cal.offConfidences), color = TaarPalette.Green)
            }
            if (kind != TaarViewModel.CaptureKind.CALIBRATE_OFF) {
                OutlinedButton(
                    onClick = { onCapture(false) },
                    enabled = watts != null && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (cal.offDone) "Capture OFF again" else "Capture OFF · about 10 s") }
            }
        }

        // Step 3: appliance on.
        CalStep(3, "Appliance ON", done = cal.onDone) {
            Hint("Don't move the phone. Switch the appliance ON, wait 5 seconds, then tap Capture.")
            when {
                kind == TaarViewModel.CaptureKind.CALIBRATE_ON -> CapturingPanel(
                    "Appliance ON", state.capture!!.step, state.capture.total,
                    "Measuring the field while the appliance runs.", settling = state.capture.settling,
                )
                cal.onDone -> Hint("✓ " + summary(cal.onConfidences), color = TaarPalette.Green)
            }
            if (kind != TaarViewModel.CaptureKind.CALIBRATE_ON) {
                OutlinedButton(
                    onClick = { onCapture(true) },
                    enabled = cal.offDone && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (cal.onDone) "Capture ON again" else "Capture ON · about 10 s") }
            }
        }

        when (val r = cal.result) {
            is AmpCalibration.Result.Ok -> {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = TaarPalette.GreenSurface),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (cal.saved) "✓ Calibration saved" else "Calibration ready",
                            style = MaterialTheme.typography.titleLarge,
                            color = TaarPalette.Green,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "%.1f A gave %.2f µT → %.3f µT per amp".format(r.amps, r.onMedianUt, r.utPerAmp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Hint(
                            "Amp readings are only right with the phone in this same spot on this same " +
                                "cable. Move it and they become an estimate.",
                        )
                    }
                }
                if (cal.saved) {
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text("Done", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text("Save calibration", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            is AmpCalibration.Result.Failed -> ErrorCard(r.reason)
            null -> Unit
        }
    }
}

@Composable
private fun CalStep(number: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StepRow(number, title, if (done) StepState.DONE else StepState.TODO, "", null) {}
            content()
        }
    }
}

private fun summary(confidences: List<Double>): String {
    val states = confidences.map { LineState.of(it) }
    val verdict = when {
        states.all { it == LineState.FLOWING } -> "current flowing"
        states.all { it == LineState.NONE } -> "no current"
        else -> "mixed"
    }
    return "${confidences.size} captures, $verdict (" +
        confidences.joinToString(" · ") { "%.0f×".format(LineState.contrastOf(it)) } + ")"
}


