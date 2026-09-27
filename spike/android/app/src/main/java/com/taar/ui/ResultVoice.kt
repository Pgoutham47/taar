package com.taar.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** What the view model needs to speak a result, so it never holds a Context. */
interface Speaker {
    fun speak(text: String)
    fun stop()
}

/**
 * Speaks results and voice replies through the phone's own text-to-speech engine,
 * which runs on the phone: no network, nothing leaves it.
 *
 * The microphone is Taar's arc sensor, so speech and capture never overlap: the
 * view model calls [stop] before every capture, and a voice command that starts a
 * capture waits for its "Measuring…" to finish ([speak] with onDone) before it does.
 * If the phone has no usable English voice, nothing is said and onDone still runs.
 */
class ResultVoice(context: Context) : Speaker, AutoCloseable {

    @Volatile private var ready = false
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, () -> Unit>()
    private var next = 0

    /** True while words are playing: a conversation does not listen until it is false. */
    var speaking by mutableStateOf(false)
        private set
    private var current: String? = null

    /** The last thing said, for "say that again". */
    @Volatile var last: String? = null
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS && pickLanguage()
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
    }

    /**
     * The callback runs before [speaking] turns false, in the same main-thread step,
     * so an action it starts (a measurement) is already running by the time a
     * conversation checks whether it may listen again.
     */
    private fun finish(id: String?) {
        val then = id?.let { pending.remove(it) }
        main.post {
            then?.invoke()
            if (id == current) speaking = false
        }
    }

    /** Indian English where the engine has it, since that is who will hear it; otherwise any English. */
    private fun pickLanguage(): Boolean =
        listOf(Locale("en", "IN"), Locale.UK, Locale.US, Locale.ENGLISH).any { locale ->
            tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
        }

    override fun speak(text: String) = speak(text, null)

    /** [onDone] runs on the main thread once the words have finished, or at once if nothing can be said. */
    fun speak(text: String, onDone: (() -> Unit)?) {
        last = text
        if (!ready) { onDone?.let { main.post(it) }; return }
        val id = "taar-${next++}"
        onDone?.let { pending[id] = it }
        current = id
        speaking = true
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id)
    }

    override fun stop() {
        if (ready) tts.stop()
        speaking = false
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
    }
}
