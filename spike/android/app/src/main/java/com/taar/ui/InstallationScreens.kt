package com.taar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.taar.domain.Circuit
import com.taar.domain.Installation
import com.taar.domain.Status
import com.taar.domain.Store

/** The board list. */
@Composable
fun InstallationListScreen(
    installations: List<Installation>,
    onOpen: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Boards", style = MaterialTheme.typography.headlineSmall)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(installations, key = { it.id }) { inst ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(inst.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(inst.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${inst.circuits.size} circuits · " +
                                "${inst.circuits.count { it.baseline?.isSufficient == true }} with a reference" +
                                if (inst.isBenchRig) " · bench rig" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text("New board name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onCreate(newName.trim()); newName = "" },
            enabled = newName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add board") }
    }
}

/**
 * Circuit editor.
 *
 * The bench-rig switch matters more than it looks. A phone tested on a table with a
 * desk lamp produces readings that are not representative of a board, and without
 * this they would silently teach the thresholds that get applied to real ones.
 */
@Composable
fun CircuitEditorScreen(
    installation: Installation,
    onSave: (Installation) -> Unit,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember(installation.id) { mutableStateOf(installation.name) }
    var bench by remember(installation.id) { mutableStateOf(installation.isBenchRig) }
    var circuits by remember(installation.id) { mutableStateOf(installation.circuits) }
    var newLabel by remember { mutableStateOf("") }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Edit board", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(name, { name = it }, label = { Text("Board name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = bench, onCheckedChange = { bench = it })
            Column {
                Text("Bench / test rig", style = MaterialTheme.typography.bodyMedium)
                Text("Readings excluded from threshold calibration",
                    style = MaterialTheme.typography.labelSmall, color = Color(0xFF9AA4B2))
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(circuits, key = { it.id }) { c ->
                CircuitRow(
                    circuit = c,
                    onChange = { updated ->
                        circuits = circuits.map { if (it.id == updated.id) updated else it }
                    },
                    onSelect = { onSelect(c.id) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newLabel, { newLabel = it }, label = { Text("New circuit") },
                singleLine = true, modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    circuits = circuits + Circuit(
                        id = "c${System.currentTimeMillis()}",
                        label = newLabel.trim(),
                    )
                    newLabel = ""
                },
                enabled = newLabel.isNotBlank(),
            ) { Text("Add") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(installation.copy(name = name, isBenchRig = bench, circuits = circuits))
                },
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
private fun CircuitRow(circuit: Circuit, onChange: (Circuit) -> Unit, onSelect: () -> Unit) {
    var rating by remember(circuit.id) {
        mutableStateOf(circuit.breakerRatingA?.toString() ?: "")
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(circuit.label, style = MaterialTheme.typography.titleSmall)
            Text(
                if (circuit.baseline?.isSufficient == true)
                    "reference: ${circuit.baseline!!.sampleCount} captures"
                else "no reference recorded",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                when (circuit.utPerAmp) {
                    null -> "uncalibrated — readings shown as an index"
                    else -> "calibrated: %.2f uT/A".format(circuit.utPerAmp)
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            OutlinedTextField(
                value = rating,
                onValueChange = {
                    rating = it
                    onChange(circuit.copy(breakerRatingA = it.toDoubleOrNull()))
                },
                label = { Text("Breaker rating (A)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = onSelect) { Text("Select this circuit") }
        }
    }
}

/** Every reading taken at a board, newest first. */
@Composable
fun HistoryScreen(
    readings: List<Store.LabelledReading>,
    circuitLabels: Map<String, String>,
    onBack: () -> Unit,
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("History", style = MaterialTheme.typography.headlineSmall)
        Text("${readings.size} readings. Labelled ones calibrate the thresholds.",
            style = MaterialTheme.typography.bodySmall)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(readings.reversed()) { lr ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(circuitLabels[lr.reading.circuitId] ?: lr.reading.circuitId,
                                style = MaterialTheme.typography.titleSmall)
                            Text(lr.label?.name ?: "unlabelled",
                                style = MaterialTheme.typography.labelSmall,
                                color = labelColour(lr.label))
                        }
                        Text(
                            "field %.2f uT · line %.3f · arc %.4f%s".format(
                                lr.reading.fieldAmplitudeUt,
                                lr.reading.lineConfidence,
                                lr.reading.arcModulationIndex,
                                if (lr.reading.fieldEstimateUsable) "" else " · field not usable",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

private fun labelColour(status: Status?) = when (status) {
    Status.HEALTHY -> Color(0xFF3DDC84)
    Status.WARNING -> Color(0xFFFF9F45)
    Status.CRITICAL -> Color(0xFFFF6B6B)
    else -> Color(0xFF9AA4B2)
}
