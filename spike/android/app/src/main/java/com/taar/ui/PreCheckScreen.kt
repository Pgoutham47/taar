package com.taar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.taar.dsp.LineFrequency

/**
 * Pre-check: what the sensors are actually doing, before any measurement is trusted.
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
    val unprocessedGranted: Boolean,
    val noiseFloorUt: Double,
    val hasBaseline: Boolean,
    val running: Boolean = false,
    /** The magnetometer capture returned nothing at all. */
    val failed: Boolean = false,
)

@Composable
fun PreCheckScreen(state: PreCheckState, onRun: () -> Unit, onContinue: () -> Unit) {
    val phases = if (state.magMeasuredRateHz > 0)
        LineFrequency.distinctPhases(state.magMeasuredRateHz.toInt()) else 0
    val locked = state.magMeasuredRateHz > 0 && phases < 3

    Column(
        // Without this the title sits under the status bar clock.
        modifier = Modifier.fillMaxWidth().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pre-check", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Measured, not requested. A reading is only as good as what the sensors " +
                "actually delivered.",
            style = MaterialTheme.typography.bodySmall,
        )

        CheckRow("Magnetometer", state.magAvailable,
            if (state.magAvailable) "present" else "absent — this device cannot run Taar")

        if (state.running) {
            Text("Capturing 3 s…", style = MaterialTheme.typography.bodySmall)
        }
        if (state.failed) {
            Text(
                "Capture returned no samples. The sensor is present but delivered " +
                    "nothing in 3 s — re-run, and if it persists the device is not " +
                    "usable for Taar.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF6B6B),
            )
        }

        CheckRow(
            "Sample rate",
            state.magMeasuredRateHz > 0 && !locked,
            "%.1f Hz measured · requested %d Hz · %d distinct phases%s".format(
                state.magMeasuredRateHz, state.magRequestedRateHz, phases,
                if (locked) " — LOCKED, cannot see 50 Hz" else "",
            ),
        )

        // Jitter only matters when the rate is close to a locked one. With enough
        // distinct phases, perfectly uniform sampling is fine, and warning about it
        // teaches the technician to ignore the screen.
        CheckRow(
            "Timing jitter",
            state.magJitter > 0.01 || phases >= 3,
            "%.1f%% of the interval%s".format(
                state.magJitter * 100,
                when {
                    state.magJitter > 0.01 -> " — jitter also breaks harmonic lock"
                    phases >= 3 -> " — uniform, but $phases phases is plenty"
                    else -> " — uniform AND too few phases; the rate must change"
                },
            ),
        )

        CheckRow("Noise floor", state.noiseFloorUt > 0 && state.noiseFloorUt < 2.0,
            "%.2f uT RMS".format(state.noiseFloorUt))

        CheckRow("Microphone", state.audioAvailable,
            when {
                !state.audioAvailable -> "permission not granted"
                state.unprocessedGranted -> "UNPROCESSED source granted"
                else -> "fell back to MIC — arc band may be attenuated"
            })

        CheckRow("Reference", state.hasBaseline,
            if (state.hasBaseline) "recorded for this installation"
            else "none — record a baseline before measuring")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRun) { Text("Re-run checks") }
            Button(onClick = onContinue, enabled = state.magAvailable && !locked) {
                Text("Continue")
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (ok) "OK" else "!!", color = if (ok) Color(0xFF3DDC84) else Color(0xFFFF9F45))
                Text(label, style = MaterialTheme.typography.titleSmall)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
        }
    }
}
