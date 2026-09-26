package com.taar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taar.domain.Circuit
import com.taar.domain.LineState
import com.taar.ml.ArcModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home: which circuit, which setup steps are done, and one big button for the next
 * thing to do. The old screen offered six equal buttons and left the order to guesswork.
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

    TaarScreen(
        title = "Taar",
        subtitle = "Checks whether current is flowing in a cable, using the phone's magnetic sensor.",
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Hint("Now measuring")
                Text(
                    circuit?.let { "${state.installation?.name ?: ""} › ${it.label}" } ?: "No circuit chosen",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                circuit?.breakerRatingA?.let { Hint("Breaker %.0f A".format(it)) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Setup", style = MaterialTheme.typography.titleMedium)

                StepRow(
                    number = 1,
                    title = "Phone check",
                    state = when {
                        phoneOk -> StepState.DONE
                        phoneFailed -> StepState.PROBLEM
                        else -> StepState.TODO
                    },
                    detail = state.phoneCheck?.let {
                        if (it.passed) "Sensor working · %.0f Hz · %s".format(it.rateHz, time(it.atMillis))
                        else "Last check failed · try again"
                    } ?: "Not done yet · takes 3 seconds",
                    actionLabel = if (state.phoneCheck == null) "Start" else "Redo",
                    onAction = { onGo(MainActivity.Screen.PHONE_CHECK) },
                )
                HorizontalDivider()
                StepRow(
                    number = 2,
                    title = "Circuit",
                    state = if (hasCircuit) StepState.DONE else StepState.TODO,
                    detail = circuit?.let { "${it.label} on ${state.installation?.name}" }
                        ?: "Choose which cable you are measuring",
                    actionLabel = if (hasCircuit) "Change" else "Choose",
                    onAction = { onGo(MainActivity.Screen.CIRCUITS) },
                )
                HorizontalDivider()
                StepRow(
                    number = 3,
                    title = "Reference",
                    state = if (hasReference) StepState.DONE else StepState.TODO,
                    detail = circuit?.baseline?.takeIf { it.isSufficient }?.let {
                        "Recorded ${time(it.recordedAtMillis)} · " + when (LineState.of(it.medianLineConfidence)) {
                            LineState.NONE -> "no current was flowing"
                            LineState.UNCLEAR -> "signal was unclear"
                            LineState.FLOWING -> "current was flowing"
                        }
                    } ?: "What 'normal' looks like for this cable",
                    actionLabel = when {
                        !hasCircuit -> null
                        hasReference -> "Redo"
                        else -> "Record"
                    },
                    onAction = { onGo(MainActivity.Screen.REFERENCE) },
                )
                HorizontalDivider()
                StepRow(
                    number = 4,
                    title = "Amps (optional)",
                    state = if (calibrated) StepState.DONE else StepState.OPTIONAL,
                    detail = if (calibrated) "Calibrated · readings show amps"
                    else "Not calibrated · readings won't show amps",
                    actionLabel = if (hasReference) (if (calibrated) "Redo" else "Calibrate") else null,
                    onAction = { onGo(MainActivity.Screen.CALIBRATE) },
                )
            }
        }

        AiStatus(state.aiSelfCheck)

        // One primary action: the next unfinished step, or Measure once ready.
        val (label, target) = when {
            state.phoneCheck == null || phoneFailed -> "Next: phone check" to MainActivity.Screen.PHONE_CHECK
            !hasCircuit -> "Next: choose a circuit" to MainActivity.Screen.CIRCUITS
            !hasReference -> "Next: record reference" to MainActivity.Screen.REFERENCE
            else -> "Measure" to MainActivity.Screen.MEASURE
        }
        Button(
            onClick = { onGo(target) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(label, style = MaterialTheme.typography.titleMedium) }

        if (target == MainActivity.Screen.MEASURE) {
            Hint("Put the phone flat on the cable, tap Measure, and keep still for 3 seconds.")
        }

        Button(
            onClick = { onGo(MainActivity.Screen.LIVE) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TaarPalette.Blue),
        ) { Text("See What Taar Sees · live physics", style = MaterialTheme.typography.titleMedium) }

        OutlinedButton(onClick = { onGo(MainActivity.Screen.ROOM) }, modifier = Modifier.fillMaxWidth()) {
            Text("Room 3D Scan (AR) · experimental")
        }

        if (hasReference) {
            OutlinedButton(onClick = { onGo(MainActivity.Screen.CABLE_SCAN) }, modifier = Modifier.fillMaxWidth()) {
                Text("Cable Scan · where along the cable?")
            }
        }
        OutlinedButton(onClick = { onGo(MainActivity.Screen.HISTORY) }, modifier = Modifier.fillMaxWidth()) {
            Text("History")
        }

        Hint(
            "Taar is a triage aid. It does not replace a licensed electrician, a calibrated " +
                "clamp meter or a statutory inspection.",
        )
    }
}

/** "14:05" for today, "25 Sep 14:05" otherwise. */
fun time(millis: Long): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val today = day.format(Date()) == day.format(Date(millis))
    return SimpleDateFormat(if (today) "HH:mm" else "d MMM HH:mm", Locale.getDefault()).format(Date(millis))
}

/** Whether the on-device model loaded and reproduces its training outputs on this phone. */
@Composable
private fun AiStatus(check: ArcModel.SelfCheck?) {
    val (text, colour) = when {
        check == null -> "On-device AI: not available on this phone · sparking check uses the rule alone" to
            TaarPalette.Grey
        check.passed -> "✓ On-device AI ready · TFLite model self-check passed (${check.rows}/${check.rows})" to
            TaarPalette.Green
        else -> "✕ On-device AI self-check failed · its opinion is not shown reliably" to TaarPalette.Red
    }
    Hint(text, color = colour)
}
