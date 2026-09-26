package com.taar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taar.domain.Circuit
import com.taar.domain.Installation
import com.taar.domain.LineState
import com.taar.domain.Status
import com.taar.domain.Store

/**
 * Boards and circuits on one screen. Tapping a circuit selects it and returns home;
 * every edit saves immediately.
 *
 * The old flow split this across two screens with a Save button, and selecting a
 * circuit that had been added but not yet saved silently selected nothing.
 */
@Composable
fun CircuitsScreen(
    installations: List<Installation>,
    selectedBoardId: String?,
    selectedCircuitId: String?,
    onSelect: (boardId: String, circuitId: String) -> Unit,
    onAddBoard: (String) -> Unit,
    onAddCircuit: (boardId: String, label: String, ratingA: Double?) -> Unit,
    onUpdateCircuit: (boardId: String, Circuit) -> Unit,
    onRemoveCircuit: (boardId: String, circuitId: String) -> Unit,
    onBench: (boardId: String, Boolean) -> Unit,
    onRenameBoard: (boardId: String, String) -> Unit,
    onBack: () -> Unit,
) {
    var newBoard by remember { mutableStateOf("") }

    TaarScreen(
        title = "Choose circuit",
        subtitle = "Tap the circuit you are measuring. Changes save straight away.",
        onBack = onBack,
    ) {
        for (inst in installations) {
            BoardCard(
                inst = inst,
                selectedCircuitId = if (inst.id == selectedBoardId) selectedCircuitId else null,
                onSelect = { onSelect(inst.id, it) },
                onAddCircuit = { label, rating -> onAddCircuit(inst.id, label, rating) },
                onUpdateCircuit = { onUpdateCircuit(inst.id, it) },
                onRemoveCircuit = { onRemoveCircuit(inst.id, it) },
                onBench = { onBench(inst.id, it) },
                onRename = { onRenameBoard(inst.id, it) },
            )
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add a board", style = MaterialTheme.typography.titleSmall)
                Hint("A board is one place, such as a home or a distribution board.")
                OutlinedTextField(
                    value = newBoard,
                    onValueChange = { newBoard = it },
                    label = { Text("Board name, e.g. Home") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onAddBoard(newBoard.trim()); newBoard = "" },
                    enabled = newBoard.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add board") }
            }
        }
    }
}

/**
 * The bench-rig switch matters more than it looks. A phone tested on a table with a
 * desk lamp produces readings that are not representative of a board, and without
 * this they would silently teach the thresholds that get applied to real ones.
 */
@Composable
private fun BoardCard(
    inst: Installation,
    selectedCircuitId: String?,
    onSelect: (String) -> Unit,
    onAddCircuit: (String, Double?) -> Unit,
    onUpdateCircuit: (Circuit) -> Unit,
    onRemoveCircuit: (String) -> Unit,
    onBench: (Boolean) -> Unit,
    onRename: (String) -> Unit,
) {
    var newLabel by remember(inst.id) { mutableStateOf("") }
    var newRating by remember(inst.id) { mutableStateOf("") }
    var editing by remember(inst.id) { mutableStateOf<String?>(null) }
    var renaming by remember(inst.id) { mutableStateOf(false) }
    var boardName by remember(inst.id) { mutableStateOf(inst.name) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (renaming) {
                OutlinedTextField(
                    boardName, { boardName = it }, label = { Text("Board name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onRename(boardName.trim()); renaming = false },
                        enabled = boardName.isNotBlank(),
                    ) { Text("Save") }
                    OutlinedButton(onClick = { boardName = inst.name; renaming = false }) { Text("Cancel") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(inst.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { boardName = inst.name; renaming = true }) { Text("Rename") }
                }
            }

            if (inst.circuits.isEmpty()) Hint("No circuits yet. Add one below.")

            for (c in inst.circuits) {
                if (editing == c.id) {
                    CircuitEditor(
                        circuit = c,
                        onSave = { onUpdateCircuit(it); editing = null },
                        onRemove = { onRemoveCircuit(c.id); editing = null },
                        onCancel = { editing = null },
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(c.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = c.id == selectedCircuitId, onClick = { onSelect(c.id) })
                        Column(Modifier.weight(1f)) {
                            Text(c.label, style = MaterialTheme.typography.bodyLarge)
                            Hint(circuitSummary(c))
                        }
                        TextButton(onClick = { editing = c.id }) { Text("Edit") }
                    }
                }
            }

            HorizontalDivider()
            Text("Add a circuit", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    newLabel, { newLabel = it }, label = { Text("Name, e.g. Kettle") },
                    singleLine = true, modifier = Modifier.weight(1.6f),
                )
                OutlinedTextField(
                    newRating, { v -> newRating = v.filter { it.isDigit() || it == '.' } },
                    label = { Text("Breaker A") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = {
                    onAddCircuit(newLabel.trim(), newRating.toDoubleOrNull())
                    newLabel = ""; newRating = ""
                },
                enabled = newLabel.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add circuit") }

            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = inst.isBenchRig, onCheckedChange = onBench)
                Column {
                    Text("Test bench", style = MaterialTheme.typography.bodyMedium)
                    Hint("Turn on for practice setups, so they don't tune the warning levels of real boards.")
                }
            }
        }
    }
}

@Composable
private fun CircuitEditor(
    circuit: Circuit,
    onSave: (Circuit) -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    var label by remember(circuit.id) { mutableStateOf(circuit.label) }
    var rating by remember(circuit.id) { mutableStateOf(circuit.breakerRatingA?.let { "%.0f".format(it) } ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            rating, { v -> rating = v.filter { it.isDigit() || it == '.' } },
            label = { Text("Breaker rating (A)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(circuit.copy(label = label.trim(), breakerRatingA = rating.toDoubleOrNull())) },
                enabled = label.isNotBlank(),
            ) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = onRemove) { Text("Remove", color = TaarPalette.Red) }
        }
    }
}

private fun circuitSummary(c: Circuit): String = listOfNotNull(
    c.breakerRatingA?.let { "%.0f A breaker".format(it) },
    if (c.baseline?.isSufficient == true) "reference ✓" else "no reference",
    if (c.utPerAmp != null) "amps ✓" else null,
).joinToString(" · ")

/** Every reading taken at a board, newest first. */
@Composable
fun HistoryScreen(
    boardName: String,
    readings: List<Store.LabelledReading>,
    circuitLabels: Map<String, String>,
    onBack: () -> Unit,
) {
    // Labelling appends the reading a second time with its label, so keep one row
    // per capture: the last written, which carries the label if there is one.
    val unique = readings.associateBy { it.reading.circuitId to it.reading.epochMillis }.values
        .sortedByDescending { it.reading.epochMillis }
    TaarScreen(
        title = "History",
        subtitle = "$boardName · ${unique.size} readings, newest first",
        onBack = onBack,
    ) {
        if (unique.isEmpty()) Hint("No readings yet.")
        for (lr in unique.take(MAX_HISTORY)) {
            val r = lr.reading
            val line = LineState.of(r.lineConfidence)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(circuitLabels[r.circuitId] ?: r.circuitId, style = MaterialTheme.typography.titleSmall)
                        Text(time(r.epochMillis), style = MaterialTheme.typography.labelMedium, color = TaarPalette.Grey)
                    }
                    Text(
                        when (line) {
                            LineState.FLOWING -> "Current flowing"
                            LineState.UNCLEAR -> "Unclear"
                            LineState.NONE -> "No current"
                        } + " · %.0f×".format(LineState.contrastOf(r.lineConfidence)) +
                            (if (r.supplyIsolated) " · supply OFF" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (line) {
                            LineState.FLOWING -> if (r.supplyIsolated) TaarPalette.Red else TaarPalette.Yellow
                            LineState.UNCLEAR -> TaarPalette.Amber
                            LineState.NONE -> TaarPalette.Blue
                        },
                    )
                    Text(
                        "field %.2f µT · sparking %.4f%s%s".format(
                            r.fieldAmplitudeUt,
                            r.arcModulationIndex,
                            if (r.fieldEstimateUsable) "" else " · field not usable",
                            lr.label?.let { " · marked ${labelName(it)}" } ?: "",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (lr.label != null) labelColour(lr.label) else TaarPalette.Grey,
                    )
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

/** A scrolling column, not a lazy list, so older readings are capped to keep it quick. */
private const val MAX_HISTORY = 200

private fun labelColour(status: Status?) = when (status) {
    Status.HEALTHY -> TaarPalette.Green
    Status.WARNING -> TaarPalette.Amber
    Status.CRITICAL -> TaarPalette.Red
    else -> TaarPalette.Grey
}
