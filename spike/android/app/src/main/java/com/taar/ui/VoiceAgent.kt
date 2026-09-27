package com.taar.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.taar.domain.EnergyCost
import com.taar.domain.VoiceCommand
import com.taar.domain.VoiceCommand.Command
import kotlinx.coroutines.delay

/**
 * Carries out what the technician said, the way a tap would, and says what it did.
 *
 * The same view model calls as the buttons: a voice "measure" is a tap on Measure,
 * not a second way of measuring. Anything that starts a capture first says so and
 * waits for the words to finish, because the microphone is the arc sensor.
 * Commands that would change what a reading means -- marking a supply off, deleting
 * a circuit -- are not offered by voice at all.
 *
 * A tap on the mic starts a conversation: after each reply Taar listens again by
 * itself, so a round of a board is "measure kitchen", "next", "measure", "next"...
 * without touching the screen. It listens only when nothing is being recorded and
 * nothing is being said, ends on "stop listening" or two silent turns, and hands
 * the microphone over whenever a mode that uses it (live view, cable scan) starts.
 */
class VoiceAgent(private val vm: TaarViewModel, private val voice: ResultVoice) {

    /** Shows a tab or opens a screen; set by the activity, which owns navigation. */
    var navigate: (VoiceCommand.Place) -> Unit = {}
    var openScreen: (MainActivity.Screen) -> Unit = {}

    /** What was heard and what Taar answered, shown beside the mic for a few seconds. */
    var shown by mutableStateOf<Pair<String, String>?>(null)
        private set
    var thinking by mutableStateOf(false)
        private set

    /** A conversation is on: Taar listens again after every reply. */
    var conversation by mutableStateOf(false)
        private set
    /** The next turn may start as soon as nothing is recording or speaking. */
    var waiting by mutableStateOf(false)
        private set
    /** When the last real command came (or the conversation began, or a capture ended). */
    private var lastActive = 0L

    /** Set when a question went to the assistant, so its answer is spoken when it arrives. */
    private var speakNextAnswer = false

    fun startConversation() {
        conversation = true
        lastActive = System.currentTimeMillis()
        say("", "I'm listening. Say what you need, or say stop listening.")
        waiting = true
    }

    fun endConversation(words: String?) {
        conversation = false
        waiting = false
        words?.let { say("", it) }
    }

    /** The activity is starting the next turn's listening. */
    fun turnStarted() { waiting = false }

    /** A measurement or recording just finished: the quiet time starts again from now. */
    fun captureEnded() { lastActive = System.currentTimeMillis() }

    /**
     * A turn with nothing for Taar in it -- silence, a noise, people talking nearby.
     * The conversation keeps listening until [IDLE_MS] has passed with no command.
     */
    private fun quietTurn() {
        if (System.currentTimeMillis() - lastActive > IDLE_MS) {
            endConversation("I'll stop listening now. Tap the mic when you need me.")
        } else nextTurn()
    }

    private fun nextTurn() {
        if (conversation) waiting = true
    }

    /**
     * [command] is what the recogniser limited to command words heard, [free] what the
     * unlimited one heard. The limited one is trusted when every word fitted and it
     * names an action; otherwise the free text is tried, then rephrased by the model.
     */
    fun handle(command: String, free: String) {
        android.util.Log.d("TAAR", "voice heard: command='$command' free='$free' conversation=$conversation")
        val names = vm.state.value.installation?.circuits?.map { VoiceCommand.Name(it.id, it.label) } ?: emptyList()
        val heard = free.ifBlank { command.replace("[unk]", "").trim() }
        if (command.isBlank() && heard.isBlank()) {
            if (!conversation) { say("", "I didn't hear anything. Tap the mic and speak, or hold volume down."); return }
            quietTurn()
            return
        }
        // Mid-conversation, talk nearby and stray noises count as silence: no action, no "sorry".
        if (conversation && VoiceCommand.looksLikeChatter(command, free, VoiceCommand.vocabulary(names.map { it.label }), names)) {
            quietTurn()
            return
        }
        lastActive = System.currentTimeMillis()
        if (command.isNotBlank() && "[unk]" !in command) {
            VoiceCommand.parse(command, names)?.let { run(command, it); return }
        }
        VoiceCommand.parse(heard, names)?.let { run(heard, it); return }

        if (!vm.state.value.assistant.installed) {
            say(heard, "Sorry, I didn't get that. Say help to hear what I can do.")
            nextTurn()
            return
        }
        // Not a command the rules know: the model rephrases it into one, or it is not one.
        thinking = true
        shown = heard to "Thinking…"
        vm.rephraseCommand(heard) { rephrased ->
            thinking = false
            if (rephrased != null) run(heard, rephrased)
            else { say(heard, "Sorry, I didn't get that. Say help to hear what I can do."); nextTurn() }
        }
    }

    /** Called when the assistant finishes an answer; speaks it if the question came by voice. */
    fun onAssistantIdle() {
        if (!speakNextAnswer) return
        speakNextAnswer = false
        val last = vm.state.value.chat.lastOrNull()?.takeIf { !it.fromUser } ?: return
        val text = last.error ?: last.text
        if (text.isNotBlank()) say(shown?.first ?: "", text)
        nextTurn()
    }

    private fun run(heard: String, command: Command) {
        val st = vm.state.value
        val board = st.installation
        val circuits = board?.circuits ?: emptyList()
        fun circuit(id: String?) = circuits.firstOrNull { it.id == (id ?: st.selectedCircuitId) }
        fun reply(text: String) { say(heard, text); nextTurn() }

        when (command) {
            is Command.Open -> when (command.place) {
                // Clicks from the speaker would be heard as speech: hand over the microphone.
                VoiceCommand.Place.GEIGER -> {
                    navigate(command.place)
                    say(heard, "Opening Geiger mode. I'll stop listening while it clicks. Tap the mic when you need me.")
                    conversation = false; waiting = false
                }
                else -> { navigate(command.place); reply("Opening ${placeName(command.place)}.") }
            }

            is Command.Measure, is Command.RecordNormal -> {
                val id = (command as? Command.Measure)?.circuitId ?: (command as? Command.RecordNormal)?.circuitId
                val c = circuit(id)
                when {
                    board == null -> reply("Choose a board first.")
                    c == null -> reply("Which circuit? Say, for example, measure kitchen.")
                    st.busy -> reply("A measurement is already running.")
                    else -> {
                        if (c.id != st.selectedCircuitId) vm.selectCircuit(board.id, c.id)
                        val record = command is Command.RecordNormal
                        if (!record && c.baseline?.isSufficient != true) {
                            openScreen(MainActivity.Screen.REFERENCE)
                            reply("${c.label} has no normal recorded yet. Say: record normal.")
                        } else {
                            openScreen(if (record) MainActivity.Screen.REFERENCE else MainActivity.Screen.MEASURE)
                            val words = if (record) "Recording normal for ${c.label}. Keep the phone still for ten seconds."
                            else "Measuring ${c.label}. Hold still."
                            shown = heard to words
                            // The capture starts only once these words have finished.
                            voice.speak(words) { if (record) vm.recordBaseline() else vm.measure() }
                            nextTurn()
                        }
                    }
                }
            }

            is Command.Select -> {
                val c = circuit(command.circuitId)
                if (board == null || c == null) reply("I couldn't find that circuit.")
                else { vm.selectCircuit(board.id, c.id); reply("${c.label} selected.${readiness(c)}") }
            }

            Command.Next, Command.Previous -> {
                val i = circuits.indexOfFirst { it.id == st.selectedCircuitId }
                val j = if (command == Command.Next) i + 1 else i - 1
                when {
                    board == null || circuits.isEmpty() -> reply("There are no circuits on this board yet.")
                    j !in circuits.indices -> reply(if (command == Command.Next) "That was the last circuit." else "This is the first circuit.")
                    else -> {
                        val c = circuits[j]
                        vm.selectCircuit(board.id, c.id)
                        reply("${c.label}.${readiness(c)}")
                    }
                }
            }

            Command.ListCircuits -> reply(
                if (circuits.isEmpty()) "There are no circuits on this board yet."
                else "${board?.name} has ${circuits.size} circuit${if (circuits.size == 1) "" else "s"}: " +
                    listed(circuits.map { it.label }) + ".",
            )

            Command.WhichCircuit -> reply(circuit(null)?.let { "${it.label} on ${board?.name} is selected." }
                ?: "No circuit is selected. Say, for example, kitchen.")

            Command.Why -> {
                val f = st.fusion
                reply(if (f == null) "Measure first, then ask me why." else
                    "${f.outcome.title}. " + f.why.take(2).joinToString(" ") { it.trimEnd('.') + "." })
            }

            Command.WhatToDo -> {
                val f = st.fusion
                reply(when {
                    circuit(null) == null -> "Choose a circuit. Say, for example, kitchen."
                    circuit(null)?.baseline?.isSufficient != true -> "Record this circuit's normal first. Say: record normal."
                    f == null -> "Measure it. Say: measure."
                    else -> f.whatToDo.take(2).joinToString(" ") { it.text.trimEnd('.') + "." }
                        .ifBlank { "No action is suggested by this reading." }
                })
            }

            is Command.SetHours -> {
                val c = circuit(command.circuitId)
                if (board == null || c == null) { reply("Which circuit? Say, for example, the geyser runs two hours a day."); return }
                if (c.id != st.selectedCircuitId) vm.selectCircuit(board.id, c.id)
                vm.setCostHours(command.hours)
                reply("${c.label} runs ${EnergyCost.hours(command.hours).removeSuffix(" h")} hours a day. I'll use that for the cost.")
            }

            is Command.SetRate -> {
                vm.setTariffRate(command.rupeesPerUnit)
                reply("Rate set to ${"%.2f".format(command.rupeesPerUnit).removeSuffix("0").removeSuffix(".0")} rupees a unit.")
            }

            is Command.Cost -> {
                val c = circuit(command.circuitId)
                val e = EnergyCost.estimate(st.lastImpliedCurrentA, st.costHours, st.tariffRate)
                reply(when {
                    c == null -> "Which circuit? Say, for example, how much does the AC cost."
                    c.utPerAmp == null -> "Calibrate amps for ${c.label} first, then I can tell you the cost."
                    st.lastReading?.circuitId != c.id || c.id != st.selectedCircuitId -> "Measure ${c.label} first, then ask again."
                    e == null -> "No current was flowing in ${c.label}, so it costs nothing right now."
                    else -> "${c.label} uses about ${"%.1f".format(e.kilowatts)} kilowatts. That is about " +
                        "${EnergyCost.grouped(EnergyCost.roundRupees(e.rupeesPerMonth))} rupees a month at " +
                        "${EnergyCost.hours(e.hoursPerDay).removeSuffix(" h")} hours a day."
                })
            }

            is Command.Toggle -> {
                val name = if (command.mode == VoiceCommand.Mode.GEIGER) "Geiger mode" else "Board Map"
                if (command.mode == VoiceCommand.Mode.GEIGER) vm.setGeigerEnabled(command.on) else vm.setBoardMapEnabled(command.on)
                reply("$name is ${if (command.on) "on" else "off"}.")
            }

            is Command.AddCircuit -> {
                if (board == null) { reply("Choose a board first."); return }
                vm.addCircuit(board.id, command.name, null)
                vm.state.value.installation?.circuits?.lastOrNull { it.label == command.name }
                    ?.let { vm.selectCircuit(board.id, it.id) }
                reply("Added ${command.name}. Say record normal to set it up.")
            }

            is Command.StartScan -> {
                val c = circuit(null)
                if (!command.live && c?.baseline?.isSufficient != true) {
                    reply("Record this circuit's normal first. Say: record normal.")
                    return
                }
                // Both keep the microphone busy, so the conversation hands it over.
                navigate(if (command.live) VoiceCommand.Place.LIVE else VoiceCommand.Place.CABLE_SCAN)
                val words = if (command.live) "Starting the live view. I'll stop listening until you pause it."
                else "Starting the cable scan. Move the phone along the cable. I'll stop listening until you stop it."
                shown = heard to words
                conversation = false; waiting = false
                voice.speak(words) { if (command.live) vm.startLive() else vm.startCableScan() }
            }

            Command.Problems -> reply(VoiceCommand.problemsAnswer(vm.latestDots()))
            Command.Repeat -> reply(voice.last ?: "Nothing to repeat yet.")
            Command.Help -> reply(VoiceCommand.EXAMPLES)
            Command.End -> endConversation("Okay. Tap the mic when you need me.")

            is Command.Ask -> {
                navigate(VoiceCommand.Place.ASK)
                if (!st.assistant.installed) {
                    reply("The assistant is not set up. Load its model on the Ask AI tab.")
                } else {
                    shown = heard to "Asking Taar AI…"
                    speakNextAnswer = true
                    vm.askGeneral(command.question)
                }
            }
        }
    }

    /** What a circuit still needs, said after its name. */
    private fun readiness(c: com.taar.domain.Circuit) =
        if (c.baseline?.isSufficient == true) " Say measure when ready." else " No normal recorded yet. Say record normal."

    private fun listed(n: List<String>) = if (n.size <= 1) n.joinToString() else n.dropLast(1).joinToString(", ") + " and " + n.last()

    /** Shows and speaks a reply. */
    fun say(heard: String, text: String) {
        shown = heard to text
        voice.speak(text)
    }

    fun dismiss() { shown = null }

    private companion object {
        /** A conversation ends after this long with no command in it. */
        const val IDLE_MS = 30_000L
    }

    private fun placeName(p: VoiceCommand.Place) = when (p) {
        VoiceCommand.Place.HOME -> "home"
        VoiceCommand.Place.TOOLS -> "tools"
        VoiceCommand.Place.ASK -> "Taar AI"
        VoiceCommand.Place.HISTORY -> "history"
        VoiceCommand.Place.BOARD_MAP -> "the board map"
        VoiceCommand.Place.GEIGER -> "Geiger mode"
        VoiceCommand.Place.CIRCUITS -> "circuits"
        VoiceCommand.Place.CALIBRATE -> "calibration"
        VoiceCommand.Place.CABLE_SCAN -> "cable scan"
        VoiceCommand.Place.LIVE -> "the live view"
        VoiceCommand.Place.PHONE_CHECK -> "the phone check"
    }
}

/**
 * The mic on every screen: the button, what is being heard while listening, and
 * what Taar answered afterwards. [bottom] lifts it clear of a tab bar.
 */
@Composable
fun VoiceLayer(input: VoiceInput, agent: VoiceAgent, bottom: Dp, onMic: () -> Unit) {
    val shown = agent.shown
    LaunchedEffect(shown, agent.thinking) {
        if (shown != null && !agent.thinking && !input.listening && !agent.conversation) { delay(6000); agent.dismiss() }
    }
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).padding(end = 16.dp, bottom = bottom),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                input.listening -> Bubble {
                    Text("Listening…", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Yellow)
                    Text(input.partial.ifEmpty { "Say, for example: measure kitchen" },
                        style = MaterialTheme.typography.bodyLarge, color = if (input.partial.isEmpty()) TaarPalette.Grey else Color.White)
                    if (agent.conversation) {
                        Text("Say “stop listening” to end", style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey)
                    }
                }
                shown != null -> Bubble(onClick = { agent.dismiss() }) {
                    if (shown.first.isNotBlank()) {
                        Text("“${shown.first}”", style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey)
                    }
                    Text(shown.second, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                }
            }
            MicButton(input, agent.conversation, onMic)
        }
    }
}

@Composable
private fun Bubble(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large, color = TaarPalette.SurfaceHigh,
        border = BorderStroke(1.dp, TaarPalette.Outline),
        modifier = Modifier.widthIn(max = 300.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun MicButton(input: VoiceInput, conversation: Boolean, onMic: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "mic")
    val grow by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "grow")
    val colour = when {
        input.listening -> TaarPalette.Red
        conversation -> TaarPalette.Green
        input.ready -> TaarPalette.Yellow
        else -> TaarPalette.SurfaceHigh
    }
    Box(
        Modifier.size(64.dp).scale(if (input.listening) grow else 1f).clip(CircleShape).background(colour)
            .border(if (conversation) 4.dp else 2.dp, Color.White.copy(alpha = if (conversation) 0.8f else 0.25f), CircleShape)
            .clickable(onClick = onMic),
        contentAlignment = Alignment.Center,
    ) {
        if (!input.ready && input.failed == null) {
            CircularProgressIndicator(Modifier.size(28.dp), color = TaarPalette.Grey, strokeWidth = 3.dp)
        } else {
            MicGlyph(if (input.ready) Color(0xFF14110A) else TaarPalette.Grey)
        }
    }
}

/** A microphone, drawn: the core icon set has none. */
@Composable
private fun MicGlyph(colour: Color) {
    Canvas(Modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(colour, Offset(w * 0.36f, h * 0.06f), Size(w * 0.28f, h * 0.5f), CornerRadius(w * 0.14f))
        drawArc(colour, 0f, 180f, false, Offset(w * 0.22f, h * 0.2f), Size(w * 0.56f, h * 0.52f),
            style = Stroke(stroke, cap = StrokeCap.Round))
        drawLine(colour, Offset(w / 2, h * 0.72f), Offset(w / 2, h * 0.9f), stroke, StrokeCap.Round)
        drawLine(colour, Offset(w * 0.34f, h * 0.92f), Offset(w * 0.66f, h * 0.92f), stroke, StrokeCap.Round)
    }
}
