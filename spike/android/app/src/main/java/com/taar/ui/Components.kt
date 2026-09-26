package com.taar.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taar.domain.LineState
import com.taar.domain.Metrics
import kotlin.math.ln

/**
 * Shared pieces for every screen, so each one reads the same way: where you are,
 * what to do, what is happening.
 */

object TaarPalette {
    val Green = Color(0xFF3DDC84)
    val Amber = Color(0xFFFF9F45)
    val Red = Color(0xFFFF6B6B)
    val Grey = Color(0xFF9AA4B2)
    val Yellow = Color(0xFFFFD54A)
    val Blue = Color(0xFF7FB2FF)
    val RedSurface = Color(0xFF3A1D1F)
    val AmberSurface = Color(0xFF3A2A18)
}

/** A scrolling screen with a title, an optional back action and an optional subtitle. */
@Composable
fun TaarScreen(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) { Text("← Back") }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Grey) }
        content()
    }
}

/** Plain explanatory text. */
@Composable
fun Hint(text: String, color: Color = TaarPalette.Grey) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** A problem the person needs to see, in words. */
@Composable
fun ErrorCard(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TaarPalette.RedSurface),
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Red)
    }
}

/** A numbered instruction. */
@Composable
fun Instruction(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        NumberBadge(number.toString(), TaarPalette.Yellow)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NumberBadge(text: String, colour: Color) {
    Surface(shape = CircleShape, color = colour.copy(alpha = 0.18f), modifier = Modifier.size(26.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = colour, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

enum class StepState { DONE, TODO, OPTIONAL, PROBLEM }

/** One row of the home screen's setup checklist. */
@Composable
fun StepRow(
    number: Int,
    title: String,
    state: StepState,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val (mark, colour) = when (state) {
        StepState.DONE -> "✓" to TaarPalette.Green
        StepState.TODO -> number.toString() to TaarPalette.Yellow
        StepState.OPTIONAL -> number.toString() to TaarPalette.Grey
        StepState.PROBLEM -> "!" to TaarPalette.Red
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberBadge(mark, colour)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey)
            }
        }
        if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

/**
 * Shown while the phone is capturing. Says what is happening, which capture this
 * is, and fills over the three seconds a capture takes.
 */
@Composable
fun CapturingPanel(title: String, step: Int, total: Int, what: String) {
    val progress = remember(step) { Animatable(0f) }
    LaunchedEffect(step) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = CAPTURE_MILLIS, easing = LinearEasing))
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TaarPalette.AmberSurface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Keep the phone still", style = MaterialTheme.typography.titleLarge,
                color = TaarPalette.Yellow, fontWeight = FontWeight.SemiBold)
            Text(
                if (total > 1) "$title · capture $step of $total" else title,
                style = MaterialTheme.typography.titleSmall,
            )
            LinearProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = TaarPalette.Yellow,
            )
            Hint(what)
        }
    }
}

private const val CAPTURE_MILLIS = 3200

/**
 * Where a reading's contrast sits against the three bands, on a log scale from 1x
 * to 100x. The numbers alone meant nothing to the person holding the phone.
 */
@Composable
fun SignalMeter(contrast: Double) {
    val idle = LineState.contrastOf(Metrics.IDLE_CONFIDENCE)
    val live = LineState.contrastOf(Metrics.LIVE_CONFIDENCE)
    fun pos(c: Double): Float = (ln(c.coerceIn(1.0, 100.0)) / ln(100.0)).toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val h = 10.dp.toPx()
            val top = (size.height - h) / 2
            val r = CornerRadius(h / 2, h / 2)
            val xIdle = size.width * pos(idle)
            val xLive = size.width * pos(live)
            drawRoundRect(TaarPalette.Grey.copy(alpha = 0.45f), Offset(0f, top), Size(xIdle, h), r)
            drawRect(TaarPalette.Amber.copy(alpha = 0.55f), Offset(xIdle, top), Size(xLive - xIdle, h))
            drawRoundRect(TaarPalette.Yellow.copy(alpha = 0.75f), Offset(xLive, top), Size(size.width - xLive, h), r)
            val x = size.width * pos(contrast)
            drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(x, size.height / 2))
            drawCircle(Color(0xFF0D0F13), radius = 5.dp.toPx(), center = Offset(x, size.height / 2))
        }
        Row(Modifier.fillMaxWidth()) {
            Text("No current", style = MaterialTheme.typography.labelSmall, color = TaarPalette.Grey,
                modifier = Modifier.weight(pos(idle)))
            Text("Unclear", style = MaterialTheme.typography.labelSmall, color = TaarPalette.Amber,
                modifier = Modifier.weight(pos(live) - pos(idle) + 0.08f))
            Text("Current flowing", style = MaterialTheme.typography.labelSmall, color = TaarPalette.Yellow,
                modifier = Modifier.weight(1f - pos(live)))
        }
        Hint(
            "Below ${times(idle)} is room noise. Above ${times(live)} means current is flowing.",
        )
    }
}

/**
 * A contrast as "8×" or "13.5×". A plain %.0f turned the 13.5 live threshold into
 * "13×", which is not the number the app uses.
 */
fun times(contrast: Double): String =
    if (kotlin.math.abs(contrast - kotlin.math.round(contrast)) < 0.05) "%.0f×".format(contrast)
    else "%.1f×".format(contrast)

/** A label and a value on one line, value in monospace. */
@Composable
fun Metric(label: String, value: String, explain: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        explain?.let { Hint(it) }
    }
}
