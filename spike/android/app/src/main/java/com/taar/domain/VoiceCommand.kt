package com.taar.domain

import kotlin.math.pow

/**
 * Turns what the technician said into one of the things Taar can do.
 *
 * Rules, not a model: a handful of commands, each found by its key words, with a
 * circuit found by its name. They answer instantly and the same way every time,
 * which matters when the phone is pressed to a live cable. Anything the rules do
 * not recognise is handed to the on-device language model to rephrase into one of
 * these commands, and that rephrasing is parsed by these same rules -- so the model
 * can only ever choose an action from this list, never invent one.
 *
 * Input is whatever speech recognition produced: lower case, numbers as words, an
 * abbreviation such as "AC" possibly split into "a c".
 */
object VoiceCommand {

    enum class Place { HOME, TOOLS, ASK, HISTORY, BOARD_MAP, HOUSE, GEIGER, CIRCUITS, CALIBRATE, CABLE_SCAN, LIVE, PHONE_CHECK }

    /** The extra modes a voice command can switch on or off. */
    enum class Mode { GEIGER, BOARD_MAP }

    sealed class Command {
        data class Open(val place: Place) : Command()
        data class Measure(val circuitId: String?) : Command()
        data class RecordNormal(val circuitId: String?) : Command()
        data class Select(val circuitId: String) : Command()
        data class SetHours(val hours: Double, val circuitId: String?) : Command()
        data class Cost(val circuitId: String?) : Command()
        data class SetRate(val rupeesPerUnit: Double) : Command()
        data class Toggle(val mode: Mode, val on: Boolean) : Command()
        data class AddCircuit(val name: String) : Command()
        data class StartScan(val live: Boolean) : Command()
        object Next : Command()
        object Previous : Command()
        object ListCircuits : Command()
        object WhichCircuit : Command()
        object Why : Command()
        object WhatToDo : Command()
        object Problems : Command()
        object Repeat : Command()
        object Help : Command()
        /** "Stop listening", "that's all": ends a conversation. */
        object End : Command()
        data class Ask(val question: String) : Command()
    }

    /** A circuit as the parser needs it: an id and the name the technician gave it. */
    data class Name(val id: String, val label: String)

    /**
     * Null when nothing here matches; the caller then asks the language model.
     *
     * Order matters and is deliberate: a phrase is tested for the more specific
     * commands before the general ones its words also fit ("what should I do" is
     * advice, not "what"; "phone check" is a place, not "check" = measure).
     */
    fun parse(heard: String, circuits: List<Name>): Command? {
        val words = normalise(heard)
        if (words.isEmpty()) return null
        val text = " ${words.joinToString(" ")} "
        fun has(vararg phrases: String) = phrases.any { " $it " in text }
        fun hasStem(vararg stems: String) = words.any { w -> stems.any { w.startsWith(it) } }
        fun circuit() = circuitIn(words, circuits)

        // A question for the assistant, spoken as such: "ask what does grey mean".
        if (words.first() == "ask" && words.size > 1) return Command.Ask(words.drop(1).joinToString(" "))

        // Ending a conversation comes first, so "thank you, stop" is never a command.
        if (has("stop listening", "that s all", "thats all", "that is all", "bye", "goodbye", "good bye",
                "thank you", "thanks", "go to sleep", "exit", "quit", "stop", "cancel")) return Command.End

        if (has("again", "repeat", "say that again", "one more time", "pardon", "come again", "read the result",
                "read result", "the result", "what did you find", "what was it")) return Command.Repeat
        if (has("help", "what can i say", "what can you do", "commands", "options")) return Command.Help

        if (has("what should i do", "what do i do", "what to do", "what now", "next step", "what do you suggest",
                "advice", "suggest")) return Command.WhatToDo
        if (has("why", "explain", "reason", "how come")) return Command.Why
        if (hasStem("problem", "fault", "issue", "wrong", "danger", "unsafe", "bad")) return Command.Problems

        hoursIn(words)?.let { (hours, rest) -> return Command.SetHours(hours, circuitIn(rest, circuits)) }
        rateIn(words)?.let { return Command.SetRate(it) }
        if (has("how much", "cost", "costs", "bill", "rupees", "price", "money", "expense")) return Command.Cost(circuit())

        // Switching an extra mode on or off, before opening it.
        val off = has("turn off", "switch off", "disable", "hide", "remove") && !has("breaker")
        val on = has("turn on", "switch on", "enable")
        val geiger = has("geiger", "giger", "gaiger", "geigar", "gyger", "wire finder", "clicker")
        val map = has("board map", "map")
        if ((off || (on && !has("open"))) && (geiger || map)) {
            return Command.Toggle(if (geiger) Mode.GEIGER else Mode.BOARD_MAP, on = !off)
        }

        addedName(words)?.let { return it }

        if (has("next", "next one", "next circuit", "next wire", "move on", "following")) return Command.Next
        if (has("previous", "last one", "go back one", "one before", "before that")) return Command.Previous
        if (has("list", "which circuits", "what circuits", "all circuits", "how many circuits", "circuit names",
                "what are the circuits")) return Command.ListCircuits
        if (has("which circuit", "what circuit", "current circuit", "selected", "where am i", "which wire",
                "what wire")) return Command.WhichCircuit

        // Places whose names contain a command word are checked before that command.
        if (has("phone check", "check the phone", "check phone", "sensor check", "check the sensor")) {
            return Command.Open(Place.PHONE_CHECK)
        }
        if (hasStem("calibrat")) return Command.Open(Place.CALIBRATE)
        val starting = has("start", "begin", "run", "do")
        if (has("cable scan", "scan the cable", "scan cable", "scan along")) {
            return if (starting) Command.StartScan(live = false) else Command.Open(Place.CABLE_SCAN)
        }
        if (has("live view", "physics", "what taar sees", "see what", "signals")) {
            return if (starting) Command.StartScan(live = true) else Command.Open(Place.LIVE)
        }

        // Never on "normal" alone: "is it normal?" must not overwrite the reference.
        if (has("record", "reference", "save normal", "set normal", "record normal", "learn normal")) {
            return Command.RecordNormal(circuit())
        }
        // Never on "okay" or "fine" alone: in a conversation those are replies, not requests.
        if (has("measure", "measuring", "measurement", "check", "test", "reading", "read", "inspect", "scan",
                "is there current", "any current", "current in", "how is", "is it working", "working")) {
            return Command.Measure(circuit())
        }

        if (geiger || has("find the wire", "find wire", "find live", "live wire", "hidden wire", "before i drill",
                "drill")) return Command.Open(Place.GEIGER)
        if (has("house", "house view", "rooms", "whole house")) return Command.Open(Place.HOUSE)
        if (map || has("photo", "board", "picture")) return Command.Open(Place.BOARD_MAP)
        if (has("history", "past", "old readings", "earlier", "log")) return Command.Open(Place.HISTORY)
        if (has("circuits", "boards", "rename")) return Command.Open(Place.CIRCUITS)
        if (has("tools", "settings", "setup", "setting")) return Command.Open(Place.TOOLS)
        if (has("assistant", "ask ai", "chat", "taar ai")) return Command.Open(Place.ASK)
        if (has("home", "go back", "back", "main screen", "main", "start", "close")) return Command.Open(Place.HOME)

        circuit()?.let { return Command.Select(it) }
        return null
    }

    /** "Add a circuit called fridge", "new circuit fan": the name after the command words. */
    private fun addedName(words: List<String>): Command? {
        val text = " ${words.joinToString(" ")} "
        if (!(" add " in text || " new " in text || " create " in text)) return null
        if (listOf("circuit", "switch", "wire", "breaker").none { " $it " in text }) return null
        val skip = setOf("add", "new", "create", "a", "an", "the", "circuit", "switch", "wire", "breaker", "called",
            "named", "name", "for", "please", "it", "is")
        val name = words.filter { it !in skip }.joinToString(" ")
        return if (name.isEmpty()) Command.Open(Place.CIRCUITS)
        else Command.AddCircuit(name.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
    }

    /** "The rate is 9 rupees", "tariff 7 point 7": rupees per unit, between 1 and 30. */
    private fun rateIn(words: List<String>): Double? {
        val text = " ${words.joinToString(" ")} "
        if (listOf(" rate ", " tariff ", " per unit ").none { it in text }) return null
        val i = words.indexOfFirst { it.toDoubleOrNull() != null }
        if (i < 0) return null
        var n = words[i].toDouble()
        if (words.getOrNull(i + 1) == "point") words.getOrNull(i + 2)?.toIntOrNull()?.let { n += it / 10.0.pow(it.toString().length) }
        return n.takeIf { it in 1.0..30.0 }
    }

    /**
     * "Which wires have a problem?", answered from each circuit's latest result,
     * the same results the Board Map colours its dots with. Problems first.
     */
    fun problemsAnswer(circuits: List<Pair<String, BoardMap.Dot>>): String {
        if (circuits.isEmpty()) return "There are no circuits on this board yet."
        fun names(mark: BoardMap.Mark) = circuits.filter { it.second.mark == mark }.map { it.first }
        fun list(n: List<String>) = if (n.size <= 1) n.joinToString() else n.dropLast(1).joinToString(", ") + " and " + n.last()
        val problem = names(BoardMap.Mark.PROBLEM)
        val check = names(BoardMap.Mark.CHECK)
        val unmeasured = names(BoardMap.Mark.NOT_MEASURED)
        val parts = buildList {
            if (problem.isNotEmpty()) add("${list(problem)} ${if (problem.size == 1) "has" else "have"} a problem.")
            if (check.isNotEmpty()) add("${list(check)} ${if (check.size == 1) "needs" else "need"} a check.")
            if (problem.isEmpty() && check.isEmpty()) add("No problems in the latest readings.")
            if (unmeasured.isNotEmpty()) {
                add(if (unmeasured.size == 1) "${unmeasured[0]} is not measured yet." else "${unmeasured.size} circuits are not measured yet.")
            }
        }
        return parts.joinToString(" ")
    }

    /**
     * The prompt that asks the on-device model to rephrase a request the rules did
     * not recognise into one of the commands they do. Whatever it answers is parsed
     * by [parse], so it can only pick from this list.
     */
    fun modelPrompt(heard: String, circuitLabels: List<String>): String {
        val names = circuitLabels.take(20).joinToString(", ") { it.take(30) }.ifEmpty { "none" }
        return "<|im_start|>system\n" +
            "You turn an electrician's spoken request into one command for the Taar app. Reply with exactly one " +
            "line from this list, with a circuit name from the list of circuits where one is meant, and nothing else:\n" +
            "measure <circuit>\nrecord normal <circuit>\nopen board map\nopen house view\nopen geiger mode\nopen history\ngo home\n" +
            "calibrate amps\nwhich wires have a problem\nhow much does <circuit> cost\n" +
            "<circuit> runs <number> hours a day\nsay that again\nask <the question, if it is a question>\n" +
            "Circuits: $names.<|im_end|>\n" +
            "<|im_start|>user\n${heard.trim().take(200)}<|im_end|>\n" +
            "<|im_start|>assistant\n"
    }

    /** The first line of the model's reply, without list marks or quotes. */
    fun firstLine(reply: String): String =
        reply.lineSequence().map { it.trim().trimStart('-', '*', ' ').trim('"', '\'', '`', '.') }
            .firstOrNull { it.isNotEmpty() } ?: ""

    /** What the technician can say, for Help and for when nothing matched. */
    const val EXAMPLES = "You can say: measure kitchen, next circuit, record normal, why, what should I do, " +
        "which wires have a problem, how much does the AC cost, open board map, house view, Geiger mode, or stop listening."

    // ---- words ----

    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)

    /**
     * Lower case, punctuation gone, number words as digits ("twenty four" is 24),
     * and runs of single letters joined ("a c" is "ac"), since that is how speech
     * recognition spells out an abbreviation.
     */
    fun normalise(s: String): List<String> {
        val raw = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val numbers = ArrayList<String>()
        var i = 0
        while (i < raw.size) {
            val t = TENS[raw[i]]
            val u = raw.getOrNull(i + 1)?.let { UNITS[it] }?.takeIf { it in 1..9 }
            when {
                t != null && u != null -> { numbers += (t + u).toString(); i += 2 }
                t != null -> { numbers += t.toString(); i++ }
                raw[i] in UNITS -> { numbers += UNITS.getValue(raw[i]).toString(); i++ }
                else -> { numbers += raw[i]; i++ }
            }
        }
        val out = ArrayList<String>()
        var j = 0
        while (j < numbers.size) {
            var k = j
            while (k < numbers.size && numbers[k].length == 1 && numbers[k][0].isLetter()) k++
            if (k - j >= 2) { out += numbers.subList(j, k).joinToString(""); j = k } else { out += numbers[j]; j++ }
        }
        return out
    }

    /** "8 hours", "two hours a day", "all day": the hours, and the words left once they are removed. */
    private fun hoursIn(words: List<String>): Pair<Double, List<String>>? {
        val text = " ${words.joinToString(" ")} "
        if (" all day " in text || " always " in text || " whole day " in text) {
            return 24.0 to words.filterNot { it in setOf("all", "day", "always", "whole") }
        }
        val at = words.indexOfFirst { it == "hour" || it == "hours" }
        if (at <= 0) return null
        // Recognition hears "two hours" as "to hours" or "do hours" often enough to plan for it.
        val before = words[at - 1].let { if (it in setOf("to", "do", "too")) "2" else it }
        val n = before.toDoubleOrNull()?.takeIf { it > 0 && it <= 24 } ?: return null
        return n to words.filterIndexed { i, _ -> i != at && i != at - 1 }
    }

    /**
     * The words the command recogniser listens for: every word the rules act on,
     * numbers up to 24, and each word of this board's circuit names.
     *
     * Limiting recognition to these made the difference on test recordings: free
     * recognition heard "measure the kitchen wire" as "major the kitchen while" and
     * "open board map" as "the open birdman"; limited, both came out exactly. "to" and
     * "do" are in the list for "go to" and "what do I do"; when one lands before
     * "hours" it is read as two.
     */
    fun vocabulary(circuitLabels: List<String>): List<String> {
        val fixed = ("measure measuring measurement check test reading read inspect record normal reference save set " +
            "open show go to take me start begin stop listening board map photo picture house rooms geiger mode wire wires finder " +
            "find live hidden drill history home back main close tools settings calibrate amps cable scan phone view " +
            "help what can i you say that again repeat which have has a an the is are there any it ok okay fine " +
            "working problem problems wrong fault issue danger how much does do cost costs bill rupees rupee rate " +
            "tariff per unit point runs run for hours hour day all always whole of on off in my select choose " +
            "switch turn enable disable add new called named ask assistant circuit circuits sensor next previous " +
            "last one list selected current why explain should now step thank thanks you bye goodbye exit cancel " +
            "that's all please " +
            UNITS.keys.joinToString(" ") + " " + TENS.keys.joinToString(" ")).split(" ")
        val names = circuitLabels.flatMap { label ->
            label.lowercase().replace(Regex("[^a-z ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
                // An abbreviation is said letter by letter: "AC" is "a c".
                .flatMap { w -> if (w.length in 2..3 && w.none { it in "aeiou" } || w == "ac") w.map { it.toString() } else listOf(w) }
        }
        return (fixed + names).filter { it.isNotEmpty() && "'" !in it }.distinct()
    }

    /**
     * True when an utterance is most likely not meant for Taar: people talking
     * nearby, or a stray noise. A conversation listens continuously, so it must not
     * act on either -- or keep answering "sorry" to them.
     *
     * Measured on test recordings: nearby chatter came out of the free recogniser
     * as 7 or more words with at most half of them command words ("I think the
     * laptop needs charging before the demo"); real commands that long still had
     * 57% or more. A noise came out as one or two words with nothing the command
     * recogniser could place ("boom", "some").
     */
    fun looksLikeChatter(command: String, free: String, vocabulary: Collection<String>, circuits: List<Name>): Boolean {
        val words = normalise(free)
        // Noise decoded against a small vocabulary comes out as some word from it ("named",
        // "the"), so the test is whether either transcript makes a command, not whether it has words.
        val placed = parse(command.replace("[unk]", " "), circuits) != null || parse(free, circuits) != null
        if (words.size <= 2 && !placed) return true
        if (words.size >= 12) return true
        if (words.size < 7) return false
        val known = vocabulary.toSet()
        return words.count { it in known || it.toIntOrNull() != null }.toDouble() / words.size <= 0.5
    }

    /** Words in a circuit's name that say nothing about which circuit it is. */
    private val FILLER = setOf("the", "a", "an", "of", "my", "wire", "circuit", "line", "cable", "socket", "plug", "point")

    /**
     * The circuit named in [words], or null. A name matches when most of its
     * telling words were said ("left plug" finds "Left black plug"); the best
     * match wins, and a tie between two names is no match rather than a guess.
     */
    private fun isAbbreviation(w: String) = w.length in 2..3 && w.all { it.isLetter() } && (w.none { it in "aeiou" } || w == "ac")

    fun circuitIn(words: List<String>, circuits: List<Name>): String? {
        val said = words.toSet()
        val scored = circuits.mapNotNull { c ->
            val name = normalise(c.label)
            val telling = name.filter { it !in FILLER }.ifEmpty { name }
            // A spoken abbreviation often loses its first letter: "AC" is heard as "c".
            val hit = telling.count { it in said || (isAbbreviation(it) && it.last().toString() in said) }
            if (hit == 0) null else Triple(c.id, hit.toDouble() / telling.size, hit)
        }.filter { it.second >= 0.5 }
        val best = scored.maxWithOrNull(compareBy({ it.second }, { it.third })) ?: return null
        val tied = scored.count { it.second == best.second && it.third == best.third }
        return if (tied > 1) null else best.first
    }
}
