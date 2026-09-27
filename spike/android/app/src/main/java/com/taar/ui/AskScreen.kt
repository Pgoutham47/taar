package com.taar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.taar.domain.AssistantPrompt
import com.taar.ml.TaarAssistant

/**
 * Ask Taar: questions about the app, answered on the phone by the same Qwen model
 * that explains results, from the built-in notes that match each question. The
 * notes an answer came from are shown under it.
 */
@Composable
fun AskScreen(
    state: TaarViewModel.UiState,
    onAsk: (String) -> Unit,
    onImportModel: (android.net.Uri) -> Unit,
    onClear: () -> Unit,
) {
    val a = state.assistant
    val chat = state.chat
    var draft by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImportModel) }

    // Follow the answer as it streams in.
    LaunchedEffect(chat.size, chat.lastOrNull()?.text?.length) { scroll.animateScrollTo(scroll.maxValue) }

    Column(
        Modifier.fillMaxSize().background(TaarPalette.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(top = Space.xl, bottom = Space.m),
            verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("Ask Taar", style = MaterialTheme.typography.headlineMedium)
                Text("Qwen2.5 · runs on this phone · English", style = MaterialTheme.typography.bodyMedium,
                    color = TaarPalette.Grey)
            }
            if (chat.isNotEmpty() && !a.busy) {
                Text("Clear", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Yellow,
                    modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClear).padding(8.dp))
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = Space.gutter)
                .padding(bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            when {
                a.importing != null -> TaarCard {
                    Text("Loading the assistant…", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { a.importing }, modifier = Modifier.fillMaxWidth(),
                        color = TaarPalette.Yellow, trackColor = TaarPalette.SurfaceHigh)
                    Hint("Copying ${(a.importing * 100).toInt()}% · about a minute, once.")
                }
                !a.installed -> TaarCard {
                    Text("Set up the assistant", style = MaterialTheme.typography.titleMedium)
                    Text("Copy the model file (about 550 MB) to this phone once, then pick it. It stays on the " +
                        "phone; nothing is downloaded.", style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Grey)
                    Hint("File: ${TaarAssistant.MODEL_NAME}", color = TaarPalette.Faint)
                    PrimaryButton("Load model file", onClick = { pick.launch(arrayOf("*/*")) })
                    a.error?.let { Banner(it, Tone.DANGER) }
                }
                chat.isEmpty() -> {
                    TaarCard(tone = Tone.INFO) {
                        Text("Ask anything about Taar", style = MaterialTheme.typography.titleMedium)
                        Text("How to measure, what a result means, how detection works, privacy. Answers come " +
                            "from Taar's built-in notes and are written on this phone.",
                            style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Grey)
                    }
                    SectionLabel("Try asking")
                    for (q in AssistantPrompt.GENERAL_PRESETS) Suggestion(q) { onAsk(q) }
                }
                else -> for ((i, m) in chat.withIndex()) {
                    if (m.fromUser) UserBubble(m.text)
                    else AnswerBubble(m, thinking = a.busy && i == chat.lastIndex && m.text.isEmpty())
                }
            }
            if (a.installed && chat.isNotEmpty()) {
                Hint("Answers are written on this phone by a small language model and can be wrong. For a " +
                    "reading, the result screen is what to act on.", color = TaarPalette.Faint)
            }
        }

        if (a.installed && a.importing == null) {
            Row(
                Modifier.fillMaxWidth().background(TaarPalette.Background).padding(horizontal = Space.l, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(300) },
                    placeholder = { Text("Ask about Taar…", color = TaarPalette.Faint) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    shape = MaterialTheme.shapes.large,
                    enabled = !a.busy,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TaarPalette.Yellow, unfocusedBorderColor = TaarPalette.Outline,
                        focusedContainerColor = TaarPalette.Surface, unfocusedContainerColor = TaarPalette.Surface,
                        disabledContainerColor = TaarPalette.Surface, disabledBorderColor = TaarPalette.Outline,
                    ),
                )
                FilledIconButton(
                    onClick = { onAsk(draft); draft = "" },
                    enabled = !a.busy && draft.isNotBlank(),
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = TaarPalette.Ink, contentColor = TaarPalette.OnAccent,
                        disabledContainerColor = TaarPalette.SurfaceHigh, disabledContentColor = TaarPalette.Faint,
                    ),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }
}

@Composable
private fun Suggestion(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = TaarPalette.Surface,
        border = BorderStroke(1.dp, TaarPalette.Outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Text,
            modifier = Modifier.widthIn(max = 300.dp).clip(MaterialTheme.shapes.large)
                .background(TaarPalette.Yellow.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun AnswerBubble(m: TaarViewModel.ChatMessage, thinking: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = TaarPalette.Surface,
        border = BorderStroke(1.dp, TaarPalette.Outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (thinking) Text("Thinking…", style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Grey)
            if (m.text.isNotEmpty()) Text(m.text, style = MaterialTheme.typography.bodyMedium)
            for (c in m.concerns) Banner(c, Tone.WARNING)
            m.error?.let { Banner(it, Tone.DANGER) }
            if (m.sources.isNotEmpty() && m.text.isNotEmpty()) {
                Text("From: " + m.sources.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = TaarPalette.Faint)
            }
        }
    }
}
