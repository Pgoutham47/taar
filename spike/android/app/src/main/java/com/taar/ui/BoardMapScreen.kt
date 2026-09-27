package com.taar.ui

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taar.domain.BoardMap
import com.taar.domain.Circuit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Board Map: a photo of the distribution board with a numbered dot on each
 * breaker, coloured by that circuit's latest result.
 *
 * Two jobs on one screen. Setting up: take the photo, then pick a circuit and tap
 * where its breaker is. Using it: tap a dot to see that circuit's last result and
 * measure it again. The photo never leaves the phone.
 */
@Composable
fun BoardMapScreen(
    state: TaarViewModel.UiState,
    photos: BoardPhotos,
    onPlace: (circuitId: String, x: Double, y: Double) -> Unit,
    onAddAt: (label: String, breakerRatingA: Double?, x: Double, y: Double) -> Unit,
    onRemove: (circuitId: String) -> Unit,
    onMeasure: (Circuit) -> Unit,
    onCircuits: () -> Unit,
    onBack: () -> Unit,
) {
    val board = state.installation
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    /** The circuit waiting for a tap on the photo. */
    var placing by remember { mutableStateOf<String?>(null) }
    /** The circuit whose dot was tapped. */
    var selected by remember { mutableStateOf<String?>(null) }
    /** Where on the photo a new switch is being added, while its name is asked for. */
    var adding by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val photo by produceState<Bitmap?>(null, board?.id, version) {
        value = board?.id?.let { id -> withContext(Dispatchers.IO) { photos.load(id) } }
    }

    fun keep(save: suspend () -> Boolean) {
        saving = true
        photoError = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { save() }
            saving = false
            if (ok) version++ else photoError = "That photo could not be read. Try taking it again."
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val id = board?.id
        if (taken && id != null) keep { photos.acceptCamera(id) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = board?.id
        if (uri != null && id != null) keep { photos.importFrom(uri, id) }
    }
    val takePhoto = {
        board?.id?.let { id ->
            try {
                camera.launch(photos.cameraTarget(id))
            } catch (e: ActivityNotFoundException) {
                photoError = "No camera app found. Choose a photo from the gallery instead."
            }
        }
        Unit
    }
    val pickPhoto = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    /** Adding switches happens on the photo at full size: at card size a switch is a few millimetres wide. */
    var fullScreen by remember { mutableStateOf(false) }
    BackHandler(enabled = fullScreen) { fullScreen = false; placing = null; adding = null }

    val circuits = board?.circuits ?: emptyList()
    val pins = state.boardMap.pins
    val dots = state.boardDots
    val ordered = BoardMap.order(circuits.map { it.id }, pins).mapNotNull { id -> circuits.find { it.id == id } }
    val number = ordered.withIndex().associate { (i, c) -> c.id to i + 1 }

    val full = photo
    if (fullScreen && board != null && full != null) {
        FullScreenPhoto(
            photo = full, circuits = ordered, number = number, pins = pins, dots = dots,
            selected = selected, placing = placing, pending = adding,
            onTap = { x, y ->
                val id = placing
                when {
                    id != null -> { onPlace(id, x, y); placing = null; selected = id }
                    selected != null -> selected = null
                    else -> adding = x to y
                }
            },
            onDot = { id -> selected = if (selected == id) null else id; placing = null },
            onMove = { id -> placing = id; selected = null },
            onRemove = { id -> onRemove(id); selected = null },
            onCancel = { placing = null; selected = null },
            onDone = { fullScreen = false; placing = null },
        )
    } else TaarScreen(title = "Board Map", subtitle = board?.name ?: "No board chosen", onBack = onBack) {
        if (board == null) {
            Banner("Choose a board first.", Tone.WARNING)
            PrimaryButton("Circuits and boards", onClick = onCircuits)
            return@TaarScreen
        }
        photoError?.let { ErrorCard(it) }

        val bmp = photo
        if (bmp == null) {
            TaarCard {
                SectionLabel("Step 1 · Photo")
                Text("Take a photo of the board", style = MaterialTheme.typography.titleLarge)
                Hint("Open the board's cover and stand back so every breaker is in the picture. The photo stays on this phone.")
                PrimaryButton(if (saving) "Saving photo…" else "Take photo", onClick = takePhoto, enabled = !saving)
                SecondaryButton("Choose from gallery", onClick = pickPhoto, enabled = !saving)
            }
            return@TaarScreen
        }

        if (pins.isEmpty()) {
            Banner("Tap the photo to open it full screen. Then tap each switch and give it a name.",
                Tone.INFO, title = "Step 2 · Add the switches")
        }

        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .aspectRatio(bmp.width.toFloat() / bmp.height)
                .clip(MaterialTheme.shapes.medium)
                .border(1.dp, TaarPalette.Outline, MaterialTheme.shapes.medium),
        ) {
            val w = maxWidth
            val h = maxHeight
            val image = remember(bmp) { bmp.asImageBitmap() }
            Image(image, contentDescription = "Photo of ${board.name}", contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize())
            // At this size a tap only opens the photo full screen (or closes an open
            // dot); switches are added there. Taps on a dot are taken by the dot itself.
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { if (selected != null) selected = null else fullScreen = true }
                },
            )
            for (c in circuits) {
                val pin = pins[c.id] ?: continue
                val mark = dots[c.id]?.mark ?: BoardMap.Mark.NOT_MEASURED
                val half = dotSize(selected == c.id) / 2
                Dot(
                    number = number[c.id] ?: 0, mark = mark, selected = selected == c.id,
                    modifier = Modifier.offset(w * pin.x.toFloat() - half, h * pin.y.toFloat() - half),
                    onClick = { selected = if (selected == c.id) null else c.id },
                )
            }
            Text(
                "Tap to open full screen", style = MaterialTheme.typography.labelLarge, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        val chosen = circuits.find { it.id == selected }
        if (chosen != null) {
            val dot = dots[chosen.id]
            TaarCard(tone = toneOf(dot?.mark)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Dot(number[chosen.id] ?: 0, dot?.mark ?: BoardMap.Mark.NOT_MEASURED, selected = false)
                    Column(Modifier.weight(1f)) {
                        Text(chosen.label, style = MaterialTheme.typography.titleLarge)
                        Text(
                            listOfNotNull(dot?.mark?.label, dot?.epochMillis?.let { time(it) },
                                chosen.breakerRatingA?.let { "%.0f A breaker".format(it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey,
                        )
                    }
                }
                dot?.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                PrimaryButton(
                    if (chosen.baseline?.isSufficient == true) "Measure this circuit" else "Record its normal, then measure",
                    onClick = { onMeasure(chosen) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Move dot", onClick = { placing = chosen.id; selected = null; fullScreen = true },
                        modifier = Modifier.weight(1f))
                    SecondaryButton("Remove dot", onClick = { onRemove(chosen.id); selected = null }, modifier = Modifier.weight(1f))
                }
            }
        }

        if (circuits.isNotEmpty()) {
            // Once switches are on the photo, the summary is about the photo; circuits
            // never placed on it (such as the starter board's) would only confuse it.
            val onPhoto = circuits.filter { pins.isEmpty() || it.id in pins }
            val summary = BoardMap.summary(onPhoto.mapNotNull { dots[it.id] })
            if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.titleSmall)
            MarkLegend()

            TaarCard {
                SectionLabel("Circuits")
                Hint("To add switches, open the photo full screen and tap them." +
                    if (pins.size < circuits.size) " To put an existing circuit on the photo, tap Place." else "")
                ordered.forEachIndexed { i, c ->
                    if (i > 0) Rule()
                    val dot = dots[c.id]
                    val pinned = c.id in pins
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .clickable {
                                if (pinned) { selected = c.id; placing = null }
                                else { placing = c.id; selected = null; fullScreen = true }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Dot(i + 1, dot?.mark ?: BoardMap.Mark.NOT_MEASURED, selected = false)
                        Column(Modifier.weight(1f)) {
                            Text(c.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(dot?.mark?.label, dot?.epochMillis?.let { time(it) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey,
                            )
                        }
                        Text(
                            when {
                                placing == c.id -> "Tap photo"
                                pinned -> "Open"
                                else -> "Place"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (pinned) TaarPalette.Grey else TaarPalette.Yellow,
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(if (saving) "Saving…" else "Retake photo", onClick = takePhoto, enabled = !saving,
                modifier = Modifier.weight(1f))
            SecondaryButton("From gallery", onClick = pickPhoto, enabled = !saving, modifier = Modifier.weight(1f))
        }
        SecondaryButton("Rename or remove circuits", onClick = onCircuits)

        Hint(
            "Each dot shows the latest result for that circuit and when it was taken. Grey means no current was seen, " +
                "not that the wire is dead: the phone senses current, not voltage.",
        )
    }

    adding?.let { (x, y) ->
        AddSwitchDialog(
            nextNumber = pins.keys.count { id -> circuits.any { it.id == id } } + 1,
            unplaced = circuits.filter { it.id !in pins },
            onAdd = { label, rating -> onAddAt(label, rating, x, y); adding = null },
            onUseExisting = { id -> onPlace(id, x, y); selected = id; adding = null },
            onCancel = { adding = null },
        )
    }
}

/**
 * The photo across the whole screen, for adding and moving switches. Pinch to
 * zoom and drag to pan; a tap goes to [onTap] as a fraction of the photo, wherever
 * it is zoomed. Dots keep their size on screen at any zoom, so they never hide the
 * switch they mark.
 */
@Composable
private fun FullScreenPhoto(
    photo: Bitmap,
    circuits: List<Circuit>,
    number: Map<String, Int>,
    pins: Map<String, BoardMap.Pin>,
    dots: Map<String, BoardMap.Dot>,
    selected: String?,
    placing: String?,
    pending: Pair<Double, Double>?,
    onTap: (Double, Double) -> Unit,
    onDot: (String) -> Unit,
    onMove: (String) -> Unit,
    onRemove: (String) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val image = remember(photo) { photo.asImageBitmap() }
    val aspect = photo.width.toFloat() / photo.height
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val tap by rememberUpdatedState(onTap)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
            val wide = maxWidth / maxHeight > aspect
            val fitW = if (wide) maxHeight * aspect else maxWidth
            val fitH = if (wide) maxHeight else maxWidth / aspect
            val density = LocalDensity.current
            val fitWpx = with(density) { fitW.toPx() }
            val fitHpx = with(density) { fitH.toPx() }
            val viewW = constraints.maxWidth.toFloat()
            val viewH = constraints.maxHeight.toFloat()
            Box(
                Modifier.fillMaxSize().pointerInput(fitWpx, fitHpx, viewW, viewH) {
                    detectTransformGestures { _, change, zoom, _ ->
                        val s = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        val maxX = maxOf(0f, (fitWpx * s - viewW) / 2)
                        val maxY = maxOf(0f, (fitHpx * s - viewH) / 2)
                        scale = s
                        pan = Offset((pan.x + change.x).coerceIn(-maxX, maxX), (pan.y + change.y).coerceIn(-maxY, maxY))
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(fitW, fitH)
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }
                        .pointerInput(Unit) {
                            detectTapGestures { at ->
                                tap((at.x / size.width).toDouble().coerceIn(0.0, 1.0),
                                    (at.y / size.height).toDouble().coerceIn(0.0, 1.0))
                            }
                        },
                ) {
                    Image(image, contentDescription = "Board photo", contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize())
                    val keepSize = Modifier.graphicsLayer { scaleX = 1f / scale; scaleY = 1f / scale }
                    pending?.let { (x, y) ->
                        Box(
                            Modifier.offset(fitW * x.toFloat() - DOT / 2, fitH * y.toFloat() - DOT / 2).then(keepSize)
                                .size(DOT).clip(CircleShape).background(TaarPalette.Glow)
                                .border(BorderStroke(2.dp, Color.White), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text("+", fontWeight = FontWeight.Bold, color = TaarPalette.Panel) }
                    }
                    for (c in circuits) {
                        val pin = pins[c.id] ?: continue
                        val half = dotSize(selected == c.id) / 2
                        Dot(
                            number = number[c.id] ?: 0, mark = dots[c.id]?.mark ?: BoardMap.Mark.NOT_MEASURED,
                            selected = selected == c.id,
                            modifier = Modifier.offset(fitW * pin.x.toFloat() - half, fitH * pin.y.toFloat() - half)
                                .then(keepSize),
                            onClick = { onDot(c.id) },
                        )
                    }
                }
            }
        }

        // Top: what to do, and the way out.
        Row(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.65f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Tap each switch", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(
                    "Pinch to zoom · ${pins.keys.count { id -> circuits.any { it.id == id } }} on the photo",
                    style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey,
                )
            }
            Text(
                "Done", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Panel,
                modifier = Modifier.clip(CircleShape).background(TaarPalette.Glow).clickable(onClick = onDone)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        // Bottom: the dot being placed, the dot tapped, or a reminder.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.75f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val chosen = circuits.find { it.id == selected }
            val moving = circuits.find { it.id == placing }
            when {
                moving != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tap where ${moving.label}'s switch is", style = MaterialTheme.typography.bodyLarge,
                        color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel", color = TaarPalette.Glow) }
                }
                chosen != null -> {
                    val dot = dots[chosen.id]
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Dot(number[chosen.id] ?: 0, dot?.mark ?: BoardMap.Mark.NOT_MEASURED, selected = false)
                        Column(Modifier.weight(1f)) {
                            Text(chosen.label, style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Text(dot?.mark?.label ?: "", style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey)
                        }
                    }
                    Row {
                        TextButton(onClick = { onMove(chosen.id) }) { Text("Move", color = TaarPalette.Glow) }
                        TextButton(onClick = { onRemove(chosen.id) }) { Text("Remove", color = TaarPalette.Red) }
                        TextButton(onClick = onCancel) { Text("Close", color = TaarPalette.Grey) }
                    }
                }
                else -> Text(
                    "Tap a switch to add it. Tap a dot to move or remove it.",
                    style = MaterialTheme.typography.bodyLarge, color = Color.White,
                )
            }
        }
    }
}

/** Far enough to make one switch fill the screen on a crowded board. */
private const val MAX_ZOOM = 6f

/**
 * Names the switch just tapped on the photo. Offers the board's circuits that have
 * no dot yet too, so a board set up by hand earlier can be mapped without retyping.
 */
@Composable
private fun AddSwitchDialog(
    nextNumber: Int,
    unplaced: List<Circuit>,
    onAdd: (label: String, breakerRatingA: Double?) -> Unit,
    onUseExisting: (circuitId: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = TaarPalette.Surface,
        title = { Text("Name switch $nextNumber", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Hint("What does this switch control? Use the label on the board if it has one.")
                OutlinedTextField(
                    name, { name = it }, label = { Text("Name, e.g. Kitchen") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    rating, { v -> rating = v.filter { it.isDigit() || it == '.' } },
                    label = { Text("Rating in amps, if printed (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (unplaced.isNotEmpty()) {
                    Text("Or put an existing circuit here", style = MaterialTheme.typography.labelLarge,
                        color = TaarPalette.Grey)
                    for (c in unplaced) {
                        Text(
                            c.label, style = MaterialTheme.typography.bodyLarge, color = TaarPalette.Yellow,
                            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                .clickable { onUseExisting(c.id) }.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name.trim(), rating.toDoubleOrNull()) }, enabled = name.isNotBlank()) {
                Text("Add", color = if (name.isNotBlank()) TaarPalette.Yellow else TaarPalette.Faint)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = TaarPalette.Grey) } },
    )
}

private val DOT = 30.dp

private fun dotSize(selected: Boolean) = if (selected) DOT + 8.dp else DOT

internal fun colourOf(mark: BoardMap.Mark): Color = when (mark) {
    BoardMap.Mark.PROBLEM -> TaarPalette.Red
    BoardMap.Mark.CHECK -> TaarPalette.Amber
    BoardMap.Mark.UNCLEAR -> TaarPalette.Blue
    BoardMap.Mark.LIVE -> TaarPalette.Green
    BoardMap.Mark.OFF -> TaarPalette.Grey
    BoardMap.Mark.NOT_MEASURED -> TaarPalette.Grey
}

internal fun toneOf(mark: BoardMap.Mark?): Tone = when (mark) {
    BoardMap.Mark.PROBLEM -> Tone.DANGER
    BoardMap.Mark.CHECK -> Tone.WARNING
    BoardMap.Mark.UNCLEAR -> Tone.INFO
    BoardMap.Mark.LIVE -> Tone.SUCCESS
    else -> Tone.NEUTRAL
}

/** A numbered dot. Unmeasured dots are hollow, so they never pass for a result. */
@Composable
private fun Dot(
    number: Int,
    mark: BoardMap.Mark,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val fill = colourOf(mark)
    Box(
        modifier
            .size(dotSize(selected))
            .clip(CircleShape)
            .background(fill)
            .border(BorderStroke(if (selected) 3.dp else 2.dp, Color.White), CircleShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
            color = if (mark == BoardMap.Mark.NOT_MEASURED) Color.White else TaarPalette.Panel,
        )
    }
}

@Composable
internal fun MarkLegend() {
    val marks = BoardMap.Mark.entries
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in marks.chunked(2)) {
            Row(Modifier.fillMaxWidth()) {
                for (m in row) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(colourOf(m))
                            .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape))
                        Text(m.label, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey)
                    }
                }
            }
        }
    }
}
