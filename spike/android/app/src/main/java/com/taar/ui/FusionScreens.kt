package com.taar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
    val tone = uiTone(a.outcome.tone)
    TaarCard(tone = tone) {
        StatusPill(statusWord(a.outcome.tone), tone)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(a.outcome.title, style = MaterialTheme.typography.headlineSmall, color = colourOf(a.outcome.tone))
            Text(a.outcome.titleTe, style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Grey)
        }
        Text(a.outcome.summary, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("Evidence ${a.strength.name.lowercase()}", Tone.NEUTRAL)
            StatusPill("Quality ${a.quality.name.lowercase()}", when (a.quality) {
                Fusion.Quality.GOOD -> Tone.SUCCESS
                Fusion.Quality.FAIR -> Tone.WARNING
                Fusion.Quality.POOR -> Tone.DANGER
            })
        }

        Rule()
        SectionLabel("Taar analysis")
        for (row in a.summaryRows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Grey)
                Text(row.value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                    color = colourOf(row).takeIf { it != Color.Unspecified } ?: TaarPalette.Text, textAlign = TextAlign.End)
            }
        }

        Rule()
        SectionLabel("Why")
        for (w in a.why.take(3)) Text("•  $w", style = MaterialTheme.typography.bodyMedium)
        if (a.conflicts.isNotEmpty()) {
            Banner("${a.conflicts.size} signal${if (a.conflicts.size == 1) "" else "s"} pointed the other way. " +
                "See all evidence for details.", Tone.WARNING)
        }

        SectionLabel("What to do")
        for (g in a.whatToDo.take(2)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("•  ${g.text}", style = MaterialTheme.typography.bodyMedium)
                g.textTe?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey,
                    modifier = Modifier.padding(start = 14.dp)) }
            }
        }

        SecondaryButton("See all evidence", onClick = onWhy)
        Text(Fusion.CAVEAT, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Faint)
    }
}

private fun uiTone(t: Fusion.Tone) = when (t) {
    Fusion.Tone.CRITICAL -> Tone.DANGER
    Fusion.Tone.WARNING -> Tone.WARNING
    Fusion.Tone.ADVISORY -> Tone.INFO
    Fusion.Tone.NORMAL -> Tone.SUCCESS
    Fusion.Tone.UNRELIABLE -> Tone.WARNING
}

private fun statusWord(t: Fusion.Tone) = when (t) {
    Fusion.Tone.CRITICAL -> "Possible anomaly"
    Fusion.Tone.WARNING -> "Check"
    Fusion.Tone.ADVISORY -> "Note"
    Fusion.Tone.NORMAL -> "Normal"
    Fusion.Tone.UNRELIABLE -> "Measure again"
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

    TaarCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Ask Taar AI about this result", style = MaterialTheme.typography.titleMedium)
                Text("Qwen2.5 · on this phone · English", style = MaterialTheme.typography.bodySmall,
                    color = TaarPalette.Grey)
            }
        }

        when {
            a.importing != null -> {
                Text("Loading the assistant… ${(a.importing * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { a.importing }, modifier = Modifier.fillMaxWidth(),
                    color = TaarPalette.Yellow, trackColor = TaarPalette.SurfaceHigh,
                )
            }
            !a.installed -> {
                Hint("One-time setup: copy the model file (about 550 MB) to this phone, then pick it.")
                SecondaryButton("Load model file", onClick = { pick.launch(arrayOf("*/*")) })
            }
            else -> {
                for (p in com.taar.domain.AssistantPrompt.PRESETS) {
                    androidx.compose.material3.Surface(
                        onClick = { onAsk(p) },
                        enabled = !a.busy,
                        shape = MaterialTheme.shapes.medium,
                        color = TaarPalette.SurfaceHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(p, style = MaterialTheme.typography.bodyMedium,
                            color = if (a.busy) TaarPalette.Faint else TaarPalette.Text,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(300) },
                    placeholder = { Text("Or ask your own question", color = TaarPalette.Faint) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !a.busy,
                    shape = MaterialTheme.shapes.medium,
                    maxLines = 3,
                )
                PrimaryButton("Ask", onClick = { onAsk(question); question = "" },
                    enabled = !a.busy && question.isNotBlank())
            }
        }

        if (a.question != null || a.busy) Rule()
        a.question?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = TaarPalette.Yellow) }
        if (a.busy && a.answer.isEmpty()) Hint("Thinking… the first answer takes a few seconds while the model loads.")
        if (a.answer.isNotEmpty()) Text(a.answer, style = MaterialTheme.typography.bodyMedium)
        for (c in a.concerns) Banner(c, Tone.WARNING)
        a.error?.let { Banner(it, Tone.DANGER) }
        if (a.answer.isNotEmpty()) {
            Text("Written on this phone by a small language model from the evidence above. It can be wrong; " +
                "the result above is what to act on.", style = MaterialTheme.typography.bodySmall, color = TaarPalette.Faint)
        }
    }
}
