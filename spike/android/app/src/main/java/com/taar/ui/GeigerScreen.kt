package com.taar.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taar.domain.Geiger
import com.taar.sensor.MagStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Geiger mode: sweep the phone along a wall or cable and it clicks faster as it
 * nears a wire carrying current.
 *
 * Needs no circuit, no reference and no microphone: only the magnetometer, read
 * continuously, and the same 50 Hz detector a measurement uses. Nothing is saved.
 */
@Composable
fun GeigerScreen(stream: MagStream, onBack: () -> Unit) {
    val context = LocalContext.current
    val sound = remember { GeigerSound(context) }
    DisposableEffect(Unit) { onDispose { sound.close() } }

    // A sweep takes both hands and a minute; the screen must not dim halfway along a wall.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var scale by remember { mutableStateOf(Geiger.Scale()) }
    var contrast by remember { mutableDoubleStateOf(0.0) }
    var peak by remember { mutableDoubleStateOf(0.0) }
    var rateHz by remember { mutableDoubleStateOf(0.0) }
    var clicks by remember { mutableIntStateOf(0) }
    var soundOn by rememberSaveable { mutableStateOf(true) }
    var vibrateOn by rememberSaveable { mutableStateOf(true) }
    val history = remember { mutableStateListOf<Double>() }

    val level = scale.level(contrast)
    val zone = Geiger.Zone.of(level)

    // Analysis: the newest window, every UPDATE_MILLIS, off the main thread.
    LaunchedEffect(Unit) {
        if (!stream.isAvailable) return@LaunchedEffect
        stream.start()
        val smoother = Geiger.Smoother()
        try {
            while (isActive) {
                delay(Geiger.UPDATE_MILLIS)
                val result = withContext(Dispatchers.Default) {
                    stream.window(Geiger.WINDOW_SECONDS)?.let { w ->
                        w.rateHz to Geiger.contrast(w.tSeconds, w.xUt, w.yUt, w.zUt)
                    }
                } ?: continue
                rateHz = result.first
                contrast = smoother.update(result.second)
                peak = maxOf(peak, contrast)
                history.add(contrast)
                if (history.size > HISTORY_POINTS) history.removeAt(0)
            }
        } finally {
            stream.stop()
        }
    }

    // Clicks: random at the current rate, checked every few milliseconds.
    val rate by rememberUpdatedState(Geiger.clicksPerSecond(level))
    val withSound by rememberUpdatedState(soundOn)
    val withVibration by rememberUpdatedState(vibrateOn)
    LaunchedEffect(Unit) {
        val rnd = Random.Default
        var last = System.nanoTime()
        while (isActive) {
            delay(TICK_MILLIS)
            // A 10 ms delay on the main thread often runs long; use the time that passed.
            val now = System.nanoTime()
            val elapsed = (now - last) / 1e9
            last = now
            if (Geiger.clickInTick(rate, elapsed, rnd.nextDouble())) {
                sound.click(withSound, withVibration)
                clicks++
            }
        }
    }

    TaarScreen(
        title = "Geiger Mode",
        subtitle = "Clicks faster as the phone nears a wire carrying current",
        onBack = onBack,
    ) {
        if (!stream.isAvailable) {
            ErrorCard("This phone has no magnetometer, so Geiger mode cannot run.")
            return@TaarScreen
        }

        TaarCard(tone = when (zone) {
            Geiger.Zone.QUIET -> Tone.NEUTRAL
            Geiger.Zone.NEAR -> Tone.WARNING
            Geiger.Zone.CLOSE -> Tone.DANGER
        }) {
            Gauge(level, contrast, zone, clicks, started = history.isNotEmpty())
            Text(
                if (history.isEmpty()) "Starting the sensor…" else zone.title,
                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (history.isEmpty()) "Hold the phone still for a moment" else zone.detail,
                style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Grey, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        TaarCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Last 10 seconds", Modifier.weight(1f))
                Text(
                    "strongest ${times(peak)}", style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace, color = TaarPalette.Grey,
                )
            }
            History(history, scale)
            Hint("Sweep slowly and watch for the peak: the wire runs under the spot where the line is highest.")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Sound", soundOn) { soundOn = !soundOn }
            Chip("Vibration", vibrateOn) { vibrateOn = !vibrateOn }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Set quiet here", onClick = {
                scale = Geiger.Scale().quietAt(contrast)
                peak = contrast
            }, modifier = Modifier.weight(1f))
            SecondaryButton("Reset", onClick = {
                scale = Geiger.Scale()
                peak = contrast
            }, modifier = Modifier.weight(1f))
        }
        Hint(
            "Clicking with no cable nearby? Chargers and laptops nearby carry current too. Hold the phone " +
                "away from cables and tap Set quiet here. Clicks now start above ${times(scale.quiet)}.",
        )

        TaarCard {
            SectionLabel("How to use")
            Instruction(1, "Switch on something that runs on the wire: a kettle, heater or iron.")
            Instruction(2, "Hold the phone flat against the wall or cable, and move it slowly.")
            Instruction(3, "Follow the faster clicks. The strongest spot is where the wire runs.")
        }

        Banner(
            "It only finds a wire while current flows in it. No clicks does not mean there is no wire, " +
                "and never means it is safe to drill or touch.",
            Tone.WARNING, title = "What it cannot tell you",
        )

        Metric(
            "Sensor", "%.0f Hz · %.1f s window".format(rateHz, Geiger.WINDOW_SECONDS),
            explain = "The same 50 Hz detector as a measurement, recomputed ${1000 / Geiger.UPDATE_MILLIS} times a second.",
        )
    }
}

private fun zoneColour(zone: Geiger.Zone) = when (zone) {
    Geiger.Zone.QUIET -> TaarPalette.Grey
    Geiger.Zone.NEAR -> TaarPalette.Yellow
    Geiger.Zone.CLOSE -> TaarPalette.Red
}

/** A 270° dial for the level, with a flash on every click. */
@Composable
private fun Gauge(level: Double, contrast: Double, zone: Geiger.Zone, clicks: Int, started: Boolean) {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(clicks) {
        if (clicks == 0) return@LaunchedEffect
        flash.snapTo(1f)
        flash.animateTo(0f, tween(140))
    }
    val colour = zoneColour(zone)
    Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp)) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawCircle(colour.copy(alpha = 0.22f * flash.value), radius = size.minDimension / 2 - stroke * 1.4f)
            drawArc(TaarPalette.Outline, 135f, 270f, false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round))
            if (level > 0) {
                drawArc(colour, 135f, (270f * level).toFloat().coerceAtLeast(2f), false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (!started) "–" else if (contrast >= 10) "%.0f×".format(contrast) else "%.1f×".format(contrast),
                fontSize = 52.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = colour,
            )
            Text("50 Hz signal", style = MaterialTheme.typography.labelMedium, color = TaarPalette.Grey)
        }
    }
}

/** The recent levels as a line, with the "very close" level dashed. */
@Composable
private fun History(points: List<Double>, scale: Geiger.Scale) {
    Canvas(Modifier.fillMaxWidth().height(80.dp).background(Color(0xFF12161D), MaterialTheme.shapes.small)) {
        val w = size.width
        val h = size.height
        val close = h * (1f - Geiger.Zone.CLOSE_LEVEL.toFloat())
        drawLine(TaarPalette.Red.copy(alpha = 0.5f), Offset(0f, close), Offset(w, close), 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        if (points.size < 2) return@Canvas
        val dx = w / (HISTORY_POINTS - 1)
        val x0 = w - (points.size - 1) * dx
        val path = Path()
        points.forEachIndexed { i, c ->
            val y = h * (1f - scale.level(c).toFloat()) * 0.94f + h * 0.03f
            if (i == 0) path.moveTo(x0, y) else path.lineTo(x0 + i * dx, y)
        }
        drawPath(path, TaarPalette.Yellow, style = Stroke(width = 4f))
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (on) TaarPalette.Yellow.copy(alpha = 0.14f) else TaarPalette.Surface,
        border = BorderStroke(1.dp, if (on) TaarPalette.Yellow.copy(alpha = 0.6f) else TaarPalette.Outline),
    ) {
        Text(
            (if (on) "$label on" else "$label off"), style = MaterialTheme.typography.labelLarge,
            color = if (on) TaarPalette.Yellow else TaarPalette.Grey,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** About 10 s of updates. */
private val HISTORY_POINTS = (10_000 / Geiger.UPDATE_MILLIS).toInt()

/** How often the click loop decides whether to click. */
private const val TICK_MILLIS = 10L
