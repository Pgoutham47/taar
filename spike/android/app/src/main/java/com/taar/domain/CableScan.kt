package com.taar.domain

/**
 * Cable Scan: where along a cable the anomaly signal is strongest.
 *
 * Built on top of the existing layers. Each scan point is one ordinary capture,
 * turned into [Metrics] and a [Fusion.Analysis] exactly as a measurement is; this
 * file only compares those results across positions.
 *
 * The location comes from the sound, not the magnetic field. Current is the same
 * all along a cable, so its field does not say where anything is -- differences
 * between positions are mostly the phone's distance and angle. An arc's sound is
 * clearest near its source: the 100 Hz pattern stands further above room noise, so
 * the rule's arc score and the model's probability both rise as the phone gets
 * closer. The field is still recorded at every point, because an arc needs current.
 *
 * It finds the strongest anomaly zone, an area to inspect. It does not locate a
 * fault, and positions are relative scan steps, not centimetres.
 */
object CableScan {

    /** One position along the cable, as captured. [strength] is before smoothing. */
    data class Point(
        val index: Int,
        val epochMillis: Long,
        /** As in [Reading.lineConfidence]; see [LineState.contrastOf] for display. */
        val lineConfidence: Double,
        val loadZ: Double,
        val arcZ: Double,
        val aiProbability: Float?,
        val outcome: Fusion.Outcome,
        val quality: Fusion.Quality,
        val motionRadPerS: Double?,
        val audioCaptured: Boolean,
        val strength: Double,
    ) {
        /** Discarded points take no part in finding the zone. */
        val accepted: Boolean get() = quality != Fusion.Quality.POOR && audioCaptured

        val rejectReason: String?
            get() = when {
                !audioCaptured -> "no sound captured"
                quality == Fusion.Quality.POOR -> "phone moved"
                else -> null
            }
    }

    enum class State { DISCARDED, NORMAL, ELEVATED, STRONGEST }

    enum class Verdict { ZONE_FOUND, UNIFORM, ISOLATED_SPIKE, NO_ANOMALY, TOO_FEW_POINTS }

    data class Scored(val point: Point, val smoothed: Double?, val state: State)

    data class Result(
        val points: List<Scored>,
        val verdict: Verdict,
        /** Scan point numbers (1-based) of the zone, and its peak. Null without a zone. */
        val zoneFirst: Int?,
        val zoneLast: Int?,
        val peak: Int?,
        val good: Int,
        val fair: Int,
        val discarded: Int,
        /** Most accepted points showed no current: an arc needs current. */
        val currentAbsent: Boolean,
        val message: String,
        val why: List<String>,
    )

    /** Below this a smoothed point is normal. */
    const val ELEVATED_AT = 0.25

    /** The zone must stand this far above the scan's typical point to count as a zone. */
    const val STANDS_OUT_BY = 0.10

    /** Fewer accepted points than this cannot show a zone against its surroundings. */
    const val MIN_POINTS = 4

    /** A fair-quality point counts for this much of its measured strength. */
    const val FAIR_WEIGHT = 0.5

    /**
     * 0..1 from the two acoustic signals the fusion layer already uses: the rule's
     * arc score against this wire's reference (0 at normal, 1 at the critical
     * level) and the model's probability. Equal weight; the rule alone if the model
     * did not score the capture.
     */
    fun strengthOf(arcZ: Double, aiProbability: Float?, t: Thresholds): Double {
        val rule = (arcZ / t.criticalZ).coerceIn(0.0, 1.0)
        return if (aiProbability == null) rule else 0.5 * rule + 0.5 * aiProbability.toDouble().coerceIn(0.0, 1.0)
    }

    fun analyse(points: List<Point>): Result {
        val accepted = points.filter { it.accepted }
        val good = accepted.count { it.quality == Fusion.Quality.GOOD }
        val fair = accepted.size - good
        val discarded = points.size - accepted.size
        val currentAbsent = accepted.isNotEmpty() &&
            accepted.count { LineState.of(it.lineConfidence) != LineState.FLOWING } * 2 >
            accepted.size

        if (accepted.size < MIN_POINTS) {
            return Result(
                points.map { Scored(it, null, if (it.accepted) State.NORMAL else State.DISCARDED) },
                Verdict.TOO_FEW_POINTS, null, null, null, good, fair, discarded, currentAbsent,
                "Too few usable points to compare positions. Scan again, holding still at each point.",
                listOf("${accepted.size} usable point${if (accepted.size == 1) "" else "s"}; at least " +
                    "$MIN_POINTS are needed to compare one area with its surroundings.") + discardLines(points),
            )
        }

        // Fair points are down-weighted, then each point is compared with its
        // neighbours: the median of three removes a one-point spike, while two
        // adjacent elevated points survive it. The ends use the smaller of the two
        // values they have, so a spike at the edge cannot become a zone either.
        val weighted = accepted.map { if (it.quality == Fusion.Quality.FAIR) it.strength * FAIR_WEIGHT else it.strength }
        val smoothed = DoubleArray(weighted.size) { k ->
            when {
                weighted.size == 1 -> weighted[0]
                k == 0 -> minOf(weighted[0], weighted[1])
                k == weighted.lastIndex -> minOf(weighted[k], weighted[k - 1])
                else -> listOf(weighted[k - 1], weighted[k], weighted[k + 1]).sorted()[1]
            }
        }

        val peakK = smoothed.indices.maxByOrNull { smoothed[it] }!!
        val peakValue = smoothed[peakK]
        val typical = Stats.median(smoothed)
        val rawPeak = weighted.maxOrNull() ?: 0.0

        // The zone: the run of adjacent points around the peak that stay high.
        val floor = maxOf(ELEVATED_AT, peakValue * 0.6)
        var first = peakK
        var last = peakK
        while (first > 0 && smoothed[first - 1] >= floor) first--
        while (last < smoothed.lastIndex && smoothed[last + 1] >= floor) last++

        val verdict = when {
            peakValue >= ELEVATED_AT && peakValue - typical < STANDS_OUT_BY -> Verdict.UNIFORM
            peakValue >= ELEVATED_AT -> Verdict.ZONE_FOUND
            rawPeak >= ELEVATED_AT -> Verdict.ISOLATED_SPIKE
            else -> Verdict.NO_ANOMALY
        }
        val inZone = verdict == Verdict.ZONE_FOUND

        val byIndex = accepted.indices.associate { accepted[it].index to it }
        val scored = points.map { p ->
            val k = byIndex[p.index]
            when {
                k == null -> Scored(p, null, State.DISCARDED)
                inZone && k in first..last -> Scored(p, smoothed[k], State.STRONGEST)
                smoothed[k] >= ELEVATED_AT -> Scored(p, smoothed[k], State.ELEVATED)
                else -> Scored(p, smoothed[k], State.NORMAL)
            }
        }

        val number = { k: Int -> accepted[k].index + 1 }
        val peakPoint = accepted[peakK]
        val message = when (verdict) {
            Verdict.ZONE_FOUND -> "Strongest anomaly signal around point ${number(peakK)}" +
                (if (first != last) " (points ${number(first)}–${number(last)})" else "") +
                ". Further inspection recommended in this section."
            Verdict.UNIFORM -> "The signal was similar along the whole scan, so no single area stands out. " +
                "The source may be beyond the scanned length, or not on this cable."
            Verdict.ISOLATED_SPIKE -> "One point was high but its neighbours were not, so it is treated as " +
                "noise rather than an area. Scan again over that section to check."
            Verdict.NO_ANOMALY -> "No anomaly zone observed along the scanned length."
            Verdict.TOO_FEW_POINTS -> ""
        }

        val why = buildList {
            when (verdict) {
                Verdict.ZONE_FOUND -> {
                    val width = last - first + 1
                    add(if (width > 1) "$width adjacent points (${number(first)}–${number(last)}) stayed high; a " +
                        "single noisy point cannot form a zone." else
                        "Point ${number(peakK)} stayed high after comparison with both neighbours.")
                    add("Peak at point ${number(peakK)}: strength %.2f after smoothing (%.2f measured).".format(
                        peakValue, peakPoint.strength))
                    add("Arc rule at the peak: %+.1f steps from this wire's normal sound.".format(peakPoint.arcZ))
                    peakPoint.aiProbability?.let { add("On-device AI at the peak: arc-like ${(it * 100).toInt()}%.") }
                    add("Typical point on this scan: %.2f. The zone stands %.2f above it.".format(typical, peakValue - typical))
                }
                Verdict.UNIFORM -> add("Highest point %.2f vs typical %.2f: less than %.2f apart."
                    .format(peakValue, typical, STANDS_OUT_BY))
                Verdict.ISOLATED_SPIKE -> add("Highest single point %.2f fell to %.2f when compared with its neighbours."
                    .format(rawPeak, peakValue))
                Verdict.NO_ANOMALY -> add("No point reached the elevated level (%.2f).".format(ELEVATED_AT))
                Verdict.TOO_FEW_POINTS -> Unit
            }
            addAll(discardLines(points))
            if (fair > 0) add("$fair fair-quality point${if (fair == 1) "" else "s"} counted at half strength.")
            if (currentAbsent) add("Most points showed no current in the cable. An arc needs current, so the " +
                "sound may come from something nearby.")
            add("Location comes from the sound, which is clearest near its source. The current is the same " +
                "all along a cable, so the magnetic signal confirms current flows but cannot say where.")
        }

        return Result(scored, verdict, first.takeIf { inZone }?.let(number), last.takeIf { inZone }?.let(number),
            peakK.takeIf { inZone }?.let(number), good, fair, discarded, currentAbsent, message, why)
    }

    private fun discardLines(points: List<Point>): List<String> {
        val bad = points.filter { !it.accepted }
        if (bad.isEmpty()) return emptyList()
        return listOf("${bad.size} point${if (bad.size == 1) "" else "s"} discarded (" +
            bad.groupBy { it.rejectReason }.entries.joinToString(", ") { "${it.value.size} ${it.key}" } +
            "): points ${bad.joinToString(", ") { "${it.index + 1}" }}.")
    }

    const val CAVEAT = "This shows where the anomaly signal was strongest along the scan, not the exact " +
        "location of a fault. Positions are scan steps, not measured distances."
}

/**
 * Cable Scan sessions, stored beside the readings in the same line format and on
 * the same [FileSystem]. Kept apart from [Store] so scan points never enter a
 * circuit's reading history or its thresholds.
 */
class ScanStore(private val fs: FileSystem) {

    data class Session(val circuitId: String, val circuitLabel: String, val epochMillis: Long,
                       val points: List<CableScan.Point>)

    private fun dir(installationId: String, circuitId: String) = "scans/$installationId/$circuitId/"

    fun save(installationId: String, s: Session) {
        val out = StringBuilder()
        out.append(VERSION).append('\n')
        out.append(listOf("session", s.circuitId, s.circuitLabel.replace("\t", " "), s.epochMillis).joinToString(SEP))
            .append('\n')
        for (p in s.points) {
            out.append(listOf("point", p.index, p.epochMillis, p.lineConfidence, p.loadZ, p.arcZ,
                p.aiProbability?.toString() ?: "-", p.outcome.name, p.quality.name,
                p.motionRadPerS?.toString() ?: "-", p.audioCaptured, p.strength).joinToString(SEP)).append('\n')
        }
        fs.write(dir(installationId, s.circuitId) + "${s.epochMillis}.tsv", out.toString())
    }

    /** Newest first. Unreadable files are skipped rather than failing the list. */
    fun list(installationId: String, circuitId: String): List<Session> =
        fs.list(dir(installationId, circuitId)).mapNotNull { runCatching { parse(fs.read(it)!!) }.getOrNull() }
            .sortedByDescending { it.epochMillis }

    private fun parse(text: String): Session? {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.firstOrNull() != VERSION) return null
        val head = lines[1].split(SEP)
        val points = lines.drop(2).map { it.split(SEP) }.filter { it[0] == "point" }.map { c ->
            CableScan.Point(
                index = c[1].toInt(), epochMillis = c[2].toLong(), lineConfidence = c[3].toDouble(),
                loadZ = c[4].toDouble(), arcZ = c[5].toDouble(), aiProbability = c[6].takeIf { it != "-" }?.toFloat(),
                outcome = Fusion.Outcome.valueOf(c[7]), quality = Fusion.Quality.valueOf(c[8]),
                motionRadPerS = c[9].takeIf { it != "-" }?.toDouble(), audioCaptured = c[10].toBoolean(),
                strength = c[11].toDouble(),
            )
        }
        return Session(head[1], head[2], head[3].toLong(), points)
    }

    private companion object {
        const val VERSION = "taar-scan/1"
        const val SEP = "\t"
    }
}
