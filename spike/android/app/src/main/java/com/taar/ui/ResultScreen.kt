package com.taar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.taar.domain.CurrentBasis
import com.taar.domain.RankedFault
import com.taar.domain.Reading
import com.taar.domain.Status
import com.taar.domain.Thresholds

/**
 * The result of one capture.
 *
 * Two rules govern this screen. Ranked causes are shown with the evidence that
 * produced them, so a technician can disagree with the tool on specifics rather
 * than on the whole. And a current is only ever printed in amperes when the circuit
 * was calibrated — otherwise it is a relative index, because a fabricated ampere
 * figure is worse than no figure.
 */
@Composable
fun ResultScreen(
    reading: Reading,
    status: Status,
    faults: List<RankedFault>,
    thresholds: Thresholds,
    basis: CurrentBasis,
    impliedCurrentA: Double?,
    spectrogram: com.taar.dsp.Spectrogram.Result?,
    prediction: com.taar.domain.Prediction?,
    onLabel: (Status) -> Unit,
    onMeasureAgain: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusBanner(status)

        if (!reading.fieldEstimateUsable) {
            Advisory(
                "The field estimate was ill-conditioned, so load figures are omitted. " +
                    "Re-run the pre-check.",
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Metric(
                    "Mains detected",
                    "%.0fx contrast · %.2f".format(reading.lineContrast, reading.lineConfidence),
                )
                Metric("Field amplitude", "%.2f uT".format(reading.fieldAmplitudeUt))
                Metric(
                    "Load",
                    when {
                        !reading.fieldEstimateUsable -> "not usable"
                        basis == CurrentBasis.CALIBRATED && impliedCurrentA != null ->
                            "%.2f A".format(impliedCurrentA)
                        // Never an ampere figure from assumed geometry.
                        else -> "index only — circuit not calibrated"
                    },
                )
                Metric("Arc modulation", "%.4f".format(reading.arcModulationIndex))
                Metric(
                    "Thresholds",
                    "warn %.1f / crit %.1f MAD%s".format(
                        thresholds.warningZ, thresholds.criticalZ,
                        if (thresholds.isProvisional) " (provisional)" else "",
                    ),
                )
            }
        }

        spectrogram?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Envelope spectrogram", style = MaterialTheme.typography.titleSmall)
                    SpectrogramView(it)
                }
            }
        }

        if (faults.isEmpty()) {
            Text("No fault matched. Nothing here says the circuit is safe — only that " +
                "this reading looks like its own reference.",
                style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Likely causes", style = MaterialTheme.typography.titleSmall)
            for (r in faults) FaultCard(r)
        }

        prediction?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Similar to readings you labelled '${it.label}'",
                        style = MaterialTheme.typography.titleSmall)
                    Text("distance %.2f · margin %.2f".format(it.distance, it.margin),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace)
                    Advisory("Learned from this board's own labels. Advisory only — " +
                        "the ranked causes above are what to act on.")
                }
            }
        }

        Advisory(
            "Taar is a triage aid. It does not replace a licensed electrician, a " +
                "calibrated clamp meter or a statutory inspection.",
        )

        Text("Confirming a label is what calibrates this circuit's thresholds.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onLabel(Status.HEALTHY) }) { Text("Healthy") }
            OutlinedButton(onClick = { onLabel(Status.WARNING) }) { Text("Warning") }
            OutlinedButton(onClick = { onLabel(Status.CRITICAL) }) { Text("Critical") }
        }

        Button(onClick = onMeasureAgain, modifier = Modifier.fillMaxWidth()) {
            Text("Measure again")
        }
    }
}

@Composable
private fun StatusBanner(status: Status) {
    val (label, colour) = when (status) {
        Status.HEALTHY -> "HEALTHY" to Color(0xFF3DDC84)
        Status.WARNING -> "WARNING" to Color(0xFFFF9F45)
        Status.CRITICAL -> "CRITICAL" to Color(0xFFFF6B6B)
        Status.UNKNOWN -> "NOT USABLE" to Color(0xFF9AA4B2)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = colour, style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun FaultCard(r: RankedFault) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.fault.label, style = MaterialTheme.typography.titleSmall)
            Text(r.fault.labelTe, style = MaterialTheme.typography.bodyMedium)
            Text(r.fault.action, style = MaterialTheme.typography.bodySmall)
            Text(r.fault.actionTe, style = MaterialTheme.typography.bodySmall)
            Text("Evidence (${r.supported}/${r.total})",
                style = MaterialTheme.typography.labelSmall)
            for (e in r.evidence) {
                Text("  ${if (e.holds) "y" else "n"}  ${e.description}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Advisory(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9AA4B2))
}
