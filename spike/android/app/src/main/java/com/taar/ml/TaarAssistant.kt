package com.taar.ml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import java.io.Closeable
import java.io.File

/**
 * The on-device language model: Qwen2.5-0.5B-Instruct (int8) through MediaPipe's
 * LLM Inference, English only.
 *
 * It only explains a result the rules and fusion layer have already reached; see
 * [com.taar.domain.AssistantPrompt] for what it is told. The model is about 550 MB,
 * too large for the APK, so the technician copies the file to the phone once and
 * picks it here; it is kept in the app's private storage from then on. Nothing is
 * downloaded by the app.
 */
class TaarAssistant(private val context: Context) : Closeable {

    val modelFile = File(context.filesDir, "llm/taar-assistant.task")
    private var llm: LlmInference? = null

    val installed: Boolean get() = modelFile.length() >= MIN_MODEL_BYTES

    /**
     * Copies a picked model file into private storage. Blocking: call off the main
     * thread. Returns a message for the technician, or null on success.
     */
    fun import(uri: Uri, onProgress: (Float) -> Unit): String? {
        val resolver = context.contentResolver
        val (name, size) = resolver.query(uri, null, null, null, null)?.use { c ->
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val s = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) (if (n >= 0) c.getString(n) else "") to (if (s >= 0) c.getLong(s) else -1L)
            else "" to -1L
        } ?: ("" to -1L)
        if (!name.endsWith(".task")) return "That is not a model file. Pick the file ending in .task."
        if (size in 0 until MIN_MODEL_BYTES) return "That file is too small to be the model. Copy it to the phone again."

        close()
        modelFile.parentFile?.mkdirs()
        val tmp = File(modelFile.parentFile, "import.tmp")
        return try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "could not open the file" }
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (size > 0) onProgress((copied.toDouble() / size).toFloat())
                    }
                }
            }
            if (tmp.length() < MIN_MODEL_BYTES) return "The copy was incomplete. Try again."
            modelFile.delete()
            if (!tmp.renameTo(modelFile)) return "Could not save the model."
            null
        } catch (e: Exception) {
            "Copying failed: ${e.message ?: e::class.simpleName}. Check there is about 1 GB free."
        } finally {
            tmp.delete()
        }
    }

    /** Loads the model into memory once. Blocking; returns a message on failure. */
    @Synchronized
    fun load(): String? {
        if (llm != null) return null
        if (!installed) return "No model loaded yet."
        return try {
            llm = LlmInference.createFromOptions(
                context,
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .setMaxTopK(TOP_K)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build(),
            )
            null
        } catch (e: Throwable) {
            "The model could not be loaded: ${e.message ?: e::class.simpleName}"
        }
    }

    /**
     * Streams an answer to [prompt], calling [onText] with the text so far. Blocking
     * until done; returns a message on failure. A fresh session per question, so one
     * answer never leaks into the next.
     */
    fun generate(prompt: String, onText: (String) -> Unit): String? {
        load()?.let { return it }
        val model = llm ?: return "The model is not loaded."
        return try {
            LlmInferenceSession.createFromOptions(
                model,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(TOP_K)
                    .setTemperature(TEMPERATURE)
                    .setRandomSeed(7)
                    // The prompt carries Qwen's chat format itself.
                    .setPromptTemplates(
                        PromptTemplates.builder()
                            .setSystemPrefix("").setSystemSuffix("")
                            .setUserPrefix("").setUserSuffix("")
                            .setModelPrefix("").setModelSuffix("")
                            .build(),
                    )
                    .build(),
            ).use { session ->
                session.addQueryChunk(prompt)
                val text = StringBuilder()
                session.generateResponseAsync { partial, _ ->
                    text.append(partial)
                    onText(text.toString().replace("<|im_end|>", "").trim())
                }.get()
            }
            null
        } catch (e: Throwable) {
            "The assistant failed: ${e.message ?: e::class.simpleName}"
        }
    }

    @Synchronized
    override fun close() {
        runCatching { llm?.close() }
        llm = null
    }

    companion object {
        const val MODEL_NAME = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        private const val MIN_MODEL_BYTES = 100L * 1024 * 1024
        /** The model file was built with a 1,280-token window; prompt and answer share it. */
        private const val MAX_TOKENS = 1_280
        private const val TOP_K = 40
        /** Low: explanations should be plain and repeatable, not creative. */
        private const val TEMPERATURE = 0.3f
    }
}
