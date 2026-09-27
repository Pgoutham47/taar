package com.taar.domain

/**
 * Board Map: a photo of the distribution board with a dot on each breaker,
 * coloured by that circuit's latest result.
 *
 * Only positions and results live here; the photo itself is a file the Android
 * layer keeps. Positions are fractions of the photo's width and height, so they
 * survive the photo being shown at any size.
 *
 * A dot shows what was last observed on that circuit and when. It is never a
 * statement that a circuit is safe: grey means no current was seen, not that the
 * wire is dead.
 */
object BoardMap {

    /** Where a circuit's breaker is on the photo: 0..1 across and down. */
    data class Pin(val x: Double, val y: Double) {
        init {
            require(x in 0.0..1.0 && y in 0.0..1.0) { "pin must lie on the photo" }
        }
    }

    /** The result a measurement reached, kept so the map shows what the technician saw. */
    data class LastResult(val epochMillis: Long, val outcome: Fusion.Outcome, val line: LineState)

    data class Map(
        val pins: kotlin.collections.Map<String, Pin> = emptyMap(),
        val results: kotlin.collections.Map<String, LastResult> = emptyMap(),
    )

    /** What a dot shows. Ordered from most to least urgent, for the summary line. */
    enum class Mark(val label: String) {
        PROBLEM("Problem"),
        CHECK("Check"),
        UNCLEAR("Measure again"),
        LIVE("Current flowing"),
        OFF("No current seen"),
        NOT_MEASURED("Not measured"),
    }

    data class Dot(val mark: Mark, val epochMillis: Long?, val title: String?)

    /**
     * The dot for one circuit, from its last measured result, or failing that its
     * latest reading. A reading alone says only whether current was flowing; the
     * result also says whether anything was wrong.
     */
    fun dotOf(result: LastResult?, latest: Reading?): Dot {
        val newestReading = latest?.takeIf { r -> result == null || r.epochMillis > result.epochMillis }
        if (result != null && newestReading == null) {
            val mark = when (result.outcome.tone) {
                Fusion.Tone.CRITICAL -> Mark.PROBLEM
                Fusion.Tone.WARNING, Fusion.Tone.ADVISORY -> Mark.CHECK
                Fusion.Tone.UNRELIABLE -> Mark.UNCLEAR
                Fusion.Tone.NORMAL -> markOf(result.line)
            }
            return Dot(mark, result.epochMillis, result.outcome.title)
        }
        if (newestReading != null) {
            return Dot(markOf(LineState.of(newestReading.lineConfidence)), newestReading.epochMillis, null)
        }
        return Dot(Mark.NOT_MEASURED, null, null)
    }

    private fun markOf(line: LineState) = when (line) {
        LineState.FLOWING -> Mark.LIVE
        LineState.NONE -> Mark.OFF
        LineState.UNCLEAR -> Mark.UNCLEAR
    }

    /**
     * The order dots are numbered in: circuits on the photo first, in the order
     * their dots were placed, then the rest in board order. The first switch
     * tapped is 1, however many circuits the board already had. Moving a dot
     * keeps its number.
     */
    fun order(circuitIds: List<String>, pins: kotlin.collections.Map<String, Pin>): List<String> =
        pins.keys.filter { it in circuitIds } + circuitIds.filter { it !in pins }

    /** "2 current flowing · 1 problem", most urgent first, only marks that occur. */
    fun summary(dots: Collection<Dot>): String =
        Mark.entries.mapNotNull { m ->
            dots.count { it.mark == m }.takeIf { it > 0 }?.let { "$it ${m.label.lowercase()}" }
        }.joinToString(" · ")
}

class BoardMapStore(private val fs: FileSystem) {

    private fun path(installationId: String) = "boardmap/$installationId.tsv"

    fun load(installationId: String): BoardMap.Map {
        val text = fs.read(path(installationId)) ?: return BoardMap.Map()
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.firstOrNull() != VERSION) return BoardMap.Map()
        val pins = linkedMapOf<String, BoardMap.Pin>()
        val results = linkedMapOf<String, BoardMap.LastResult>()
        for (line in lines.drop(1)) {
            val f = line.split(SEP)
            // One bad row is skipped, never the whole map.
            runCatching {
                when (f[0]) {
                    "pin" -> pins[f[1]] = BoardMap.Pin(f[2].toDouble(), f[3].toDouble())
                    "result" -> results[f[1]] = BoardMap.LastResult(
                        f[2].toLong(), Fusion.Outcome.valueOf(f[3]), LineState.valueOf(f[4]),
                    )
                }
            }
        }
        return BoardMap.Map(pins, results)
    }

    fun save(installationId: String, map: BoardMap.Map) {
        val out = StringBuilder().append(VERSION).append('\n')
        for ((id, p) in map.pins) out.append(listOf("pin", id, p.x, p.y).joinToString(SEP)).append('\n')
        for ((id, r) in map.results) {
            out.append(listOf("result", id, r.epochMillis, r.outcome.name, r.line.name).joinToString(SEP)).append('\n')
        }
        fs.write(path(installationId), out.toString())
    }

    fun placePin(installationId: String, circuitId: String, pin: BoardMap.Pin) {
        val m = load(installationId)
        save(installationId, m.copy(pins = m.pins + (circuitId to pin)))
    }

    fun removePin(installationId: String, circuitId: String) {
        val m = load(installationId)
        save(installationId, m.copy(pins = m.pins - circuitId))
    }

    fun recordResult(installationId: String, circuitId: String, result: BoardMap.LastResult) {
        val m = load(installationId)
        save(installationId, m.copy(results = m.results + (circuitId to result)))
    }

    private companion object {
        const val VERSION = "taar-boardmap/1"
        const val SEP = "\t"
    }
}
