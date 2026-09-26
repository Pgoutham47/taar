package com.taar.domain

/**
 * What the on-device language model is told, and what is checked in its answer.
 *
 * The model explains; it does not decide. Every fact it may use comes from the
 * fusion result the technician is already looking at, and the instructions forbid
 * inventing numbers, softening a warning, or calling a wire safe or unsafe. A small
 * model can still slip, so [concerns] flags answers that sound certain about safety
 * and the screen shows them beside the answer rather than hiding it.
 *
 * Qwen2.5's chat format is written out here in full, and the runtime's own
 * templates are switched off, so the exact text the model sees is this file's.
 */
object AssistantPrompt {

    val PRESETS = listOf(
        "Explain this result simply.",
        "What should I do next?",
        "Why did Taar show this result?",
    )

    const val SYSTEM = "You are Taar's assistant, helping an electrician understand one reading from the Taar " +
        "phone app. Taar senses current with the phone's magnetometer and listens for sparking with the " +
        "microphone. Answer in plain, simple English in at most 5 short sentences. Use only the facts given " +
        "below; never invent numbers or measurements. Never say a wire or circuit is safe or unsafe: the phone " +
        "cannot prove that. Never contradict, soften or remove a warning. If the facts do not answer the " +
        "question, say you do not know and suggest a qualified electrician."

    /** Kept well inside the model's 1,280-token window, leaving room for the answer. */
    const val MAX_FACTS_CHARS = 1_800

    fun build(analysis: Fusion.Analysis, circuitLabel: String?, breakerRatingA: Double?, question: String): String {
        val q = question.trim().ifEmpty { PRESETS[0] }.take(300)
        return "<|im_start|>system\n$SYSTEM<|im_end|>\n" +
            "<|im_start|>user\nFacts from Taar:\n${facts(analysis, circuitLabel, breakerRatingA)}\n\n" +
            "Question: $q<|im_end|>\n" +
            "<|im_start|>assistant\n"
    }

    fun facts(a: Fusion.Analysis, circuitLabel: String?, breakerRatingA: Double?): String {
        val lines = buildList {
            circuitLabel?.let { add("Circuit: $it" + (breakerRatingA?.let { r -> ", breaker %.0f A".format(r) } ?: "")) }
            add("Result: ${a.outcome.title}. ${a.outcome.summary}")
            add("Evidence strength: ${a.strength.name.lowercase()}. Measurement quality: ${a.quality.name.lowercase()}.")
            a.signals.filter { it.verdict != Fusion.Verdict.UNAVAILABLE }
                .forEach { add("${it.name}: ${it.value}. ${it.detail}") }
            a.why.take(3).forEach { add("Reason: $it") }
            a.conflicts.take(2).forEach { add("Disagreement: $it") }
            a.whatToDo.take(2).forEach { add("Guidance: ${it.text}") }
        }
        val out = StringBuilder()
        for (line in lines) {
            val l = "- " + line.take(220)
            if (out.length + l.length + 1 > MAX_FACTS_CHARS) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(l)
        }
        return out.toString()
    }

    private val CERTAIN = listOf(
        Regex("""\b(is|are|it's|its|be)\s+(completely\s+|perfectly\s+|totally\s+|100%\s+)?(safe|unsafe)\b""",
            RegexOption.IGNORE_CASE),
        Regex("""\bno\s+(risk|danger)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(definitely|certainly|guaranteed)\b""", RegexOption.IGNORE_CASE),
    )
    private val HEDGE = Regex("""\b(not|cannot|can't|whether|if|never)\b""", RegexOption.IGNORE_CASE)

    /**
     * Phrases a small model should not have produced. Empty when none. A match right
     * after a negation ("cannot say whether it is safe") is the model doing its job.
     */
    fun concerns(answer: String): List<String> {
        val found = CERTAIN.any { re ->
            re.findAll(answer).any { m ->
                val before = answer.substring(maxOf(0, m.range.first - 25), m.range.first)
                !HEDGE.containsMatchIn(before)
            }
        }
        return if (found) listOf("This answer sounds certain about safety. Taar cannot prove a wire is safe or " +
            "unsafe; follow the result above and its guidance.") else emptyList()
    }
}
