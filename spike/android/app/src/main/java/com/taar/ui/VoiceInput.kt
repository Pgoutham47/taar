package com.taar.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * Offline speech recognition for voice commands: Vosk with its small Indian English
 * model, shipped in the APK and run on the phone's CPU. Nothing is sent anywhere.
 *
 * Each utterance is decoded twice at once, from the same audio. One recogniser is
 * limited to Taar's command words and this board's circuit names -- on test
 * recordings that turned "the open birdman" back into "open board map". The other
 * is unlimited, for anything that is not a command: a question for the assistant,
 * or phrasing the rules do not know, which the on-device model then rephrases.
 *
 * Two ways to talk. A tap on the mic listens until the first pause. Holding
 * volume-down listens until it is let go, which works with the phone pressed
 * against a cable and the screen facing the wall.
 *
 * The microphone is also Taar's arc sensor. [canListen] is asked before every start
 * and refuses while a capture runs, and the recorder is released after every
 * utterance, so a capture that follows never finds the microphone taken.
 */
class VoiceInput(private val context: Context) {

    /** Why listening cannot start now (a capture is running, say), or null when it can. */
    var canListen: () -> String? = { null }

    /** The words the command recogniser may hear, asked for at each start so new circuit names count. */
    var vocabulary: () -> List<String> = { emptyList() }

    /** On the main thread: what the command recogniser heard, and what the unlimited one heard. */
    var onHeard: (command: String, free: String) -> Unit = { _, _ -> }

    /** Called when listening cannot start, with the reason, to be said and shown. */
    var onRefused: (String) -> Unit = {}

    var ready by mutableStateOf(false)
        private set
    var listening by mutableStateOf(false)
        private set
    /** The words so far, while listening. */
    var partial by mutableStateOf("")
        private set
    var failed by mutableStateOf<String?>(null)
        private set

    private var model: Model? = null
    private var session: Session? = null
    private val main = Handler(Looper.getMainLooper())

    /** Copies the model out of the APK on first run (a few seconds), then loads it. */
    fun prepare() {
        StorageService.unpack(context, "model-en-in", "model",
            { m -> model = m; ready = true },
            { e -> failed = "Voice model could not load: ${e.message}" },
        )
    }

    /** One turn of a conversation: listen until the first pause. */
    fun listenTurn() {
        if (session == null) start(hold = false)
    }

    /** Stops listening and throws away what was heard: the conversation was ended. */
    fun cancel() {
        session?.let { it.discard = true; it.finish() }
    }

    /** Volume-down pressed. */
    fun holdStart() {
        if (session == null) start(hold = true)
    }

    /** Volume-down released. */
    fun holdEnd() {
        session?.takeIf { it.hold }?.finish()
    }

    private fun start(hold: Boolean) {
        val m = model
        if (m == null) {
            onRefused(if (failed != null) "Voice is not available on this phone." else "Voice is getting ready. Try again in a moment.")
            return
        }
        canListen()?.let { onRefused(it); return }
        val s = runCatching { Session(m, hold, JSONArray(vocabulary() + "[unk]").toString()) }.getOrNull()
        if (s == null) { onRefused("The microphone is busy. Try again."); return }
        session = s
        partial = ""
        listening = true
        s.start()
    }

    fun close() {
        session?.finish()
        model?.close()
    }

    /** One utterance: its own recorder and recognisers, all released at the end. */
    private inner class Session(model: Model, val hold: Boolean, grammar: String) : Thread("taar-voice") {
        private val command = Recognizer(model, RATE.toFloat(), grammar)
        private val free = Recognizer(model, RATE.toFloat())
        private val recorder = newRecorder()
        @Volatile private var stopping = false
        @Volatile var discard = false

        fun finish() { stopping = true }

        override fun run() {
            val said = StringBuilder()
            val freeSaid = StringBuilder()
            val buf = ShortArray(RATE / 10)
            val limit = System.currentTimeMillis() + if (hold) HOLD_LIMIT_MS else TAP_LIMIT_MS
            try {
                recorder.startRecording()
                while (!stopping && System.currentTimeMillis() < limit) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (free.acceptWaveForm(buf, n)) append(freeSaid, text(free.result, "text"))
                    if (command.acceptWaveForm(buf, n)) {
                        val t = text(command.result, "text")
                        append(said, t)
                        // A tap listens for one phrase: the first pause with words in it ends it.
                        if (!hold && t.isNotEmpty()) break
                    } else {
                        val p = (said.toString() + " " + text(command.partialResult, "partial")).trim()
                        main.post { partial = p }
                    }
                }
            } catch (_: Exception) {
                // A recorder that fails mid-way ends the utterance with what was heard so far.
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
            append(said, text(command.finalResult, "text"))
            append(freeSaid, text(free.finalResult, "text"))
            command.close()
            free.close()
            val c = said.toString().trim()
            val f = freeSaid.toString().trim()
            main.post {
                session = null
                listening = false
                partial = c.ifEmpty { f }
                if (!discard) onHeard(c, f)
            }
        }
    }

    @SuppressLint("MissingPermission") // Checked by canListen before any session starts.
    private fun newRecorder(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 2) * 2)
        check(r.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        return r
    }

    private fun append(to: StringBuilder, t: String) {
        if (t.isNotEmpty()) to.append(' ').append(t)
    }

    private fun text(json: String?, key: String): String =
        runCatching { JSONObject(json ?: "{}").optString(key) }.getOrDefault("").trim()

    private companion object {
        const val RATE = 16000
        const val TAP_LIMIT_MS = 7000L
        const val HOLD_LIMIT_MS = 15000L
    }
}
