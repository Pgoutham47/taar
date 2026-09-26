package com.taar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.taar.domain.Circuit
import com.taar.domain.Fusion
import com.taar.domain.RoomMap
import com.taar.domain.RoomStore
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Room 3D Scan (experimental). Setup, then the live AR scan, then the 3D map.
 *
 * SceneView draws the camera, the detected surfaces and a sphere at each pinned
 * spot; ARCore tracks the phone. Taar's own pipeline measures at each spot. The
 * map shows measured activity, never hidden wiring.
 */
@Composable
fun RoomScanScreen(
    state: TaarViewModel.UiState,
    circuit: Circuit?,
    onPinned: () -> Int,
    onCancelPoint: () -> Unit,
    onMeasure: (Int, RoomMap.Vec3) -> Unit,
    onFinish: (List<RoomMap.Plane>, Map<Int, RoomMap.Vec3>) -> Unit,
    onOpen: (RoomStore.Session) -> Unit,
    onNewScan: () -> Unit,
    onBack: () -> Unit,
) {
    val room = state.room
    var scanning by rememberSaveable { mutableStateOf(false) }

    when {
        room == null -> Unit
        room.map != null -> MapStage(state, room, room.map, onNewScan = { scanning = false; onNewScan() }, onBack = onBack)
        scanning -> ArStage(room, onPinned, onCancelPoint, onMeasure,
            onFinish = { planes, refined -> onFinish(planes, refined); scanning = false },
            onBack = { scanning = false; onBack() })
        else -> SetupStage(state, circuit, onStart = { scanning = true }, onOpen = onOpen, onBack = onBack)
    }
}

// ---- 1. setup ----

@Composable
private fun SetupStage(
    state: TaarViewModel.UiState,
    circuit: Circuit?,
    onStart: () -> Unit,
    onOpen: (RoomStore.Session) -> Unit,
    onBack: () -> Unit,
) {
    TaarScreen(title = "Room 3D Scan", subtitle = "Experimental · one room", onBack = onBack) {
        Text("Map Taar's measurements onto a 3D model of one room.", style = MaterialTheme.typography.bodyLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Instruction(1, "Move slowly around the room with the camera up until the floor and walls show a dotted pattern.")
                Instruction(2, "Tap a socket or wall spot on the screen. A white ball marks it.")
                Instruction(3, "Press the phone flat on that spot and tap Measure. Hold still for 3 seconds.")
                Instruction(4, "Lift the phone. The ball turns green, yellow, orange or red. Repeat: 6 or more points.")
                Instruction(5, "Tap Finish to see the 3D map.")
            }
        }
        Hint("Keep the phone steady and at a similar distance from the surfaces you inspect. The magnetometer " +
            "only senses current within a few centimetres, which is why each point is measured by touching it.")
        if (circuit?.baseline?.isSufficient != true) {
            ErrorCard("Record a reference first. For a room, add a circuit named after it and record its reference " +
                "at a quiet spot with nothing running nearby; each point is compared with it.")
        } else {
            Hint("Compared with the reference of \"${circuit.label}\".")
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Start room scan", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (state.roomHistory.isNotEmpty()) {
            HorizontalDivider()
            Text("Saved room scans", style = MaterialTheme.typography.titleSmall)
            for (s in state.roomHistory.take(5)) {
                Text("${time(s.epochMillis)} · ${s.name} · ${s.points.size} points  ›",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(s) }.padding(vertical = 6.dp))
            }
        }
        Hint(RoomMap.CAVEAT)
    }
}

// ---- 2. live AR scan ----

@Composable
private fun ArStage(
    room: TaarViewModel.Room,
    onPinned: () -> Int,
    onCancelPoint: () -> Unit,
    onMeasure: (Int, RoomMap.Vec3) -> Unit,
    onFinish: (List<RoomMap.Plane>, Map<Int, RoomMap.Vec3>) -> Unit,
    onBack: () -> Unit,
) {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val childNodes = rememberNodes()
    val anchors = remember { mutableMapOf<Int, Anchor>() }
    val markers = remember { mutableMapOf<Int, AnchorNode>() }
    val coloured = remember { mutableSetOf<Int>() }
    // The latest frame, kept outside Compose state so it does not recompose 30 times a second.
    val latest = remember { arrayOfNulls<Frame>(1) }
    val lastPlanes = remember { longArrayOf(0L) }
    var tracking by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var planes by remember { mutableStateOf(emptyList<RoomMap.Plane>()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    val current by rememberUpdatedState(room)

    fun sphere(colour: Color, radius: Float) =
        SphereNode(engine = engine, radius = radius, materialInstance = materialLoader.createColorInstance(colour))

    fun positionOf(a: Anchor) = a.pose.let { RoomMap.Vec3(it.tx().toDouble(), it.ty().toDouble(), it.tz().toDouble()) }

    fun removeMarker(id: Int) {
        markers.remove(id)?.let { childNodes.remove(it); runCatching { it.destroy() } }
        anchors.remove(id)?.let { runCatching { it.detach() } }
    }

    // Once a point is measured, its white ball takes the point's colour, with a soft
    // halo for activity, so the room fills in as the technician works.
    LaunchedEffect(room.points) {
        for (p in room.points) {
            if (p.id in coloured) continue
            val node = markers[p.id] ?: continue
            node.childNodes.toList().forEach { node.removeChildNode(it); runCatching { it.destroy() } }
            val c = colourOf(p.state)
            node.addChildNode(sphere(c, 0.035f))
            if (p.accepted && p.activity >= RoomMap.ELEVATED_AT) node.addChildNode(sphere(c.copy(alpha = 0.28f), 0.11f))
            coloured += p.id
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !room.measuring) { Text("← Back") }
            Text("Room 3D Scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            ARScene(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                materialLoader = materialLoader,
                childNodes = childNodes,
                planeRenderer = true,
                sessionConfiguration = { session, config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.focusMode = Config.FocusMode.AUTO
                    config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                    config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                    // Depth, where the phone has it, lets a tap land on a plain wall
                    // before ARCore has outlined it as a surface.
                    config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                        Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                },
                onSessionFailed = { e -> problem = "AR could not start: ${e.message ?: e::class.simpleName}" },
                onTrackingFailureChanged = { failure = it },
                onSessionUpdated = { session, frame ->
                    latest[0] = frame
                    val t = frame.camera.trackingState == TrackingState.TRACKING
                    if (t != tracking) tracking = t
                    val now = System.currentTimeMillis()
                    if (now - lastPlanes[0] >= 500) {
                        lastPlanes[0] = now
                        planes = session.getAllTrackables(Plane::class.java)
                            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                            .map { toRoomPlane(it) }
                    }
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { e, _ ->
                        val r = current
                        if (r.pendingId != null || r.measuring) return@rememberOnGestureListener
                        val frame = latest[0]
                        if (frame == null || frame.camera.trackingState != TrackingState.TRACKING) {
                            hint = "Tracking is not ready. Move the phone slowly and try again."
                            return@rememberOnGestureListener
                        }
                        val hit = frame.hitTest(e.x, e.y).firstOrNull { h ->
                            when (val t = h.trackable) {
                                is Plane -> t.isPoseInPolygon(h.hitPose)
                                is Point -> t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                                is DepthPoint -> true
                                else -> false
                            }
                        }
                        if (hit == null) {
                            hint = "No surface found there. Tap on a dotted area, or move to let the wall be detected."
                            return@rememberOnGestureListener
                        }
                        val anchor = hit.createAnchor()
                        val id = onPinned()
                        anchors[id] = anchor
                        val node = AnchorNode(engine, anchor).apply { addChildNode(sphere(Color.White, 0.035f)) }
                        markers[id] = node
                        childNodes += node
                        hint = null
                    },
                ),
            )
            ProgressCard(room, tracking, failure, planes, anchors.mapValues { positionOf(it.value) },
                Modifier.align(Alignment.TopCenter).padding(8.dp))
            problem?.let { Box(Modifier.align(Alignment.Center).padding(16.dp)) { ErrorCard(it) } }
        }

        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val pending = room.pendingId
            when {
                room.measuring -> Text("Measuring… hold the phone still on the spot (3 s)",
                    style = MaterialTheme.typography.titleMedium, color = TaarPalette.Yellow)
                pending != null -> {
                    Text("Press the phone flat on the white ball's spot, then tap Measure.",
                        style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { anchors[pending]?.let { onMeasure(pending, positionOf(it)) } },
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) { Text("Measure", style = MaterialTheme.typography.titleMedium) }
                        OutlinedButton(onClick = { removeMarker(pending); onCancelPoint() },
                            modifier = Modifier.height(52.dp)) { Text("Cancel") }
                    }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (tracking) "Tap a spot on the screen to mark it" else "Move slowly to start tracking",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { onFinish(planes, anchors.mapValues { positionOf(it.value) }) },
                        enabled = room.points.isNotEmpty(),
                        modifier = Modifier.height(52.dp),
                    ) { Text("Finish") }
                }
            }
            (hint ?: room.lastMessage)?.let { Hint(it) }
        }
    }
}

private fun toRoomPlane(plane: Plane): RoomMap.Plane {
    val poly = plane.polygon
    val centre = plane.centerPose
    val vertices = (0 until poly.limit() / 2).map { i ->
        val w = centre.transformPoint(floatArrayOf(poly.get(i * 2), 0f, poly.get(i * 2 + 1)))
        RoomMap.Vec3(w[0].toDouble(), w[1].toDouble(), w[2].toDouble())
    }
    return RoomMap.Plane(plane.hashCode().toString(), plane.type == Plane.Type.VERTICAL, vertices)
}

@Composable
private fun ProgressCard(
    room: TaarViewModel.Room,
    tracking: Boolean,
    failure: TrackingFailureReason?,
    planes: List<RoomMap.Plane>,
    positions: Map<Int, RoomMap.Vec3>,
    modifier: Modifier,
) {
    val accepted = room.points.filter { it.accepted }.map { p -> positions[p.id]?.let { p.copy(position = it) } ?: p }
    val coverage = RoomMap.coverageOf(planes, accepted)
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xCC0D0F13))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("ROOM SCAN", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Yellow)
            val walls = planes.count { it.vertical }
            Line2("Spatial map", if (planes.isEmpty()) "searching…" else "✓ ${planes.size - walls} floor · $walls wall")
            Line2("Tracking", if (tracking) "✓" else "✕ ${reasonOf(failure)}")
            Line2("Measurements", "${room.points.size}  (${accepted.size} good · ${room.points.size - accepted.size} rejected)")
            if (coverage != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Coverage", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp))
                    Box(Modifier.weight(1f).height(8.dp).background(TaarPalette.Grey.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxWidth(coverage.toFloat().coerceIn(0.01f, 1f)).height(8.dp)
                            .background(TaarPalette.Green))
                    }
                    Text("${(coverage * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                when {
                    room.measuring -> "Collecting measurement…"
                    room.pendingId != null -> "Spot marked · waiting for Measure"
                    !tracking -> "Move the phone slowly to start tracking"
                    accepted.size < RoomMap.MIN_POINTS -> "Collecting measurements… ${RoomMap.MIN_POINTS - accepted.size} more for a map"
                    else -> "Enough for a map · add more or tap Finish"
                },
                style = MaterialTheme.typography.labelMedium, color = TaarPalette.Grey,
            )
        }
    }
}

private fun reasonOf(r: TrackingFailureReason?) = when (r) {
    TrackingFailureReason.EXCESSIVE_MOTION -> "moving too fast"
    TrackingFailureReason.INSUFFICIENT_LIGHT -> "too dark"
    TrackingFailureReason.INSUFFICIENT_FEATURES -> "point at a textured area"
    TrackingFailureReason.CAMERA_UNAVAILABLE -> "camera unavailable"
    else -> "starting…"
}

@Composable
private fun Line2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

// ---- 3. the 3D map ----

private sealed interface Picked {
    data class OfPoint(val index: Int, val point: RoomMap.Point) : Picked
    data class OfZone(val zone: RoomMap.Zone) : Picked
}

@Composable
private fun MapStage(
    state: TaarViewModel.UiState,
    room: TaarViewModel.Room,
    map: RoomMap.Map,
    onNewScan: () -> Unit,
    onBack: () -> Unit,
) {
    var showPoints by rememberSaveable { mutableStateOf(true) }
    var showRoom by rememberSaveable { mutableStateOf(true) }
    var showHeat by rememberSaveable { mutableStateOf(true) }
    var showPath by rememberSaveable { mutableStateOf(false) }
    var yaw by remember { mutableFloatStateOf(0.6f) }
    var pitch by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    val density = LocalDensity.current
    val view = remember(map) { Viewer(map) }

    TaarScreen(title = "3D Electrical Map", subtitle = room.name, onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(map.message, style = MaterialTheme.typography.titleSmall,
                    color = when {
                        !map.enough -> TaarPalette.Amber
                        map.zones.isNotEmpty() -> TaarPalette.Red
                        else -> TaarPalette.Green
                    })
                val good = map.points.count { it.accepted }
                Line2("Measurements", "${map.points.size} (${good} good · ${map.points.size - good} rejected)")
                map.coverage?.let { Line2("Wall & floor area near a reading", "${(it * 100).toInt()}%") }
                Line2("Surfaces", "${map.planes.size}")
            }
        }

        Box(
            Modifier.fillMaxWidth().height(380.dp).background(Color(0xFF0B0E13))
                .pointerInput(map) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val e = awaitPointerEvent()
                            val down = e.changes.count { it.pressed }
                            if (down == 1) {
                                val d = e.changes.first { it.pressed }.positionChange()
                                yaw += d.x * 0.008f
                                pitch = (pitch + d.y * 0.008f).coerceIn(-1.45f, 1.45f)
                            } else if (down >= 2) {
                                zoom = (zoom * e.calculateZoom()).coerceIn(0.3f, 6f)
                                pan += e.calculatePan()
                            }
                            e.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (e.changes.any { it.pressed })
                    }
                }
                .pointerInput(map, showPoints) {
                    detectTapGestures { tap ->
                        val cam = Cam(yaw, pitch, zoom, pan, size.width.toFloat(), size.height.toFloat())
                        val near = map.points.withIndex().mapNotNull { (i, p) ->
                            view.project(p.position, cam)?.let { Triple(i, p, (it.first - tap).getDistance()) }
                        }.filter { showPoints && it.third < with(density) { 28.dp.toPx() } }.minByOrNull { it.third }
                        picked = near?.let { Picked.OfPoint(it.first, it.second) }
                            ?: map.zones.firstOrNull { z ->
                                view.project(z.center, cam)?.let { (c, depth) ->
                                    (c - tap).getDistance() <= view.pixels(z.radius, depth, cam)
                                } ?: false
                            }?.let { Picked.OfZone(it) }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cam = Cam(yaw, pitch, zoom, pan, size.width, size.height)
                if (showRoom) for (plane in map.planes) {
                    val pts = plane.polygon.mapNotNull { view.project(it, cam)?.first }
                    if (pts.size < 3) continue
                    val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                    val c = if (plane.vertical) TaarPalette.Blue else TaarPalette.Grey
                    drawPath(path, c.copy(alpha = 0.10f))
                    drawPath(path, c.copy(alpha = 0.45f), style = Stroke(width = 2f))
                }
                if (showHeat) {
                    // Soft discs around each accepted point, coloured by the local average,
                    // limited to about a hand-span so sparse points are not smeared.
                    val splats = map.points.filter { it.accepted }.mapNotNull { p ->
                        view.project(p.position, cam)?.let { Triple(p, it.first, it.second) }
                    }.sortedByDescending { it.third }
                    for ((p, at, depth) in splats) {
                        val r = view.pixels(RoomMap.SIGMA_M * 1.6, depth, cam)
                        val c = heatColour(map.smoothed[p.id] ?: 0.0)
                        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.55f), Color.Transparent), at, r), r, at)
                    }
                }
                for (z in map.zones) {
                    val (at, depth) = view.project(z.center, cam) ?: continue
                    val r = view.pixels(z.radius, depth, cam)
                    drawCircle(Brush.radialGradient(listOf(TaarPalette.Red.copy(alpha = 0.45f), Color.Transparent), at, r), r, at)
                    drawCircle(TaarPalette.Red, r, at, style = Stroke(width = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
                }
                if (showPath) for ((a, b) in map.path) {
                    val pa = view.project(a, cam)?.first ?: continue
                    val pb = view.project(b, cam)?.first ?: continue
                    drawLine(TaarPalette.Amber.copy(alpha = 0.8f), pa, pb, 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                }
                if (showPoints) {
                    val sel = (picked as? Picked.OfPoint)?.point?.id
                    map.points.mapNotNull { p -> view.project(p.position, cam)?.let { Triple(p, it.first, it.second) } }
                        .sortedByDescending { it.third }
                        .forEach { (p, at, _) ->
                            val c = colourOf(p.state)
                            if (p.uncertain) drawCircle(c, 12f, at, style = Stroke(width = 4f))
                            else drawCircle(c, 12f, at)
                            if (p.id == sel) drawCircle(Color.White, 20f, at, style = Stroke(width = 4f))
                        }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(showPoints, { showPoints = !showPoints }, label = { Text("Points") })
            FilterChip(showRoom, { showRoom = !showRoom }, label = { Text("Room") })
            FilterChip(showHeat, { showHeat = !showHeat }, label = { Text("Heat") })
            FilterChip(showPath, { showPath = !showPath }, label = { Text("Path") })
        }
        if (showPath) {
            Hint("Estimated electrical activity: a line joining nearby strong readings. Not an exact wiring diagram.",
                color = TaarPalette.Amber)
        }
        Legend3D()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { yaw = 0.6f; pitch = 0.5f; zoom = 1f; pan = Offset.Zero; picked = null },
                modifier = Modifier.weight(1f)) { Text("Reset view") }
            OutlinedButton(onClick = onNewScan, modifier = Modifier.weight(1f)) { Text("New scan") }
        }
        Hint("Drag to rotate · pinch to zoom · two fingers to pan · tap a point or a red zone to inspect.")

        when (val p = picked) {
            is Picked.OfPoint -> PointDetails(p.index, p.point, state)
            is Picked.OfZone -> ZoneDetails(p.zone)
            null -> Unit
        }
        Hint(RoomMap.CAVEAT)
    }
}

@Composable
private fun PointDetails(index: Int, p: RoomMap.Point, state: TaarViewModel.UiState) {
    var why by remember(p.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("MEASUREMENT", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Grey)
            Line2("Location", "3D scan point #${index + 1}  (%.2f, %.2f, %.2f) m".format(p.position.x, p.position.y, p.position.z))
            Line2("Magnetic signal", "%.0f× noise · %.2f µT".format(p.contrast, p.fieldAmplitudeUt))
            p.referenceFieldUt?.let { ref ->
                Line2("Reference", "%.2f µT".format(ref))
                if (ref > 0) Line2("Deviation", "%+.0f%%".format((p.fieldAmplitudeUt - ref) / ref * 100))
            }
            p.currentA?.let { Line2("Current", "≈%.1f A".format(it)) }
            Line2("Arc signal", if (p.arcZ >= state.thresholds.warningZ) "Detected (%+.1f)".format(p.arcZ)
                else "Not detected (%+.1f)".format(p.arcZ))
            Line2("AI", when (val a = p.aiProbability) {
                null -> "Not available"
                else -> when {
                    a >= 0.7f -> "Confirmed"
                    a <= 0.3f -> "Not confirmed"
                    else -> "Uncertain"
                } + " (${(a * 100).toInt()}%)"
            })
            Line2("Measurement quality", when (p.quality) {
                Fusion.Quality.GOOD -> "Good"
                Fusion.Quality.FAIR -> "Fair (uncertain)"
                Fusion.Quality.POOR -> "Poor (not used)"
            })
            Line2("Taar result", p.outcome.title)
            TextButton(onClick = { why = !why }) { Text(if (why) "Hide why ▴" else "Why? ▾") }
            if (why) for (w in p.why) Text("• $w", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ZoneDetails(z: RoomMap.Zone) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = TaarPalette.RedSurface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("POSSIBLE ANOMALY ZONE", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Red,
                fontWeight = FontWeight.Bold)
            Line2("Strength", z.strength.name.lowercase().replaceFirstChar { it.uppercase() })
            Line2("Readings", "${z.pointIds.size} within %.1f m".format(z.radius))
            Text("Evidence", style = MaterialTheme.typography.titleSmall)
            for (e in z.evidence) Text("• $e", style = MaterialTheme.typography.bodySmall)
            Text("Further inspection recommended.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Hint("Based on measurements at the touched points. It does not show a specific hidden wire or fault.")
        }
    }
}

@Composable
private fun Legend3D() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((label, s) in listOf("Normal" to RoomMap.State.NORMAL, "Elevated" to RoomMap.State.ELEVATED,
            "Strong" to RoomMap.State.STRONG, "Possible anomaly" to RoomMap.State.POSSIBLE_ANOMALY)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).background(colourOf(s)))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---- projection ----

private class Cam(val yaw: Float, val pitch: Float, val zoom: Float, val pan: Offset, val w: Float, val h: Float) {
    val focal: Float get() = min(w, h) * 0.9f * zoom
}

/** An orbit camera around the scan's centre: rotate, zoom and pan, simple perspective. */
private class Viewer(map: RoomMap.Map) {
    private val centre: RoomMap.Vec3
    private val distance: Double

    init {
        val all = map.points.map { it.position } + map.planes.flatMap { it.polygon }
        centre = if (all.isEmpty()) RoomMap.Vec3(0.0, 0.0, 0.0)
        else all.reduce { a, b -> a + b } * (1.0 / all.size)
        val extent = all.maxOfOrNull { it.distanceTo(centre) } ?: 1.0
        distance = maxOf(1.5, extent * 2.4)
    }

    /** Screen position and depth, or null behind the camera. */
    fun project(v: RoomMap.Vec3, c: Cam): Pair<Offset, Double>? {
        val p = v - centre
        val cy = cos(c.yaw.toDouble()); val sy = sin(c.yaw.toDouble())
        val x1 = p.x * cy + p.z * sy
        val z1 = -p.x * sy + p.z * cy
        val cp = cos(c.pitch.toDouble()); val sp = sin(c.pitch.toDouble())
        val y2 = p.y * cp - z1 * sp
        val z2 = p.y * sp + z1 * cp
        val depth = z2 + distance
        if (depth < 0.1) return null
        val f = c.focal
        return Offset((c.w / 2 + c.pan.x + x1 * f / depth).toFloat(), (c.h / 2 + c.pan.y - y2 * f / depth).toFloat()) to depth
    }

    fun pixels(metres: Double, depth: Double, c: Cam): Float = (metres * c.focal / depth).toFloat()
}

// ---- colours ----

private fun colourOf(s: RoomMap.State): Color = when (s) {
    RoomMap.State.NORMAL -> TaarPalette.Green
    RoomMap.State.ELEVATED -> TaarPalette.Yellow
    RoomMap.State.STRONG -> TaarPalette.Amber
    RoomMap.State.POSSIBLE_ANOMALY -> TaarPalette.Red
    RoomMap.State.DISCARDED -> TaarPalette.Grey
}

/** Heat of measured activity: green → yellow → orange. Red is kept for possible anomalies. */
private fun heatColour(activity: Double): Color = when {
    activity >= RoomMap.STRONG_AT -> TaarPalette.Amber
    activity >= RoomMap.ELEVATED_AT -> TaarPalette.Yellow
    else -> TaarPalette.Green
}
