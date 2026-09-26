package com.taar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taar.dsp.LineFrequency

/**
 * Phone check: what the sensors are actually doing, before any measurement is trusted.
 *
 * Jugaad Agent's pre-check screen is the model here. Showing the measured sample
 * rate rather than the requested one is the difference between a tool that knows
 * its own limits and one that reports a number regardless.
 */

data class PreCheckState(
    val magAvailable: Boolean,
    val magMeasuredRateHz: Double,
    val magJitter: Double,
    val magRequestedRateHz: Int,
    val audioAvailable: Boolean,
    val noiseFloorUt: Double,
    val running: Boolean = false,
    /** The magnetometer capture returned nothing at all. */
    val failed: Boolean = false,
    val finished: Boolean = false,
) {
    val phases: Int
        get() = if (magMeasuredRateHz > 0) LineFrequency.distinctPhases(magMeasuredRateHz.toInt()) else 0

    /** Sampling visits too few phases of the 50 Hz wave to fit it. */
    val locked: Boolean get() = magMeasuredRateHz > 0 && phases < 3

    val noiseOk: Boolean get() = noiseFloorUt > 0 && noiseFloorUt < 2.0

    val passed: Boolean
        get() = magAvailable && !failed && magMeasuredRateHz > 0 && !locked && noiseOk
}

@Composable
fun PreCheckScreen(state: PreCheckState, onRun: () -> Unit, onDone: () -> Unit) {
    TaarScreen(
        title = "Phone check",
        subtitle = "Makes sure this phone's magnetic sensor is fast and steady enough to see mains current.",
        onBack = if (state.running) null else onDone,
    ) {
        if (!state.magAvailable) {
            ErrorCard("This phone has no magnetic sensor, so Taar cannot run on it.")
            return@TaarScreen
        }

        if (!state.finished && !state.running) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Instruction(1, "Put the phone flat on a table, away from chargers and appliances.")
                    Instruction(2, "Tap Start check.")
                    Instruction(3, "Don't touch the phone for 3 seconds.")
                }
            }
            Button(onClick = onRun, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Start check", style = MaterialTheme.typography.titleMedium)
            }
            return@TaarScreen
        }

        if (state.running) {
            CapturingPanel(
                title = "Checking the sensor",
                step = 1,
                total = 1,
                what = "Measuring how fast the sensor delivers readings and how noisy they are.",
            )
            return@TaarScreen
        }

        // Finished.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (state.passed) "✓ Phone is ready" else "✕ Phone check failed",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.passed) TaarPalette.Green else TaarPalette.Red,
                )
                Hint(
                    if (state.passed) "You can measure. You only need to redo this if readings look wrong."
                    else "See what failed below, then try again.",
                )
            }
        }

        if (state.failed) {
            ErrorCard(
                "The sensor is present but delivered nothing in 3 seconds. Try again; if it keeps " +
                    "happening, this phone cannot be used for Taar.",
            )
        } else {
            CheckRow(
                "Sensor speed",
                !state.locked,
                if (state.locked) "%.0f Hz — this speed cannot see 50 Hz mains".format(state.magMeasuredRateHz)
                else "%.0f Hz — good".format(state.magMeasuredRateHz),
            )
            CheckRow(
                "Background noise",
                state.noiseOk,
                if (state.noiseOk) "%.2f µT — fine".format(state.noiseFloorUt)
                else "%.2f µT — too noisy. Move away from chargers and metal, keep the phone still."
                    .format(state.noiseFloorUt),
            )
        }
        CheckRow(
            "Microphone",
            state.audioAvailable,
            if (state.audioAvailable) "Allowed — used to listen for sparking"
            else "Not allowed — the sparking check is off. Current detection still works.",
        )

        if (!state.failed) {
            Text(
                "requested %d Hz · %d phases · jitter %.1f%%".format(
                    state.magRequestedRateHz, state.phases, state.magJitter * 100,
                ),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = TaarPalette.Grey,
            )
        }

        if (state.passed) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Done", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(onClick = onRun, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
        } else {
            Button(onClick = onRun, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Try again", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(if (ok) "✓" else "!", color = if (ok) TaarPalette.Green else TaarPalette.Amber,
                style = MaterialTheme.typography.titleMedium)
            Column {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey)
            }
        }
    }
}
