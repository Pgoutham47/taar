package com.taar.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taar.domain.BoardMap
import com.taar.domain.Circuit
import com.taar.domain.HouseView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * House View: the board's circuits as the rooms of a house, each lit by the current
 * last measured on it, with the wire from the board running at that current's pace.
 *
 * Built for showing, so it moves; honest, so it moves only where a reading says
 * current flows. The geometry is [HouseView]'s and is a diagram, not a floor plan.
 * The house can be turned by dragging, and opened full screen in landscape where it
 * can also be tilted and zoomed.
 */
@Composable
fun HouseScreen(
    state: TaarViewModel.UiState,
    onMeasure: (Circuit) -> Unit,
    onBoardMap: () -> Unit,
    onCircuits: () -> Unit,
    onBack: () -> Unit,
) {
    val board = state.installation
    val circuits = board?.circuits ?: emptyList()

    // The same order as the Board Map's numbering, so the two pictures agree.
    val ordered = BoardMap.order(circuits.map { it.id }, state.boardMap.pins)
        .mapNotNull { id -> circuits.find { it.id == id } }
    val layout = remember(ordered) { HouseView.layout(ordered) }
    val flows = remember(ordered, state.boardDots, state.latestReadings) {
        ordered.associate { c ->
            c.id to HouseView.flowOf(c, state.boardDots[c.id] ?: BoardMap.dotOf(null, null), state.latestReadings[c.id])
        }
    }
    var selected by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf(HouseView.Camera.DEFAULT) }
    var fullScreen by remember { mutableStateOf(false) }
    val chosen = ordered.find { it.id == selected }
    val flow = chosen?.let { flows[it.id] }
    val select = { id: String? -> selected = if (id == null || id == selected) null else id }

    BackHandler(enabled = fullScreen) { fullScreen = false }
    LandscapeWhile(fullScreen)

    if (fullScreen && board != null && ordered.isNotEmpty()) {
        FullScreenHouse(
            boardName = board.name, layout = layout, flows = flows, selected = selected, camera = camera,
            onCamera = { camera = it }, onTap = select, chosen = chosen, flow = flow,
            onMeasure = { fullScreen = false; onMeasure(it) }, onDone = { fullScreen = false },
        )
        return
    }

    TaarScreen(title = "House View", subtitle = board?.name ?: "No board chosen", onBack = onBack) {
        if (board == null) {
            Banner("Choose a board first.", Tone.WARNING)
            PrimaryButton("Circuits and boards", onClick = onCircuits)
            return@TaarScreen
        }
        if (ordered.isEmpty()) {
            Banner("Add the board's circuits first. Each one becomes a room of the house.", Tone.INFO)
            PrimaryButton("Add circuits", onClick = onCircuits)
            return@TaarScreen
        }

        Box(
            Modifier.fillMaxWidth().aspectRatio(1.05f).clip(MaterialTheme.shapes.medium)
                .background(TaarPalette.Panel).border(1.dp, TaarPalette.Outline, MaterialTheme.shapes.medium),
        ) {
            // Inside a scrolling page only a sideways drag turns the house; up and
            // down still scrolls. Full screen has every gesture.
            HouseCanvas(layout, flows, selected, camera, onCamera = { camera = it }, onTap = select, orbitOnly = true,
                modifier = Modifier.fillMaxSize())
            Text(
                "Full screen", style = MaterialTheme.typography.labelLarge, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)).clickable { fullScreen = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Text(
                "Drag to turn", style = MaterialTheme.typography.labelSmall, color = TaarPalette.Grey,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }

        val summary = HouseView.summary(flows.values)
        if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.titleSmall)

        if (chosen != null && flow != null) {
            val room = layout.rooms.first { it.circuitId == chosen.id }
            TaarCard(tone = toneOf(flow.mark)) {
                Text(chosen.label, style = MaterialTheme.typography.titleLarge)
                Text(
                    listOfNotNull(
                        flow.mark.label, flow.epochMillis?.let { time(it) },
                        // The kind only when it adds something the name does not already say.
                        room.kind.caption.takeIf { room.kind != HouseView.Kind.ROOM && !chosen.label.contains(it, ignoreCase = true) },
                        chosen.breakerRatingA?.let { "%.0f A breaker".format(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey,
                )
                flow.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                flow.amps?.let { a ->
                    Metric(
                        "Current", "%.1f A".format(a),
                        flow.loadFraction?.let { "%.0f%% of the breaker's rating".format(it * 100) },
                    )
                }
                if (flow.amps == null && flow.flowing) {
                    Hint("Current is flowing. Calibrate this circuit against an appliance of known power to see amperes here.")
                }
                PrimaryButton(measureLabel(chosen), onClick = { onMeasure(chosen) })
            }
        } else {
            Hint("Tap a room to see its last result and measure it again.")
        }

        MarkLegend()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Board Map", onClick = onBoardMap, modifier = Modifier.weight(1f))
            SecondaryButton("Circuits", onClick = onCircuits, modifier = Modifier.weight(1f))
        }

        Hint(
            "The rooms are your circuits, named as you named them at the board. The wires show which breaker feeds " +
                "which room; they are a diagram, not where cables run in the walls. A dark room has not been measured, " +
                "and a grey one showed no current, which is not the same as being dead.",
        )
    }
}

private fun measureLabel(c: Circuit) =
    if (c.baseline?.isSufficient == true) "Measure this circuit" else "Record its normal, then measure"

/** Turns the phone to landscape while [on], and lets it go when the view closes. */
@Composable
private fun LandscapeWhile(on: Boolean) {
    val activity = LocalContext.current.activity()
    DisposableEffect(on, activity) {
        if (on) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        onDispose { if (on) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * The house across the whole screen. Drag to turn, drag up and down to tilt, pinch
 * to zoom, double-tap to come back to the corner view, tap a room for its result.
 */
@Composable
private fun FullScreenHouse(
    boardName: String,
    layout: HouseView.Layout,
    flows: Map<String, HouseView.Flow>,
    selected: String?,
    camera: HouseView.Camera,
    onCamera: (HouseView.Camera) -> Unit,
    onTap: (String?) -> Unit,
    chosen: Circuit?,
    flow: HouseView.Flow?,
    onMeasure: (Circuit) -> Unit,
    onDone: () -> Unit,
) {
    // Bars above and below the house rather than over it, so nothing is hidden.
    Column(Modifier.fillMaxSize().background(Color(0xFF07090E))) {
        Row(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(boardName, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(
                    HouseView.summary(flows.values).ifEmpty { "Drag to turn · pinch to zoom · double-tap to reset" },
                    style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey,
                )
            }
            Text(
                "Done", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Panel,
                modifier = Modifier.clip(CircleShape).background(TaarPalette.Glow).clickable(onClick = onDone)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        HouseCanvas(layout, flows, selected, camera, onCamera, onTap, orbitOnly = false,
            modifier = Modifier.fillMaxWidth().weight(1f))

        Row(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.65f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (chosen != null && flow != null) {
                Column(Modifier.weight(1f)) {
                    Text(chosen.label, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(
                        listOfNotNull(
                            flow.title ?: flow.mark.label,
                            flow.amps?.let { "%.1f A".format(it) },
                            flow.loadFraction?.let { "%.0f%% of rating".format(it * 100) },
                            flow.epochMillis?.let { time(it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = colourOf(flow.mark),
                    )
                }
                TextButton(onClick = { onMeasure(chosen) }) { Text(measureLabel(chosen), color = TaarPalette.Glow) }
                TextButton(onClick = { onTap(null) }) { Text("Close", color = TaarPalette.Grey) }
            } else {
                Text(
                    "Drag to turn the house · drag up or down to tilt · pinch to zoom · tap a room",
                    style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Grey, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** How close to a room's anchor a tap must land, in projected units. */
private const val TAP_TOLERANCE = 0.55

/** Degrees of turn and tilt per density-independent pixel dragged. */
private const val YAW_PER_DP = 0.45f
private const val PITCH_PER_DP = 0.3f

@Composable
private fun HouseCanvas(
    layout: HouseView.Layout,
    flows: Map<String, HouseView.Flow>,
    selected: String?,
    camera: HouseView.Camera,
    onCamera: (HouseView.Camera) -> Unit,
    onTap: (String?) -> Unit,
    orbitOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    // Seconds since the picture appeared; every moving thing is a function of it.
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> t = (now - start) / 1_000_000_000f }
    }
    val measurer = rememberTextMeasurer()
    val cam by rememberUpdatedState(camera)
    val move by rememberUpdatedState(onCamera)

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val frame = remember(w, h, layout, camera) { Frame(layout, camera, w, h) }
        val gestures = if (orbitOnly) {
            Modifier.pointerInput(Unit) {
                detectHorizontalDragGestures { _, dx -> move(cam.orbit((dx / density * YAW_PER_DP).toDouble(), 0.0)) }
            }
        } else {
            Modifier.pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    move(
                        cam.orbit((pan.x / density * YAW_PER_DP).toDouble(), (-pan.y / density * PITCH_PER_DP).toDouble())
                            .zoomed(zoom.toDouble()),
                    )
                }
            }
        }
        Canvas(
            Modifier.fillMaxSize().then(gestures).pointerInput(layout) {
                detectTapGestures(
                    onDoubleTap = { move(HouseView.Camera.DEFAULT) },
                    onTap = { at -> onTap(layout.roomAt(frame.toUnit(at), TAP_TOLERANCE, cam)?.circuitId) },
                )
            },
        ) {
            drawHouse(layout, flows, selected, frame, t, measurer)
        }
    }
}

/**
 * Fits what the camera sees onto the canvas at its zoom, and maps taps back. The
 * fit follows the view, so the house fills the frame however it is turned.
 */
private class Frame(layout: HouseView.Layout, val camera: HouseView.Camera, width: Float, height: Float) {
    private val extent = layout.extent(camera)
    val scale: Float = run {
        val margin = 0.06 * min(width, height)
        (camera.zoom * min((width - 2 * margin) / extent.width, (height - 2 * margin) / extent.height)).toFloat()
    }
    private val c = extent.centre
    private val ox = width / 2 - (c.x * scale).toFloat()
    private val oy = height / 2 - (c.y * scale).toFloat()

    fun at(p: HouseView.Point3): Offset {
        val s = HouseView.project(p, camera)
        return Offset((s.x * scale + ox).toFloat(), (s.y * scale + oy).toFloat())
    }

    fun at(x: Double, y: Double, z: Double = 0.0): Offset = at(HouseView.Point3(x, y, z))

    fun at(p: HouseView.Point, z: Double = 0.0): Offset = at(HouseView.Point3(p.x, p.y, z))

    fun toUnit(o: Offset): HouseView.Point =
        HouseView.Point(((o.x - ox) / scale).toDouble(), ((o.y - oy) / scale).toDouble())
}

private fun Path.moveTo(o: Offset) = moveTo(o.x, o.y)
private fun Path.lineTo(o: Offset) = lineTo(o.x, o.y)

private fun quad(a: Offset, b: Offset, c: Offset, d: Offset): Path = Path().apply {
    moveTo(a); lineTo(b); lineTo(c); lineTo(d); close()
}

private fun DrawScope.drawHouse(
    layout: HouseView.Layout,
    flows: Map<String, HouseView.Flow>,
    selected: String?,
    f: Frame,
    t: Float,
    measurer: TextMeasurer,
) {
    val cam = f.camera
    val anyFlowing = flows.values.any { it.flowing }
    val cols = layout.cols * HouseView.CELL_W
    val rows = layout.rows.toDouble()

    // Ground under the footprint, so the house sits on something.
    drawPath(quad(f.at(-0.15, -0.15), f.at(cols + 0.15, -0.15), f.at(cols + 0.15, rows + 0.15), f.at(-0.15, rows + 0.15)),
        Color.Black.copy(alpha = 0.35f))

    // Mains into the board: lit whenever any circuit is drawing.
    val mains = if (anyFlowing) HouseView.Flow(BoardMap.Mark.LIVE, 0.6, null, null, null, null)
    else HouseView.Flow(BoardMap.Mark.OFF, 0.0, null, null, null, null)
    drawWire(listOf(f.at(layout.mains), f.at(layout.board)), mains, t, colour = TaarPalette.Glow)

    // Solid things from the farthest to the nearest, so nearer ones lie over them.
    val depths = layout.rooms.associate { it.circuitId to HouseView.depth(it.anchor, cam) }
    val solids = layout.rooms.map { r -> depths.getValue(r.circuitId) to { drawRoom(r, flows[r.circuitId], selected == r.circuitId, f, t) } } +
        (HouseView.depth(HouseView.Point3(layout.board.x, layout.board.y, HouseView.BOARD_HEIGHT / 2), cam) to { drawBoard(layout, anyFlowing, f, t, measurer) })
    for ((_, draw) in solids.sortedBy { it.first }) draw()

    for (room in layout.rooms) {
        drawWire(layout.wire(room).map { f.at(it) }, flows[room.circuitId], t)
    }

    // Names, the farther ones hung higher, so a room's name is not under the one in front.
    val near = depths.values.maxOrNull() ?: 0.0
    val far = depths.values.minOrNull() ?: 0.0
    for (room in layout.rooms) {
        val d = depths.getValue(room.circuitId)
        val farness = if (near - far > 1e-6) (near - d) / (near - far) else 0.0
        drawLabel(room, flows[room.circuitId], farness, f, measurer)
    }
}

private fun DrawScope.drawRoom(room: HouseView.Room, flow: HouseView.Flow?, selected: Boolean, f: Frame, t: Float) {
    val x0 = room.cell.col * HouseView.CELL_W
    val y0 = room.cell.row.toDouble()
    val x1 = x0 + HouseView.CELL_W
    val y1 = y0 + 1
    val hgt = HouseView.WALL_HEIGHT
    val a = f.at(x0, y0); val b = f.at(x1, y0); val c = f.at(x1, y1); val d = f.at(x0, y1)
    val aT = f.at(x0, y0, hgt); val bT = f.at(x1, y0, hgt); val cT = f.at(x1, y1, hgt); val dT = f.at(x0, y1, hgt)

    val mark = flow?.mark ?: BoardMap.Mark.NOT_MEASURED
    val intensity = (flow?.intensity ?: 0.0).toFloat()
    val glow = when (mark) {
        BoardMap.Mark.NOT_MEASURED, BoardMap.Mark.OFF -> null
        else -> colourOf(mark)
    }

    // Each wall with its outward normal; the ones facing away are solid and go
    // under the floor, the ones facing the eye are glass and go over it.
    val walls = listOf(
        Triple(quad(a, b, bT, aT), HouseView.Point(0.0, -1.0), TaarPalette.PanelHigh),
        Triple(quad(b, c, cT, bT), HouseView.Point(1.0, 0.0), TaarPalette.PanelHigh.copy(alpha = 0.85f)),
        Triple(quad(c, d, dT, cT), HouseView.Point(0.0, 1.0), TaarPalette.PanelHigh),
        Triple(quad(d, a, aT, dT), HouseView.Point(-1.0, 0.0), TaarPalette.PanelHigh.copy(alpha = 0.85f)),
    )
    val (front, back) = walls.partition { (_, n, _) -> HouseView.facesViewer(n, f.camera) }
    for ((path, _, colour) in back) drawPath(path, colour)

    // Floor, lit from the middle by what was measured.
    val floor = quad(a, b, c, d)
    drawPath(floor, Color(0xFF0E121A))
    if (glow != null) {
        // Breathes faster the harder the circuit works.
        val pulse = 0.8f + 0.2f * sin(t * (1.5f + 3f * intensity))
        drawPath(floor, glow.copy(alpha = (0.16f + 0.42f * intensity) * pulse))
        drawPath(
            floor,
            Brush.radialGradient(
                listOf(glow.copy(alpha = 0.6f * intensity * pulse), Color.Transparent),
                center = f.at(room.centre), radius = f.scale * 0.9f,
            ),
        )
    } else if (mark == BoardMap.Mark.OFF) {
        drawPath(floor, TaarPalette.Grey.copy(alpha = 0.08f))
    }

    for ((path, _, _) in front) drawPath(path, Color.White.copy(alpha = 0.05f))

    val edge = if (selected) Color.White else Color(0xFF3A4252)
    val ew = (if (selected) 2.dp else 1.dp).toPx()
    drawPath(floor, edge.copy(alpha = 0.7f), style = Stroke(ew))
    for ((g, top) in listOf(a to aT, b to bT, c to cT, d to dT)) drawLine(edge, g, top, ew)
    drawPath(quad(aT, bT, cT, dT), edge, style = Stroke(ew))
}

/**
 * A wire: a quiet base line, and over it moving dashes whose pace is the measured
 * current. No dashes move on a wire that showed no current; an unclear signal gets
 * still dashes; a problem flickers and sparks where the wire enters the room.
 */
private fun DrawScope.drawWire(pts: List<Offset>, flow: HouseView.Flow?, t: Float, colour: Color? = null) {
    if (pts.size < 2) return
    val path = Path().apply { moveTo(pts[0]); pts.drop(1).forEach { lineTo(it) } }
    val mark = flow?.mark ?: BoardMap.Mark.NOT_MEASURED
    val lit = colour ?: colourOf(mark)
    val width = 3.dp.toPx()
    fun stroke(w: Float, effect: PathEffect? = null) = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect)

    when (mark) {
        BoardMap.Mark.NOT_MEASURED -> {
            drawPath(path, Color(0xFF3A4252), style = stroke(width, PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))))
            return
        }
        BoardMap.Mark.OFF -> {
            drawPath(path, TaarPalette.Grey.copy(alpha = 0.45f), style = stroke(width))
            return
        }
        else -> drawPath(path, lit.copy(alpha = 0.5f), style = stroke(width))
    }
    if (flow == null || flow.intensity <= 0.0) return

    val dash = 9.dp.toPx()
    val gap = 11.dp.toPx()
    if (!flow.flowing) {
        drawPath(path, lit, style = stroke(width, PathEffect.dashPathEffect(floatArrayOf(dash * 0.6f, gap), 0f)))
        return
    }

    // Dashes travel from the board outward. Pace in dp per second, from the measured intensity.
    val speed = (40f + 190f * flow.intensity.toFloat()) * density
    val phase = -((t * speed) % (dash + gap))
    val alpha = if (mark == BoardMap.Mark.PROBLEM) 0.55f + 0.45f * abs(sin(t * 17f)) else 1f
    val moving = PathEffect.dashPathEffect(floatArrayOf(dash, gap), phase)
    drawPath(path, lit.copy(alpha = alpha), style = stroke(width * 1.2f, moving))
    drawPath(path, Color.White.copy(alpha = 0.55f * alpha), style = stroke(width * 0.45f, moving))
    if (mark == BoardMap.Mark.PROBLEM) drawSpark(pts.last(), t)
}

private fun DrawScope.drawSpark(at: Offset, t: Float) {
    val flick = 0.5f + 0.5f * abs(sin(t * 23f))
    val r = 7.dp.toPx() * flick
    drawCircle(TaarPalette.Glow.copy(alpha = 0.35f * flick), r * 1.8f, at)
    for (i in 0 until 4) {
        val ang = (i * Math.PI / 4 + t * 3).toFloat()
        val arm = Offset(cos(ang) * r, sin(ang) * r)
        drawLine(TaarPalette.Glow, at - arm, at + arm, 1.5.dp.toPx(), cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawBoard(layout: HouseView.Layout, anyFlowing: Boolean, f: Frame, t: Float, measurer: TextMeasurer) {
    val b = layout.board
    val w = 0.34
    val d = 0.22
    val h = HouseView.BOARD_HEIGHT
    val x0 = b.x - w / 2; val x1 = b.x + w / 2; val y0 = b.y - d / 2; val y1 = b.y + d / 2
    val aa = f.at(x0, y0); val bb = f.at(x1, y0); val cc = f.at(x1, y1); val dd = f.at(x0, y1)
    val aT = f.at(x0, y0, h); val bT = f.at(x1, y0, h); val cT = f.at(x1, y1, h); val dT = f.at(x0, y1, h)
    val face = if (anyFlowing) TaarPalette.Glow else TaarPalette.Grey

    if (anyFlowing) {
        // A ring leaving the board every 1.25 s: the source of everything moving.
        val pulse = (t * 0.8f) % 1f
        drawCircle(TaarPalette.Glow.copy(alpha = 0.35f * (1f - pulse)), f.scale * 0.28f * (0.5f + pulse), f.at(b))
    }
    val sides = listOf(
        Triple(quad(aa, bb, bT, aT), HouseView.Point(0.0, -1.0), 0.6f),
        Triple(quad(bb, cc, cT, bT), HouseView.Point(1.0, 0.0), 0.55f),
        Triple(quad(cc, dd, dT, cT), HouseView.Point(0.0, 1.0), 0.75f),
        Triple(quad(dd, aa, aT, dT), HouseView.Point(-1.0, 0.0), 0.5f),
    )
    for ((path, n, alpha) in sides) if (HouseView.facesViewer(n, f.camera)) drawPath(path, face.copy(alpha = alpha))
    drawPath(quad(aT, bT, cT, dT), face)

    val label = measurer.measure("Board", TextStyle(color = TaarPalette.Grey, fontSize = 10.sp, fontWeight = FontWeight.SemiBold))
    val at = f.at(b) + Offset(0f, f.scale * 0.2f)
    drawText(label, topLeft = Offset(at.x - label.size.width / 2f, at.y))
}

private fun DrawScope.drawLabel(room: HouseView.Room, flow: HouseView.Flow?, farness: Double, f: Frame, measurer: TextMeasurer) {
    // A near room's name sits low over its floor; a room behind it carries its name
    // higher, above its rim, so the two do not overlap on screen.
    val centre = f.at(room.centre, HouseView.WALL_HEIGHT * (0.2 + 1.0 * farness))
    val maxW = (f.scale * 1.4f).toInt().coerceAtLeast(24)
    val name = measurer.measure(
        room.label, TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW),
    )
    val mark = flow?.mark ?: BoardMap.Mark.NOT_MEASURED
    val capColour = when (mark) {
        BoardMap.Mark.NOT_MEASURED -> TaarPalette.Faint
        BoardMap.Mark.OFF -> TaarPalette.Grey
        else -> colourOf(mark)
    }
    val cap = measurer.measure(
        if (flow != null) HouseView.caption(flow) else BoardMap.Mark.NOT_MEASURED.label,
        TextStyle(color = capColour, fontSize = 10.sp),
        overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW),
    )
    val total = (name.size.height + cap.size.height).toFloat()
    val plateW = max(name.size.width, cap.size.width) + 10.dp.toPx()
    val plateH = total + 6.dp.toPx()
    drawRoundRect(
        Color.Black.copy(alpha = 0.5f), topLeft = Offset(centre.x - plateW / 2, centre.y - plateH / 2),
        size = Size(plateW, plateH), cornerRadius = CornerRadius(6.dp.toPx()),
    )
    drawText(name, topLeft = Offset(centre.x - name.size.width / 2f, centre.y - total / 2))
    drawText(cap, topLeft = Offset(centre.x - cap.size.width / 2f, centre.y - total / 2 + name.size.height))
}
