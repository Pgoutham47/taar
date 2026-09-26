package com.taar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

        Hint(
            "A reference is Taar's picture of 'normal' for this cable. Every measurement is " +
                "compared with it to spot unusual load or sparking.",
            color = MaterialTheme.colorScheme.onSurface,
        )

        when {
            capturing -> CapturingPanel(
                title = "Recording reference",
                step = state.capture!!.step,
                total = state.capture.total,
                what = "Listening for the 50 Hz hum of current in the cable.",
            )

            state.referenceJustRecorded && circuit.baseline != null -> {
                val b = circuit.baseline
                val seen = LineState.of(b.medianLineConfidence)
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16301F)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("✓ Reference saved", style = MaterialTheme.typography.titleLarge,
                            color = TaarPalette.Green, fontWeight = FontWeight.SemiBold)
                        Text(
                            "During the reference: " + when (seen) {
                                LineState.NONE -> "no current was flowing."
                                LineState.UNCLEAR -> "the signal was unclear."
                                LineState.FLOWING -> "current was flowing."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Hint(
                            "Signal in each capture: " + b.lineConfidences.joinToString(" · ") {
                                "%.0f×".format(LineState.contrastOf(it))
                            },
                        )
                    }
                }
                Button(onClick = onMeasure, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Measure now", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
            }

            else -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Before you start", style = MaterialTheme.typography.titleSmall)
                        Instruction(1, "Lay the phone flat on the cable, in the spot you will measure from later. Tape it if you can.")
                        Instruction(2, "Leave the circuit as it normally is. For an appliance: plugged in, switched off.")
                        Instruction(3, "Tap Start and don't touch the phone until it finishes.")
                    }
                }
                circuit.baseline?.takeIf { it.isSufficient }?.let {
                    Hint("This replaces the reference recorded at ${time(it.recordedAtMillis)}.", color = TaarPalette.Amber)
                }
                state.error?.let { ErrorCard(it) }
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Start · about 10 seconds", style = MaterialTheme.typography.titleMedium)
                }
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
                    "Measuring the background with nothing running.",
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
                    "Measuring the field while the appliance runs.",
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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16301F)),
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
