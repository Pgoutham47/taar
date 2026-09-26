package com.taar.domain

/**
 * What the general assistant knows about Taar: short notes, each on one topic,
 * written from what the app actually does.
 *
 * A 0.5B model knows nothing about this app and will invent features if asked
 * cold. So each question is answered from the few notes that match it, found by
 * plain keyword overlap, and the notes used are shown under the answer. Nothing
 * here is learned; editing a note changes the answer.
 */
object ProductKnowledge {

    data class Note(val title: String, val keywords: String, val text: String)

    val notes = listOf(
        Note("What Taar is", "taar app what is overview purpose who for electrician",
            "Taar turns a phone into a non-contact electrical triage tool. Held against a cable for 3 seconds, it " +
                "tells whether current is flowing, how much compared with that wire's normal, and whether the sound " +
                "shows a sparking pattern. It is for electricians and technicians, works fully offline, and is a " +
                "triage aid, not a certified instrument."),
        Note("How current is detected", "current detect magnetometer magnetic field 50 hz compass contrast flowing",
            "A wire carrying current makes a magnetic field that wiggles 50 times a second, the mains frequency in " +
                "India. The phone's magnetometer (compass sensor) looks for that 50 Hz wiggle and compares it with " +
                "nearby frequencies. Below 8 times room noise means no current, above 13.5 times means current is " +
                "flowing, and in between is unclear. These limits came from real kettle tests."),
        Note("How sparking is detected", "spark sparking arc arcing loose connection sound microphone 100 hz detect",
            "A loose connection that sparks re-ignites twice every mains cycle, so its noise pulses 100 times a " +
                "second. Taar records sound with the microphone, filters the 4 to 16 kHz band where arc noise lives, " +
                "and measures how strongly it pulses at 100 Hz. A rule compares this with the wire's reference, and " +
                "an on-device AI model gives a second opinion."),
        Note("The on-device arc model", "ai model tflite neural network mlp trained training arc second opinion accuracy",
            "The arc model is a small neural network (an MLP, 16.6 KB) running on the phone with TensorFlow Lite. It " +
                "reads 89 numbers describing the sound and gives the chance that it is arc-like. It was trained on " +
                "recordings made on the phone, with simulated arcs played through a speaker. On a session it never " +
                "saw, it cut false alarms from about 10% to under 1%. It is a second opinion; the rules decide."),
        Note("Phone check", "phone check sensor speed rate noise microphone permission first setup",
            "The phone check is a 3-second test done once. It measures how fast the magnetometer really reads " +
                "(about 105 times a second on the iQOO), how much the readings shake on their own (background " +
                "noise), and whether the microphone is allowed. Exactly 100 readings a second would miss the 50 Hz " +
                "signal, so Taar picks a safe speed. Put the phone flat on a table away from chargers while it runs."),
        Note("Boards and circuits", "board circuit breaker rating add change select wire panel amps limit",
            "A board is one distribution panel; each circuit is one wire from it, controlled by one breaker. Add " +
                "circuits in Circuits and pick the one you are measuring. Enter the breaker rating printed on it, " +
                "for example 16 for a B16 breaker: Taar uses it to warn when the current approaches that limit."),
        Note("Recording a reference", "reference baseline normal record first how why",
            "A reference is what normal looks like for one wire. Taar takes 3 quick captures while the wire is in " +
                "its normal state and remembers them. Every later measurement is compared with this reference. " +
                "Record it with the phone in the same spot and position you will measure in, and keep it still."),
        Note("Taking a measurement", "measure measurement how take scan start 3 seconds supply off on steps",
            "Pick the circuit, tap Measure, and say whether the supply is on or switched off. Press the phone flat " +
                "against the cable and keep it still for 3 seconds. Taar then shows one combined result. If you " +
                "switched the breaker off, Taar warns you if current is still flowing."),
        Note("Reading the result", "result analysis evidence strength why unified fusion what does it mean",
            "After a measurement Taar shows one result that combines every signal: current in the cable, current " +
                "compared with normal, the arc signal, the comparison with the reference, and measurement quality. " +
                "Evidence strength counts how many signals agree. Tap Why to see every signal and where they " +
                "disagree."),
        Note("Warnings", "warning warnings arcing overload live switched off breaker high load unreliable rules",
            "Taar has six warnings: possible arcing, current on a circuit you switched off, load near the breaker " +
                "limit, load higher than usual, switched-off circuit not confirmed, and unreliable reading. Each " +
                "shows why it fired and what to do, in English and Telugu. A warning appears only when all its " +
                "conditions are true."),
        Note("Measurement quality", "quality moved movement gyroscope unreliable still steady hand hold",
            "Taar checks each reading's quality. The gyroscope notices if the phone turned during the 3 seconds; " +
                "if it did, the reading is marked unreliable and not interpreted. A steady hand is fine. Unclear " +
                "current signal, no sound captured, or a processed microphone lower the quality to fair."),
        Note("Amps calibration", "amps ampere calibrate calibration kettle watts current estimate",
            "Without calibration Taar shows how the current compares with normal, not amperes. To see amps, " +
                "calibrate once with an appliance of known power, such as a 1500 W kettle: measure with it off, then " +
                "on. Amps are then shown for that circuit."),
        Note("Teach Taar", "teach label normal warning fault feedback learn thresholds",
            "After a reading you can mark it Normal, Warning or Fault. Taar uses these labels to tune the warning " +
                "limits for that circuit, and a small on-device classifier learns which readings look like the ones " +
                "you marked. Readings from practice setups can be excluded."),
        Note("History", "history past readings saved list",
            "Every measurement is saved on the phone with its time, circuit and result. Open History to see them."),
        Note("Cable Scan", "cable scan along where location localize zone points slide",
            "Cable Scan finds where along a cable the sparking signal is strongest. Hold still for 3 seconds at a " +
                "point, slide one hand-width, and repeat. Taar compares the points, ignores single noisy points and " +
                "moved captures, and shows the strongest zone to inspect. It uses sound, because current is the same " +
                "all along a cable. It shows an area to inspect, not an exact fault."),
        Note("See What Taar Sees", "live physics view see what taar sees waveform pipeline visual demo",
            "See What Taar Sees is a live view of the whole chain: the magnetic signal folded onto one 50 Hz cycle, " +
                "the sound and the envelope the arc detector uses, the spectrum the AI reads, each processing step, " +
                "and the final status. It updates every 3 seconds from real captures and saves nothing."),
        Note("Ask Taar AI", "ask ai assistant qwen language model llm chat explain questions",
            "Ask Taar is an assistant running on the phone: Qwen2.5, a 0.5-billion-parameter language model, in " +
                "English. Under each result it explains that reading from Taar's own evidence. In the Ask tab it " +
                "answers questions about the app from built-in notes. It can be wrong, and it never decides a " +
                "result or calls a wire safe. The model file is loaded once from the phone's storage."),
        Note("Privacy and offline", "privacy offline internet data upload cloud server account permission",
            "Taar works fully offline. It has no internet permission, no account and no server; readings, models " +
                "and history stay on the phone. The only permission it asks for is the microphone, used to listen " +
                "for sparking."),
        Note("Limits and safety", "safe unsafe voltage limitation limits certified trust accurate danger touch",
            "Taar senses current, not voltage: a switched-off appliance on a live wire shows no current. Always " +
                "use a voltage tester before touching anything. Taar cannot prove a wire is safe or unsafe; it is a " +
                "triage aid, not a certified instrument, and does not replace a licensed electrician. The arc model " +
                "was trained on simulated arcs."),
        Note("Tips for good readings", "tips good reading accurate better noise charger metal distance",
            "Press the phone flat against the cable, within a few centimetres. Keep it still for 3 seconds. Keep " +
                "chargers, adapters and other live cables away, because they also make a 50 Hz field. Record the " +
                "reference in the same spot you measure."),
        Note("Colours", "colour color green yellow orange red grey meaning",
            "Green means normal or no anomaly observed. Yellow means elevated or possible anomaly. Orange means " +
                "strong. Red means a possible anomaly or a critical warning. Grey means unreliable or not available."),
        Note("Troubleshooting", "problem not working unclear failed error fix help trouble",
            "Unclear: keep the phone still on the cable and measure again. Unreliable: the phone moved; hold it " +
                "still. Phone check failed: move away from chargers and metal and run it again. No sound captured: " +
                "allow the microphone in the phone's settings."),
    )

    /** Notes used for a question: the best matches, never more than [MAX_NOTES]. */
    fun lookup(question: String): List<Note> {
        val q = terms(question)
        if (q.isEmpty()) return listOf(notes[0])
        val scored = notes.map { n ->
            val title = terms(n.title)
            val keys = terms(n.keywords)
            val body = terms(n.text)
            n to q.sumOf { t -> (if (t in title) 3 else 0) + (if (t in keys) 3 else 0) + (if (t in body) 1 else 0) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        return if (scored.isEmpty()) listOf(notes[0]) else scored.take(MAX_NOTES).map { it.first }
    }

    const val MAX_NOTES = 3

    private val STOP = setOf("a", "an", "the", "is", "are", "it", "its", "to", "of", "in", "on", "and", "or", "for",
        "do", "does", "how", "what", "why", "can", "i", "my", "me", "you", "your", "this", "that", "with", "be",
        "taar", "when", "where", "which", "will", "if", "at", "by", "as", "from", "about", "there", "we", "us")

    private fun terms(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in STOP }.map(::stem).toSet()

    private fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 4 && w.endsWith("es") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") -> w.dropLast(1)
        else -> w
    }
}
