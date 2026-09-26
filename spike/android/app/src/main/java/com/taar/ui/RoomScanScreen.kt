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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taar.domain.Circuit
import com.taar.domain.Fusion
import com.taar.domain.RoomMap
import com.taar.domain.RoomStore
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Room Map (experimental). Describe the room, place each measurement by tapping
 * where it is on a wall or the floor, then explore the 3D map.
 *
 * No camera: Taar's own pipeline measures at each spot, and the technician says
 * where the spot is. The map shows measured activity, never hidden wiring.
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
    var width by rememberSaveable { mutableStateOf("4") }
    var depth by rememberSaveable { mutableStateOf("3") }
    var height by rememberSaveable { mutableStateOf("2.7") }
    val size = Triple(
        width.toDoubleOrNull()?.coerceIn(1.0, 20.0) ?: 4.0,
        depth.toDoubleOrNull()?.coerceIn(1.0, 20.0) ?: 3.0,
        height.toDoubleOrNull()?.coerceIn(2.0, 6.0) ?: 2.7,
    )

    when {
        room == null -> Unit
        room.map != null -> MapStage(state, room, room.map, onNewScan = { scanning = false; onNewScan() }, onBack = onBack)
        scanning -> PlaceStage(state, room, size, onPinned, onCancelPoint, onMeasure,
            onFinish = { onFinish(RoomMap.boxRoom(size.first, size.second, size.third), emptyMap()); scanning = false },
            onBack = { scanning = false; onBack() })
        else -> SetupStage(state, circuit, width, depth, height,
            onSize = { w, d, h -> width = w; depth = d; height = h },
            onStart = { scanning = true }, onOpen = onOpen, onBack = onBack)
    }
}

// ---- 1. setup ----

@Composable
private fun SetupStage(
    state: TaarViewModel.UiState,
    circuit: Circuit?,
    width: String,
    depth: String,
    height: String,
    onSize: (String, String, String) -> Unit,
    onStart: () -> Unit,
    onOpen: (RoomStore.Session) -> Unit,
    onBack: () -> Unit,
) {
    TaarScreen(title = "Room Map", subtitle = "Experimental · one room", onBack = onBack) {
        Text("Place Taar's measurements on a 3D model of one room.", style = MaterialTheme.typography.bodyLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Instruction(1, "Enter the room's size below. A tape measure or a good guess is fine.")
                Instruction(2, "Pick the wall (or floor) a socket or spot is on, and tap where it is.")
                Instruction(3, "Press the phone flat on that spot and tap Measure. Hold still for 3 seconds.")
                Instruction(4, "Repeat around the room. 6 or more points, spread out.")
                Instruction(5, "Tap Finish to see the 3D map.")
            }
        }
        Text("Room size, metres", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SizeField("Width", width, Modifier.weight(1f)) { onSize(it, depth, height) }
            SizeField("Depth", depth, Modifier.weight(1f)) { onSize(width, it, height) }
            SizeField("Height", height, Modifier.weight(1f)) { onSize(width, depth, it) }
        }
        Hint("Width runs along the north wall; depth from the north wall to the south wall. Pick any wall " +
            "as \"north\" and stay consistent.")
        if (circuit?.baseline?.isSufficient != true) {
            ErrorCard("Record a reference first. For a room, add a circuit named after it and record its reference " +
                "at a quiet spot with nothing running nearby; each point is compared with it.")
        } else {
            Hint("Compared with the reference of \"${circuit.label}\".")
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Start room map", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (state.roomHistory.isNotEmpty()) {
            HorizontalDivider()
            Text("Saved room maps", style = MaterialTheme.typography.titleSmall)
            for (s in state.roomHistory.take(5)) {
                Text("${time(s.epochMillis)} · ${s.name} · ${s.points.size} points  ›",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(s) }.padding(vertical = 6.dp))
            }
        }
        Hint(RoomMap.CAVEAT)
    }
}

@Composable
private fun SizeField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

// ---- 2. place and measure ----

@Composable
private fun PlaceStage(
    state: TaarViewModel.UiState,
    room: TaarViewModel.Room,
    size: Triple<Double, Double, Double>,
    onPinned: () -> Int,
    onCancelPoint: () -> Unit,
    onMeasure: (Int, RoomMap.Vec3) -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    val (w, d, h) = size
    var surface by rememberSaveable { mutableStateOf(RoomMap.Surface.NORTH) }
    var target by remember { mutableStateOf<RoomMap.Vec3?>(null) }
    val pending = room.pendingId

    TaarScreen(title = "Room Map", subtitle = room.name, onBack = if (room.measuring) null else onBack) {
        ProgressCard(room)

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in RoomMap.Surface.values()) {
                FilterChip(
                    selected = surface == s,
                    onClick = { if (pending == null) { surface = s; target = null } },
                    label = { Text(s.label.substringBefore(" wall")) },
                )
            }
        }
        val (fw, fh) = RoomMap.faceSize(surface, w, d, h)
        Text(
            if (surface == RoomMap.Surface.FLOOR) "Floor from above · %.1f × %.1f m · north wall at the top".format(fw, fh)
            else "${surface.label}, facing it from inside · %.1f m wide × %.1f m high".format(fw, fh),
            style = MaterialTheme.typography.labelMedium, color = TaarPalette.Grey,
        )
        FacePicker(surface, room.points, target, w, d, fw, fh, enabled = pending == null && !room.measuring) {
            target = it
        }

        when {
            room.measuring -> Text("Measuring… hold the phone still on the spot (3 s)",
                style = MaterialTheme.typography.titleMedium, color = TaarPalette.Yellow)
            pending != null -> {
                Text("Press the phone flat on that spot, then tap Measure.", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { target?.let { onMeasure(pending, it) }; target = null },
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text("Measure", style = MaterialTheme.typography.titleMedium) }
                    OutlinedButton(onClick = { onCancelPoint(); target = null }, modifier = Modifier.height(52.dp)) {
                        Text("Cancel")
                    }
                }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onPinned() },
                    enabled = target != null,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(if (target == null) "Tap a spot above" else "Use this spot", style = MaterialTheme.typography.titleMedium) }
                OutlinedButton(onClick = onFinish, enabled = room.points.isNotEmpty(), modifier = Modifier.height(52.dp)) {
                    Text("Finish")
                }
            }
        }
        room.lastMessage?.let { Hint(it) }
    }
}

/** One wall (or the floor) as a rectangle to tap on, with the points already on it. */
@Composable
private fun FacePicker(
    surface: RoomMap.Surface,
    points: List<RoomMap.Point>,
    target: RoomMap.Vec3?,
    w: Double,
    d: Double,
    faceW: Double,
    faceH: Double,
    enabled: Boolean,
    onPick: (RoomMap.Vec3) -> Unit,
) {
    val floor = surface == RoomMap.Surface.FLOOR
    Canvas(
        Modifier.fillMaxWidth().aspectRatio((faceW / faceH).toFloat().coerceIn(0.6f, 3f))
            .background(Color(0xFF12161D))
            .pointerInput(surface, faceW, faceH, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { tap ->
                    val across = (tap.x / size.width * faceW).coerceIn(0.0, faceW)
                    // Walls: up from the floor, so flip the screen's y. Floor: down from the north wall.
                    val up = if (floor) (tap.y / size.height * faceH).coerceIn(0.0, faceH)
                    else ((1 - tap.y / size.height) * faceH).coerceIn(0.0, faceH)
                    onPick(RoomMap.place(surface, across, up, w, d))
                }
            },
    ) {
        fun toScreen(a: Double, u: Double) = Offset(
            (a / faceW * size.width).toFloat(),
            (if (floor) u / faceH * size.height else (1 - u / faceH) * size.height).toFloat(),
        )
        // Half-metre grid, so a spot can be placed by eye.
        var g = 0.5
        while (g < faceW) { val x = (g / faceW * size.width).toFloat(); drawLine(Color(0xFF2A313C), Offset(x, 0f), Offset(x, size.height), 1f); g += 0.5 }
        g = 0.5
        while (g < faceH) { val y = (g / faceH * size.height).toFloat(); drawLine(Color(0xFF2A313C), Offset(0f, y), Offset(size.width, y), 1f); g += 0.5 }
        drawRect(TaarPalette.Blue, style = Stroke(width = 3f))
        for (p in points) {
            val (a, u) = RoomMap.onFace(surface, p.position, w, d) ?: continue
            drawCircle(colourOf(p.state), 14f, toScreen(a, u))
        }
        target?.let { t ->
            RoomMap.onFace(surface, t, w, d)?.let { (a, u) ->
                val at = toScreen(a, u)
                drawCircle(Color.White, 18f, at, style = Stroke(width = 4f))
                drawLine(Color.White, at - Offset(26f, 0f), at + Offset(26f, 0f), 2f)
                drawLine(Color.White, at - Offset(0f, 26f), at + Offset(0f, 26f), 2f)
            }
        }
    }
}

@Composable
private fun ProgressCard(room: TaarViewModel.Room) {
    val accepted = room.points.count { it.accepted }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("ROOM MAP", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Yellow)
            Line2("Measurements", "${room.points.size}  (${accepted} good · ${room.points.size - accepted} rejected)")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Toward a map", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp))
                Box(Modifier.weight(1f).height(8.dp).background(TaarPalette.Grey.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxWidth((accepted.toFloat() / RoomMap.MIN_POINTS).coerceIn(0.01f, 1f)).height(8.dp)
                        .background(TaarPalette.Green))
                }
                Text("$accepted/${RoomMap.MIN_POINTS}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                when {
                    room.measuring -> "Collecting measurement…"
                    room.pendingId != null -> "Spot chosen · waiting for Measure"
                    accepted < RoomMap.MIN_POINTS -> "Collecting measurements… ${RoomMap.MIN_POINTS - accepted} more for a map"
                    else -> "Enough for a map · add more or tap Finish"
                },
                style = MaterialTheme.typography.labelMedium, color = TaarPalette.Grey,
            )
        }
    }
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
