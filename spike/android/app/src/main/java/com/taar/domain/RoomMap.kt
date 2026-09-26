package com.taar.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Room 3D Scan: Taar's existing measurements placed at the 3D points where they
 * were taken, and a map of the measured electrical activity built from them.
 *
 * A presentation layer. Each point is an ordinary capture interpreted exactly as a
 * measurement is -- [Metrics], the rules, [Fusion] -- and this file only arranges
 * those results in space. It adds no detection of its own.
 *
 * What the map can and cannot say:
 *  - Activity is the measured 50 Hz magnetic signal where the phone touched a
 *    surface. The field of a cable falls away within centimetres, so a point says
 *    what is directly under it, and nothing about the wall between points.
 *  - The heat around points is a local average limited to about a hand-span, so
 *    sparse points are never smeared into a room-wide field.
 *  - The optional path joining strong points is an inferred sketch of where
 *    activity was measured, never a wiring diagram.
 *  - A red zone needs two or more nearby readings that the fusion layer marked as a
 *    possible anomaly. One reading can never make a red region.
 */
object RoomMap {

    data class Vec3(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
        operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
        fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
        fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun length() = sqrt(dot(this))
        fun distanceTo(o: Vec3) = (this - o).length()
        fun unit(): Vec3 = length().let { if (it > 0) this * (1 / it) else this }
    }

    /** A surface ARCore found: its outline in world coordinates, metres. */
    data class Plane(val id: String, val vertical: Boolean, val polygon: List<Vec3>)

    enum class State { NORMAL, ELEVATED, STRONG, POSSIBLE_ANOMALY, DISCARDED }

    /** One measurement at one pinned 3D point. */
    data class Point(
        val id: Int,
        val position: Vec3,
        val epochMillis: Long,
        val lineConfidence: Double,
        val fieldAmplitudeUt: Double,
        val referenceFieldUt: Double?,
        val currentA: Double?,
        val arcZ: Double,
        val aiProbability: Float?,
        val outcome: Fusion.Outcome,
        val strength: Fusion.Strength,
        val quality: Fusion.Quality,
        /** The fusion layer's reasons, for the tap-to-inspect "Why?". */
        val why: List<String>,
    ) {
        /** A moved phone's reading takes no part in the map. */
        val accepted: Boolean get() = quality != Fusion.Quality.POOR
        val uncertain: Boolean get() = quality == Fusion.Quality.FAIR
        val contrast: Double get() = LineState.contrastOf(lineConfidence)

        /**
         * 0..1 on a log scale of how far the 50 Hz signal stands above noise: 0 at the
         * no-current threshold (8x), 1 at 60x, the strong end of the kettle test.
         */
        val activity: Double get() = activityOf(contrast)

        val state: State
            get() = when {
                !accepted -> State.DISCARDED
                // Critical fusion results only. A live socket is "more current than a
                // quiet reference" by nature, so the warning tier would paint every
                // socket red.
                outcome.tone == Fusion.Tone.CRITICAL -> State.POSSIBLE_ANOMALY
                activity >= STRONG_AT -> State.STRONG
                activity >= ELEVATED_AT -> State.ELEVATED
                else -> State.NORMAL
            }
    }

    data class Zone(
        val center: Vec3,
        val radius: Double,
        val pointIds: List<Int>,
        val strength: Fusion.Strength,
        val evidence: List<String>,
    )

    data class Map(
        val points: List<Point>,
        val planes: List<Plane>,
        /** Each accepted point's activity averaged with its close neighbours. */
        val smoothed: kotlin.collections.Map<Int, Double>,
        val zones: List<Zone>,
        /** Inferred sketch joining strong points. Never a wiring diagram. */
        val path: List<Pair<Vec3, Vec3>>,
        val enough: Boolean,
        /** Share of scanned surface within reach of an accepted point; null without surfaces. */
        val coverage: Double?,
        val message: String,
    )

    const val ELEVATED_AT = 0.25   // ~13x: just above the current-flowing threshold
    const val STRONG_AT = 0.6      // ~27x: the kettle test's range
    const val MIN_POINTS = 6
    /** Heat spreads this far around a point, metres. About a hand-span. */
    const val SIGMA_M = 0.25
    /** Readings this close together can form one zone. */
    const val ZONE_LINK_M = 0.6
    /** Strong points further apart than this are not joined by the path. */
    const val PATH_MAX_EDGE_M = 1.5
    /** A surface sample is covered when an accepted point is this close. */
    const val COVER_M = 0.75

    fun activityOf(contrast: Double): Double {
        if (!contrast.isFinite()) return 1.0
        return ((ln(maxOf(contrast, 1e-6)) - ln(8.0)) / (ln(60.0) - ln(8.0))).coerceIn(0.0, 1.0)
    }

    /** Local weighted average of activity at [p]; the weight says how much data backs it. */
    fun fieldAt(p: Vec3, points: List<Point>): Pair<Double, Double> {
        var num = 0.0
        var den = 0.0
        for (q in points) {
            if (!q.accepted) continue
            val d = p.distanceTo(q.position)
            if (d > 3 * SIGMA_M) continue
            val w = exp(-d * d / (2 * SIGMA_M * SIGMA_M)) * (if (q.uncertain) 0.5 else 1.0)
            num += w * q.activity
            den += w
        }
        return (if (den > 0) num / den else 0.0) to den
    }

    fun build(points: List<Point>, planes: List<Plane>): Map {
        val accepted = points.filter { it.accepted }
        val enough = accepted.size >= MIN_POINTS
        val smoothed = accepted.associate { it.id to fieldAt(it.position, accepted).first }
        val zones = zonesOf(accepted)
        val path = pathOf(accepted.filter { it.activity >= STRONG_AT || it.state == State.POSSIBLE_ANOMALY })
        val coverage = coverageOf(planes, accepted)
        val message = when {
            !enough -> "Not enough measurements to build a reliable spatial map. ${accepted.size} usable of " +
                "$MIN_POINTS needed."
            zones.isNotEmpty() -> "${zones.size} possible anomaly zone${if (zones.size == 1) "" else "s"}. " +
                "Further inspection recommended."
            accepted.any { it.state == State.POSSIBLE_ANOMALY } -> "A single reading showed a possible anomaly. " +
                "One reading is not a zone: measure around it again."
            accepted.any { it.activity >= ELEVATED_AT } -> "Electrical activity measured at the highlighted points. " +
                "No anomaly zone observed."
            else -> "Little electrical activity measured at the scanned points."
        }
        return Map(points, planes, smoothed, zones, path, enough, coverage, message)
    }

    /** Clusters of possible-anomaly readings, linked within [ZONE_LINK_M]. Singletons are not zones. */
    private fun zonesOf(accepted: List<Point>): List<Zone> {
        val red = accepted.filter { it.state == State.POSSIBLE_ANOMALY }
        val unvisited = red.toMutableList()
        val zones = mutableListOf<Zone>()
        while (unvisited.isNotEmpty()) {
            val cluster = mutableListOf(unvisited.removeAt(0))
            var grew = true
            while (grew) {
                grew = false
                val near = unvisited.filter { u -> cluster.any { it.position.distanceTo(u.position) <= ZONE_LINK_M } }
                if (near.isNotEmpty()) { cluster += near; unvisited -= near.toSet(); grew = true }
            }
            if (cluster.size < 2) continue
            val center = cluster.map { it.position }.reduce { a, b -> a + b } * (1.0 / cluster.size)
            val radius = cluster.maxOf { it.position.distanceTo(center) } + SIGMA_M
            val good = cluster.count { it.quality == Fusion.Quality.GOOD }
            val strength = when {
                cluster.size >= 3 && good >= 2 -> Fusion.Strength.HIGH
                good >= 1 -> Fusion.Strength.MODERATE
                else -> Fusion.Strength.LOW
            }
            val arcs = cluster.count { (it.aiProbability ?: 0f) >= 0.5f }
            zones += Zone(
                center, radius, cluster.map { it.id }, strength,
                buildList {
                    add("${cluster.size} nearby readings marked as a possible anomaly by the existing sensor fusion.")
                    cluster.map { it.outcome.title }.distinct().forEach { add("Fusion result: $it") }
                    add("Magnetic signal up to %.0f× room noise.".format(cluster.maxOf { it.contrast }))
                    if (arcs > 0) add("On-device AI heard an arc-like pattern in $arcs of ${cluster.size}.")
                    add("Measurement quality: $good good, ${cluster.size - good} fair.")
                },
            )
        }
        return zones
    }

    /** Minimum spanning tree over strong points, edges no longer than [PATH_MAX_EDGE_M]. */
    private fun pathOf(strong: List<Point>): List<Pair<Vec3, Vec3>> {
        if (strong.size < 2) return emptyList()
        val inTree = mutableSetOf(0)
        val edges = mutableListOf<Pair<Vec3, Vec3>>()
        while (inTree.size < strong.size) {
            var best: Triple<Int, Int, Double>? = null
            for (i in inTree) for (j in strong.indices) {
                if (j in inTree) continue
                val d = strong[i].position.distanceTo(strong[j].position)
                if (best == null || d < best.third) best = Triple(i, j, d)
            }
            val (i, j, d) = best ?: break
            inTree += j
            if (d <= PATH_MAX_EDGE_M) edges += strong[i].position to strong[j].position
        }
        return edges
    }

    /** Samples each surface on a 25 cm grid and counts how much lies near a measurement. */
    fun coverageOf(planes: List<Plane>, accepted: List<Point>): Double? {
        var total = 0
        var covered = 0
        for (plane in planes) {
            val poly = plane.polygon
            if (poly.size < 3) continue
            val origin = poly[0]
            val u = (poly[1] - origin).unit()
            val normal = u.cross((poly[2] - origin)).unit()
            if (normal.length() == 0.0) continue
            val v = normal.cross(u).unit()
            val flat = poly.map { val d = it - origin; d.dot(u) to d.dot(v) }
            val (u0, u1) = flat.minOf { it.first } to flat.maxOf { it.first }
            val (v0, v1) = flat.minOf { it.second } to flat.maxOf { it.second }
            var a = u0
            while (a <= u1) {
                var b = v0
                while (b <= v1) {
                    if (inside(a, b, flat)) {
                        total++
                        val world = origin + u * a + v * b
                        if (accepted.any { it.position.distanceTo(world) <= COVER_M }) covered++
                    }
                    b += 0.25
                }
                a += 0.25
            }
        }
        return if (total == 0) null else covered.toDouble() / total
    }

    private fun inside(x: Double, y: Double, poly: List<Pair<Double, Double>>): Boolean {
        var c = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val (xi, yi) = poly[i]
            val (xj, yj) = poly[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    const val CAVEAT = "Measured electrical activity at the points you touched, placed in a camera-built model " +
        "of the room. Not an X-ray of the walls and not a wiring diagram; it does not show whether a circuit " +
        "is safe. Where a zone is marked, further inspection is recommended."
}

/** Room scans, stored offline in the same line format as the other stores. */
class RoomStore(private val fs: FileSystem) {

    data class Session(val id: String, val name: String, val epochMillis: Long,
                       val planes: List<RoomMap.Plane>, val points: List<RoomMap.Point>)

    fun save(s: Session) {
        val out = StringBuilder(VERSION).append('\n')
        out.append(listOf("room", s.id, clean(s.name), s.epochMillis).joinToString(SEP)).append('\n')
        for (p in s.planes) {
            out.append(listOf("plane", clean(p.id), p.vertical, p.polygon.joinToString(" ") { v3(it) }).joinToString(SEP))
                .append('\n')
        }
        for (p in s.points) {
            out.append(listOf("point", p.id, v3(p.position), p.epochMillis, p.lineConfidence, p.fieldAmplitudeUt,
                p.referenceFieldUt ?: "-", p.currentA ?: "-", p.arcZ, p.aiProbability ?: "-", p.outcome.name,
                p.strength.name, p.quality.name, p.why.joinToString(" | ") { clean(it).replace("|", "/") })
                .joinToString(SEP)).append('\n')
        }
        fs.write("rooms/${s.id}.tsv", out.toString())
    }

    /** Newest first; unreadable files are skipped. */
    fun list(): List<Session> =
        fs.list("rooms/").mapNotNull { runCatching { parse(fs.read(it)!!) }.getOrNull() }
            .sortedByDescending { it.epochMillis }

    private fun parse(text: String): Session? {
        val rows = text.lines().filter { it.isNotBlank() }
        if (rows.firstOrNull() != VERSION) return null
        val head = rows[1].split(SEP)
        val planes = mutableListOf<RoomMap.Plane>()
        val points = mutableListOf<RoomMap.Point>()
        for (r in rows.drop(2).map { it.split(SEP) }) when (r[0]) {
            "plane" -> planes += RoomMap.Plane(r[1], r[2].toBoolean(),
                r[3].split(" ").filter { it.isNotBlank() }.map(::parseV3))
            "point" -> points += RoomMap.Point(
                id = r[1].toInt(), position = parseV3(r[2]), epochMillis = r[3].toLong(),
                lineConfidence = r[4].toDouble(), fieldAmplitudeUt = r[5].toDouble(),
                referenceFieldUt = r[6].takeIf { it != "-" }?.toDouble(), currentA = r[7].takeIf { it != "-" }?.toDouble(),
                arcZ = r[8].toDouble(), aiProbability = r[9].takeIf { it != "-" }?.toFloat(),
                outcome = Fusion.Outcome.valueOf(r[10]), strength = Fusion.Strength.valueOf(r[11]),
                quality = Fusion.Quality.valueOf(r[12]),
                why = r.getOrNull(13)?.split(" | ")?.filter { it.isNotBlank() } ?: emptyList(),
            )
        }
        return Session(head[1], head[2], head[3].toLong(), planes, points)
    }

    private fun v3(v: RoomMap.Vec3) = "${v.x},${v.y},${v.z}"
    private fun parseV3(s: String) = s.split(",").let { RoomMap.Vec3(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }
    private fun clean(s: String) = s.replace("\t", " ").replace("\n", " ")

    private companion object {
        const val VERSION = "taar-room/1"
        const val SEP = "\t"
    }
}
