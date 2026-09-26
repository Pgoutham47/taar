package com.taar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.taar.domain.Circuit
import com.taar.domain.LineState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home: which cable, what is left to set up, and one button for the next thing.
 *
 * Setup is three steps in a fixed order. Until they are done the checklist leads
 * and the button says which step is next; once they are, the checklist folds into
 * a single line and the button says Measure.
 */
@Composable
fun HomeScreen(
    state: TaarViewModel.UiState,
    circuit: Circuit?,
    onGo: (MainActivity.Screen) -> Unit,
) {
    val phoneOk = state.phoneCheck?.passed == true
    val phoneFailed = state.phoneCheck?.passed == false
    val hasCircuit = circuit != null
    val hasReference = circuit?.baseline?.isSufficient == true
    val calibrated = circuit?.utPerAmp != null
    val done = listOf(phoneOk, hasCircuit, hasReference).count { it }

    TaarScreen(title = "Taar", subtitle = "Electrical triage from your phone · తార", bottomInset = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("Offline", Tone.SUCCESS)
            StatusPill(
                if (state.aiSelfCheck?.passed == true) "Arc AI on" else "Arc AI off",
                if (state.aiSelfCheck?.passed == true) Tone.SUCCESS else Tone.NEUTRAL,
            )
            StatusPill(
                if (state.assistant.installed) "Assistant on" else "Assistant off",
                if (state.assistant.installed) Tone.INFO else Tone.NEUTRAL,
            )
        }

        TaarCard(onClick = { onGo(MainActivity.Screen.CIRCUITS) }) {
            SectionLabel("Now measuring")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(circuit?.label ?: "No circuit chosen", style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(
                            state.installation?.name,
                            circuit?.breakerRatingA?.let { "%.0f A breaker".format(it) },
                            if (calibrated) "amps calibrated" else null,
                        ).joinToString(" · ").ifEmpty { "Choose the cable you are measuring" },
                        style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey,
                    )
                }
                Text("Change", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Yellow)
            }
        }

        if (done < 3) {
            TaarCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Get ready", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("$done of 3", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Grey)
                }
                LinearProgressIndicator(
                    progress = { done / 3f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.extraSmall),
                    color = TaarPalette.Yellow, trackColor = TaarPalette.SurfaceHigh,
                    gapSize = 0.dp, drawStopIndicator = {},
                )
                StepRow(
                    number = 1, title = "Phone check",
                    state = when { phoneOk -> StepState.DONE; phoneFailed -> StepState.PROBLEM; else -> StepState.TODO },
                    detail = state.phoneCheck?.let {
                        if (it.passed) "Sensor at %.0f Hz · ${time(it.atMillis)}".format(it.rateHz) else "Last check failed"
                    } ?: "3 seconds, phone flat on a table",
                    actionLabel = if (state.phoneCheck == null) "Start" else "Redo",
                    onAction = { onGo(MainActivity.Screen.PHONE_CHECK) },
                )
                StepRow(
                    number = 2, title = "Choose the circuit",
                    state = if (hasCircuit) StepState.DONE else StepState.TODO,
                    detail = circuit?.label ?: "Which cable you are measuring",
                    actionLabel = if (hasCircuit) "Change" else "Choose",
                    onAction = { onGo(MainActivity.Screen.CIRCUITS) },
                )
                StepRow(
                    number = 3, title = "Record its normal",
                    state = if (hasReference) StepState.DONE else StepState.TODO,
                    detail = circuit?.baseline?.takeIf { it.isSufficient }?.let { "Recorded ${time(it.recordedAtMillis)}" }
                        ?: "3 captures of the wire in its normal state",
                    actionLabel = when { !hasCircuit -> null; hasReference -> "Redo"; else -> "Record" },
                    onAction = { onGo(MainActivity.Screen.REFERENCE) },
                )
            }
        } else {
            Banner(
                "Press the phone flat on the cable and tap Measure. Keep it still for 3 seconds.",
                Tone.SUCCESS, title = "Ready to measure",
            )
        }

        val (label, target) = when {
            state.phoneCheck == null || phoneFailed -> "Start phone check" to MainActivity.Screen.PHONE_CHECK
            !hasCircuit -> "Choose a circuit" to MainActivity.Screen.CIRCUITS
            !hasReference -> "Record the reference" to MainActivity.Screen.REFERENCE
            else -> "Measure" to MainActivity.Screen.MEASURE
        }
        PrimaryButton(label, onClick = { onGo(target) })

        if (done == 3) {
            TaarCard {
                ListRow(
                    "Reference", circuit?.baseline?.let {
                        "Recorded ${time(it.recordedAtMillis)} · " + when (LineState.of(it.medianLineConfidence)) {
                            LineState.NONE -> "no current"
                            LineState.UNCLEAR -> "unclear signal"
                            LineState.FLOWING -> "current flowing"
                        }
                    }, icon = Icons.Filled.Settings, iconTint = TaarPalette.Green,
                    onClick = { onGo(MainActivity.Screen.REFERENCE) },
                )
                Rule()
                ListRow(
                    "Amps", if (calibrated) "Calibrated · readings show amperes" else "Optional · calibrate to see amperes",
                    icon = Icons.Filled.Build, iconTint = if (calibrated) TaarPalette.Green else TaarPalette.Grey,
                    onClick = { onGo(MainActivity.Screen.CALIBRATE) },
                )
            }
        }

        Hint("Taar is a triage aid. It senses current, not voltage, and does not replace a licensed " +
            "electrician, a calibrated meter or a voltage tester.")
    }
}

/** Deeper checks and setup, one tap away from any tab. */
@Composable
fun ToolsScreen(onGo: (MainActivity.Screen) -> Unit) {
    TaarScreen(title = "Tools", subtitle = "Deeper checks and setup", bottomInset = false) {
        SectionLabel("Inspect")
        TaarCard {
            ListRow("Cable Scan", "Find where along a cable the sparking signal is strongest",
                icon = Icons.Filled.Search, onClick = { onGo(MainActivity.Screen.CABLE_SCAN) })
            Rule()
            ListRow("See What Taar Sees", "Live view from sensor signal to result",
                icon = Icons.Filled.PlayArrow, iconTint = TaarPalette.Blue, onClick = { onGo(MainActivity.Screen.LIVE) })
        }
        SectionLabel("Setup")
        TaarCard {
            ListRow("Circuits and boards", "Add, rename and choose what you measure",
                icon = Icons.Filled.Settings, iconTint = TaarPalette.Grey, onClick = { onGo(MainActivity.Screen.CIRCUITS) })
            Rule()
            ListRow("Phone check", "Sensor speed, background noise, microphone",
                icon = Icons.Filled.Phone, iconTint = TaarPalette.Grey, onClick = { onGo(MainActivity.Screen.PHONE_CHECK) })
            Rule()
            ListRow("Calibrate amps", "Use an appliance of known power to show amperes",
                icon = Icons.Filled.Build, iconTint = TaarPalette.Grey, onClick = { onGo(MainActivity.Screen.CALIBRATE) })
        }
        SectionLabel("About")
        TaarCard {
            Metric("Version", "0.1")
            Metric("Arc model", "TFLite · 16.6 KB")
            Metric("Assistant", "Qwen2.5 · 0.5B · on device")
            Metric("Network", "none · offline")
        }
    }
}

/** "14:05" for today, "25 Sep 14:05" otherwise. */
fun time(millis: Long): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val today = day.format(Date()) == day.format(Date(millis))
    return SimpleDateFormat(if (today) "HH:mm" else "d MMM HH:mm", Locale.getDefault()).format(Date(millis))
}
