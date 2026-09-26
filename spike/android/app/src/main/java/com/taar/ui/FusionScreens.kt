package com.taar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.taar.domain.Fusion
import com.taar.domain.RankedFault

/**
 * The unified result, shown first after a scan: what each signal said, what they
 * add up to, why, and what to do. Everything behind it is on [WhyStage].
 */
@Composable
fun FusionCard(a: Fusion.Analysis, onWhy: () -> Unit) {
    val tone = a.outcome.tone
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = surfaceOf(tone) ?: CardDefaults.cardColors().containerColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TAAR ANALYSIS", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Grey)
            for (row in a.summaryRows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.name, style = MaterialTheme.typography.bodyMedium)
                    Text(row.value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                        color = colourOf(row), textAlign = TextAlign.End)
                }
            }
            Text("↓", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = TaarPalette.Grey)

            Text("${iconOf(tone)} ${a.outcome.title}", style = MaterialTheme.typography.headlineSmall,
                color = colourOf(tone), fontWeight = FontWeight.SemiBold)
            Text(a.outcome.titleTe, style = MaterialTheme.typography.bodyMedium)
            Text(a.outcome.summary, style = MaterialTheme.typography.bodyMedium)
            Text("Evidence strength: ${a.strength.name}", style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold)
            Hint(a.strengthReason)

            HorizontalDivider()
            Text("WHY?", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Grey)
            for (w in a.why.take(3)) Text("• $w", style = MaterialTheme.typography.bodyMedium)
            if (a.conflicts.isNotEmpty()) {
                Hint("${a.conflicts.size} signal${if (a.conflicts.size == 1) "" else "s"} pointed the " +
                    "other way. See Why? for details.", color = TaarPalette.Amber)
            }

            Text("WHAT TO DO", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Grey)
            for (g in a.whatToDo.take(2)) {
                Text("• ${g.text}", style = MaterialTheme.typography.bodyMedium)
                g.textTe?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey) }
            }

            OutlinedButton(onClick = onWhy, modifier = Modifier.fillMaxWidth()) {
                Text("Why? See all evidence →")
            }
            Hint(Fusion.CAVEAT)
        }
    }
}

/** Every signal behind the result, the rules that fired, and the full guidance. */
@Composable
fun WhyStage(a: Fusion.Analysis, faults: List<RankedFault>, onBack: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = surfaceOf(a.outcome.tone) ?: CardDefaults.cardColors().containerColor)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${iconOf(a.outcome.tone)} ${a.outcome.title}", style = MaterialTheme.typography.titleLarge,
                color = colourOf(a.outcome.tone), fontWeight = FontWeight.SemiBold)
            Text(a.outcome.titleTe, style = MaterialTheme.typography.bodyMedium)
            Text("Evidence strength: ${a.strength.name} · measurement quality: ${a.quality.name}",
                style = MaterialTheme.typography.labelLarge)
            Hint(a.strengthReason)
        }
    }

    Section("Signals")
    Hint("Each signal is labelled by how it bears on the result: pointing to an anomaly, pointing " +
        "to normal, context only, or not available.")
    for (s in a.signals) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(tagOf(s.verdict), style = MaterialTheme.typography.labelMedium, color = colourOf(s))
                }
                Text(s.value, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace,
                    color = colourOf(s))
                Text(s.detail, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey)
            }
        }
    }

    Section("Why this result")
    for (w in a.why) Text("• $w", style = MaterialTheme.typography.bodyMedium)

    if (a.conflicts.isNotEmpty()) {
        Section("Where the signals disagree")
        for (c in a.conflicts) Text("• $c", style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Amber)
    }

    Section("Rules that fired")
    if (faults.isEmpty()) Hint("No rule warnings for this reading.")
    for (f in faults) {
        Text(f.fault.label, style = MaterialTheme.typography.titleSmall)
        for (e in f.evidence) {
            Text("${if (e.holds) "✓" else "✕"} ${e.description}", style = MaterialTheme.typography.bodySmall,
                color = TaarPalette.Grey)
        }
    }

    Section("What to do")
    for (g in a.whatToDo) {
        Text("• ${g.text}", style = MaterialTheme.typography.bodyMedium)
        g.textTe?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey) }
    }

    Hint(Fusion.CAVEAT)
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Back to result", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

private fun iconOf(tone: Fusion.Tone) = when (tone) {
    Fusion.Tone.CRITICAL -> "⚠️"
    Fusion.Tone.WARNING -> "⚠"
    Fusion.Tone.ADVISORY -> "ℹ"
    Fusion.Tone.NORMAL -> "✓"
    Fusion.Tone.UNRELIABLE -> "?"
}

private fun colourOf(tone: Fusion.Tone): Color = when (tone) {
    Fusion.Tone.CRITICAL -> TaarPalette.Red
    Fusion.Tone.WARNING -> TaarPalette.Amber
    Fusion.Tone.ADVISORY -> TaarPalette.Blue
    Fusion.Tone.NORMAL -> TaarPalette.Green
    Fusion.Tone.UNRELIABLE -> TaarPalette.Amber
}

private fun surfaceOf(tone: Fusion.Tone): Color? = when (tone) {
    Fusion.Tone.CRITICAL -> TaarPalette.RedSurface
    Fusion.Tone.WARNING, Fusion.Tone.UNRELIABLE -> TaarPalette.AmberSurface
    else -> null
}

private fun colourOf(s: Fusion.Signal): Color = when {
    s.name == "Measurement quality" -> when (s.value) {
        Fusion.Quality.GOOD.name -> TaarPalette.Green
        Fusion.Quality.FAIR.name -> TaarPalette.Amber
        else -> TaarPalette.Red
    }
    s.verdict == Fusion.Verdict.SUPPORTS -> TaarPalette.Amber
    s.verdict == Fusion.Verdict.AGAINST -> TaarPalette.Green
    s.verdict == Fusion.Verdict.UNAVAILABLE -> TaarPalette.Grey
    else -> Color.Unspecified
}

private fun tagOf(v: Fusion.Verdict) = when (v) {
    Fusion.Verdict.SUPPORTS -> "points to an anomaly"
    Fusion.Verdict.AGAINST -> "points to normal"
    Fusion.Verdict.NEUTRAL -> "context"
    Fusion.Verdict.UNAVAILABLE -> "not available"
}

/**
 * Ask Taar AI: the on-device language model explaining the result above in plain
 * English. It never replaces the result; it only answers questions about it.
 */
@Composable
fun AssistantPanel(
    a: TaarViewModel.Assistant,
    onAsk: (String) -> Unit,
    onImportModel: (android.net.Uri) -> Unit,
) {
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImportModel) }
    var question by rememberSaveable { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ASK TAAR AI", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Grey)
            Hint("Qwen2.5 · 0.5B · runs on this phone, offline · English")

            when {
                a.importing != null -> {
                    Text("Copying the model… ${(a.importing * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { a.importing }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                !a.installed -> {
                    Text(
                        "One-time setup: copy the model file (about 550 MB) to this phone, then pick it here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Hint("File: ${com.taar.ml.TaarAssistant.MODEL_NAME}")
                    OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Load model file")
                    }
                }
                else -> {
                    for (p in com.taar.domain.AssistantPrompt.PRESETS) {
                        OutlinedButton(onClick = { onAsk(p) }, enabled = !a.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(p)
                        }
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = question,
                        onValueChange = { question = it.take(300) },
                        label = { Text("Or ask your own question") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !a.busy,
                    )
                    Button(
                        onClick = { onAsk(question); question = "" },
                        enabled = !a.busy && question.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Ask") }
                }
            }

            a.question?.let { Text("Q: $it", style = MaterialTheme.typography.titleSmall) }
            if (a.busy && a.answer.isEmpty()) {
                Hint("Thinking… the first answer takes a few seconds while the model loads.")
            }
            if (a.answer.isNotEmpty()) Text(a.answer, style = MaterialTheme.typography.bodyMedium)
            for (c in a.concerns) Hint("⚠ $c", color = TaarPalette.Amber)
            a.error?.let { Hint(it, color = TaarPalette.Red) }
            if (a.answer.isNotEmpty() || a.busy) {
                Hint("Written on this phone by a small language model from the evidence above. It can be wrong; " +
                    "the result above is what to act on.")
            }
        }
    }
}
