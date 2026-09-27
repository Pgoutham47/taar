package com.taar.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * House View: the board's circuits drawn as the rooms of a house, each room lit by
 * the current last measured on its circuit, with the wire from the board to it
 * running at the speed of that current.
 *
 * The rooms are real in one sense only: they are the circuits the technician named
 * at the board. Where a room sits in the picture, and the path drawn to it, are a
 * diagram in the way a metro map is a diagram. The app has no idea where a cable
 * runs inside a wall, and the picture must never suggest it does. Everything that
 * moves or glows comes from a reading; an unmeasured room stays dark.
 *
 * Geometry lives here rather than in the screen so the layout, the wires, the
 * camera and the hit-test can be checked without a device. World units: x runs to
 * the right, y towards the viewer, z up; a room is [CELL_W] wide, one unit deep and
 * [WALL_HEIGHT] tall. A [Camera] turns, tilts and zooms the view, and [project]
 * puts a point on the screen with a little perspective so the house reads as solid.
 */
object HouseView {

    /**
     * What kind of load a circuit's name suggests. Decides a one-word caption under
     * the room's name and nothing else: it is a hint from the label, not a
     * measurement, so it never colours or animates anything.
     */
    enum class Kind(val caption: String, private vararg val words: String) {
        COOLING("Cooling", "ac", "a c", "air", "cooler", "fridge", "refrigerator", "freezer"),
        HEATING("Heating", "geyser", "heater", "boiler", "iron", "kettle", "oven", "induction", "microwave"),
        KITCHEN("Kitchen", "kitchen", "cook", "mixer", "grinder"),
        MOTOR("Motor", "pump", "motor", "lift", "borewell", "washing", "machine", "compressor"),
        LIGHTS("Lights", "light", "lights", "lamp", "fan", "fans", "tube", "bulb"),
        SOCKETS("Sockets", "plug", "plugs", "socket", "sockets", "point", "points", "power", "tv", "computer"),
        ROOM("Room");

        companion object {
            fun of(label: String): Kind {
                val words = label.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
                val text = " ${words.joinToString(" ")} "
                return entries.firstOrNull { k -> k.words.any { " $it " in text } } ?: ROOM
            }
        }
    }

    /** A point on the ground, or a point on the screen, depending on use. */
    data class Point(val x: Double, val y: Double)

    data class Point3(val x: Double, val y: Double, val z: Double = 0.0)

    data class Cell(val col: Int, val row: Int)

    data class Room(val circuitId: String, val label: String, val kind: Kind, val cell: Cell) {
        /** The middle of the floor. */
        val centre: Point get() = Point(cell.col * CELL_W + CELL_W / 2, cell.row + 0.5)

        /** Where a tap is aimed and a name is hung: mid-height over the floor's middle. */
        val anchor: Point3 get() = Point3(centre.x, centre.y, WALL_HEIGHT / 2)
    }

    /**
     * The house: [cols] by [rows] cells, the front row filled first so the house
     * always has a complete front, and the board standing in front of its left
     * corner where a real board sits by the entrance.
     */
    data class Layout(val cols: Int, val rows: Int, val rooms: List<Room>) {
        val board: Point get() = Point(BOARD_X, rows + TRUNK_Y)

        /** Where the mains line enters the picture, left of and behind the board. */
        val mains: Point get() = Point(BOARD_X - 1.1, rows + TRUNK_Y - 0.55)

        /** What the camera turns around: the middle of the house at half wall height. */
        val centre: Point3 get() = Point3(cols * CELL_W / 2, rows / 2.0, WALL_HEIGHT / 2)

        /**
         * The projected extent of everything drawn, seen from [camera]: the footprint
         * at the ground and at wall height, the board and the mains. The screen fits
         * this to the canvas, so the house fills it from every angle.
         */
        fun extent(camera: Camera): Bounds {
            val w = cols * CELL_W
            val r = rows.toDouble()
            val pts = listOf(
                Point3(0.0, 0.0), Point3(w, 0.0), Point3(0.0, r), Point3(w, r),
                Point3(0.0, 0.0, WALL_HEIGHT), Point3(w, 0.0, WALL_HEIGHT),
                Point3(0.0, r, WALL_HEIGHT), Point3(w, r, WALL_HEIGHT),
                Point3(board.x, board.y), Point3(board.x, board.y, BOARD_HEIGHT), Point3(mains.x, mains.y),
            ).map { project(it, camera) }
            return Bounds(pts.minOf { it.x }, pts.maxOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.y })
        }

        /**
         * The wire to a room: from the board along a trunk in front of the house to
         * the room's column, up the column's left wall, then into the room. Rooms
         * behind one another in a column run parallel, a little apart, so both
         * stay visible. A diagram of which breaker feeds which room, not a route.
         */
        fun wire(room: Room): List<Point> {
            val rise = room.cell.col * CELL_W + WIRE_INSET + WIRE_GAP * (rows - 1 - room.cell.row)
            val trunkY = rows + TRUNK_Y
            val c = room.centre
            return listOf(board, Point(rise, trunkY), Point(rise, c.y), c)
        }

        /** The room whose anchor is nearest [p] on screen, within [tolerance] projected units. */
        fun roomAt(p: Point, tolerance: Double, camera: Camera = Camera.DEFAULT): Room? = rooms
            .map { it to distance(project(it.anchor, camera), p) }
            .filter { (_, d) -> d <= tolerance }
            .minByOrNull { (_, d) -> d }?.first
    }

    data class Bounds(val minU: Double, val maxU: Double, val minV: Double, val maxV: Double) {
        val width: Double get() = maxU - minU
        val height: Double get() = maxV - minV
        val centre: Point get() = Point((minU + maxU) / 2, (minV + maxV) / 2)
    }

    const val WALL_HEIGHT = 0.5
    const val CELL_W = 1.5
    const val BOARD_HEIGHT = 0.55
    private const val BOARD_X = -0.45
    private const val TRUNK_Y = 0.6
    private const val WIRE_INSET = 0.12
    private const val WIRE_GAP = 0.1

    /**
     * Rooms in the order given, which the screen takes from the Board Map so the
     * numbers agree between the two pictures.
     */
    fun layout(circuits: List<Circuit>): Layout {
        val n = circuits.size
        val cols = when {
            n <= 1 -> 1
            n <= 4 -> 2
            n <= 9 -> 3
            else -> 4
        }
        val rows = ceil(n / cols.toDouble()).toInt().coerceAtLeast(1)
        val rooms = circuits.mapIndexed { i, c ->
            Room(c.id, c.label, Kind.of(c.label), Cell(i % cols, rows - 1 - i / cols))
        }
        return Layout(cols, rows, rooms)
    }

    // ---- camera ----

    /**
     * Where the house is seen from. [yawDeg] turns around the house; [pitchDeg] is
     * how far above the ground the eye is, 90 being straight down; [zoom] scales.
     * The default is the classic isometric corner view.
     */
    data class Camera(val yawDeg: Double, val pitchDeg: Double, val zoom: Double = 1.0) {
        fun orbit(dYawDeg: Double, dPitchDeg: Double): Camera = copy(
            yawDeg = (yawDeg + dYawDeg) % 360.0,
            pitchDeg = (pitchDeg + dPitchDeg).coerceIn(MIN_PITCH, MAX_PITCH),
        )

        fun zoomed(factor: Double): Camera = copy(zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM))

        companion object {
            val DEFAULT = Camera(yawDeg = -45.0, pitchDeg = 32.0)
            const val MIN_PITCH = 12.0
            const val MAX_PITCH = 85.0
            const val MIN_ZOOM = 0.6
            const val MAX_ZOOM = 3.0
        }
    }

    /** Eye distance from the house's centre in world units. Nearer means stronger perspective. */
    const val EYE_DISTANCE = 12.0

    /** A point in the camera's frame: x across, y down the screen, z towards the eye. */
    fun view(p: Point3, camera: Camera): Point3 {
        val yaw = Math.toRadians(camera.yawDeg)
        val cy = cos(yaw)
        val sy = sin(yaw)
        val xr = p.x * cy + p.y * sy
        val yr = -p.x * sy + p.y * cy
        val pitch = Math.toRadians(camera.pitchDeg)
        val cp = cos(pitch)
        val sp = sin(pitch)
        return Point3(xr, yr * sp - p.z * cp, yr * cp + p.z * sp)
    }

    /** Projected screen position in unscaled units, with perspective from [EYE_DISTANCE]. */
    fun project(p: Point3, camera: Camera = Camera.DEFAULT): Point {
        val v = view(p, camera)
        val f = EYE_DISTANCE / (EYE_DISTANCE - v.z)
        return Point(v.x * f, v.y * f)
    }

    fun project(p: Point, camera: Camera = Camera.DEFAULT): Point = project(Point3(p.x, p.y), camera)

    /** How near the eye a point is: larger is nearer, so it is drawn later. */
    fun depth(p: Point3, camera: Camera): Double = view(p, camera).z

    /** True when a wall with this outward ground normal faces the eye, so it should be glass, not solid. */
    fun facesViewer(normal: Point, camera: Camera): Boolean {
        val yaw = Math.toRadians(camera.yawDeg)
        return -normal.x * sin(yaw) + normal.y * cos(yaw) > 0
    }

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    // ---- what a reading does to a room ----

    /**
     * How a room is lit and how fast its wire runs. Every field is from a reading or
     * a result, never from the layout.
     */
    data class Flow(
        val mark: BoardMap.Mark,
        /**
         * 0 when no current was seen or nothing was measured; a little for an unclear
         * signal; [MIN_FLOWING]..1 while current flows, rising with the measured load
         * on a calibrated circuit or with the strength of the line signal otherwise.
         * Drives the wire's speed and the room's glow, nothing else.
         */
        val intensity: Double,
        /** Amperes, only on a calibrated circuit whose last reading saw current. */
        val amps: Double?,
        /** [amps] over the breaker rating, when both are known. */
        val loadFraction: Double?,
        val epochMillis: Long?,
        val title: String?,
    ) {
        val flowing: Boolean get() = intensity >= MIN_FLOWING
    }

    const val MIN_FLOWING = 0.3
    const val UNCLEAR_INTENSITY = 0.12

    fun flowOf(circuit: Circuit, dot: BoardMap.Dot, latest: Reading?): Flow {
        val line = latest?.let { LineState.of(it.lineConfidence) }
        // The same rule as Metrics: no amperes unless current is clearly flowing, or
        // room noise divided by the calibration factor becomes a confident fiction.
        val amps = circuit.utPerAmp?.takeIf { k ->
            k > 0 && latest != null && latest.fieldEstimateUsable && line == LineState.FLOWING
        }?.let { latest!!.fieldAmplitudeUt / it }
        val load = amps?.let { a -> circuit.breakerRatingA?.takeIf { it > 0 }?.let { a / it } }
        val intensity = when {
            dot.mark == BoardMap.Mark.NOT_MEASURED || latest == null -> 0.0
            line == LineState.FLOWING -> {
                val strength = load?.coerceIn(0.0, 1.0)
                    ?: ((latest.lineConfidence - Metrics.LIVE_CONFIDENCE) / (1.0 - Metrics.LIVE_CONFIDENCE)).coerceIn(0.0, 1.0)
                MIN_FLOWING + (1.0 - MIN_FLOWING) * strength
            }
            line == LineState.UNCLEAR -> UNCLEAR_INTENSITY
            else -> 0.0
        }
        return Flow(dot.mark, intensity, amps, load, dot.epochMillis, dot.title)
    }

    /** "3 of 5 rooms drawing current · 7.4 A on calibrated circuits", only the parts that apply. */
    fun summary(flows: Collection<Flow>): String {
        if (flows.isEmpty()) return ""
        val on = flows.count { it.flowing }
        val amps = flows.mapNotNull { it.amps }
        val parts = mutableListOf("$on of ${flows.size} ${if (flows.size == 1) "room" else "rooms"} drawing current")
        if (amps.isNotEmpty()) parts += "%.1f A on ${if (amps.size == 1) "the calibrated circuit" else "calibrated circuits"}".format(amps.sum())
        val problems = flows.count { it.mark == BoardMap.Mark.PROBLEM }
        if (problems > 0) parts += "$problems ${if (problems == 1) "problem" else "problems"}"
        return parts.joinToString(" · ")
    }

    /** What is written under a room's name: amperes when they are meaningful, the mark otherwise. */
    fun caption(flow: Flow): String = when {
        flow.amps != null -> "%.1f A".format(flow.amps)
        else -> flow.mark.label
    }

    /** True when two rooms would be drawn on top of each other: a layout bug, checked in tests. */
    fun overlaps(layout: Layout): Boolean =
        layout.rooms.map { it.cell }.toSet().size != layout.rooms.size ||
            layout.rooms.any { it.cell.col !in 0 until layout.cols || it.cell.row !in 0 until layout.rows }

    /** True when a wire's every segment is axis-aligned in grid space, so it reads as a diagram. */
    fun isOrthogonal(path: List<Point>): Boolean =
        path.zipWithNext().all { (a, b) -> abs(a.x - b.x) < 1e-9 || abs(a.y - b.y) < 1e-9 }
}
