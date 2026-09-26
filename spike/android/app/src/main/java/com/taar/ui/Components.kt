package com.taar.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taar.domain.LineState
import com.taar.domain.Metrics
import kotlin.math.ln

/**
 * Taar's design system: colours, type, shapes and the handful of pieces every
 * screen is built from, so the whole app reads as one instrument.
 *
 * Dark by design: it is used in basements and plant rooms, and a bright screen in
 * a dim room is the thing a technician notices first.
 */
object TaarPalette {
    val Background = Color(0xFF0B0E14)
    val Surface = Color(0xFF141923)
    val SurfaceHigh = Color(0xFF1B212D)
    val Outline = Color(0xFF262D3A)
    val Text = Color(0xFFF2F4F8)
    val Grey = Color(0xFFA0A8B8)
    val Faint = Color(0xFF6B7385)

    val Yellow = Color(0xFFFFC940)   // brand yellow
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFF5A524)
    val Red = Color(0xFFF87171)
    val Blue = Color(0xFF60A5FA)

    val RedSurface = Color(0xFF2A1719)
    val AmberSurface = Color(0xFF2A2013)
    val GreenSurface = Color(0xFF10251C)
    val BlueSurface = Color(0xFF111C2C)
}

private val TaarColors = darkColorScheme(
    primary = TaarPalette.Yellow,
    onPrimary = Color(0xFF1A1300),
    secondary = TaarPalette.Blue,
    onSecondary = Color(0xFF06121F),
    background = TaarPalette.Background,
    onBackground = TaarPalette.Text,
    surface = TaarPalette.Background,
    onSurface = TaarPalette.Text,
    surfaceVariant = TaarPalette.SurfaceHigh,
    onSurfaceVariant = TaarPalette.Grey,
    surfaceContainerLowest = TaarPalette.Background,
    surfaceContainerLow = TaarPalette.Surface,
    surfaceContainer = TaarPalette.Surface,
    surfaceContainerHigh = TaarPalette.Surface,
    surfaceContainerHighest = TaarPalette.Surface,
    outline = TaarPalette.Outline,
    outlineVariant = TaarPalette.Outline,
    error = TaarPalette.Red,
    secondaryContainer = TaarPalette.SurfaceHigh,
    onSecondaryContainer = TaarPalette.Text,
)

private val TaarType = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = t.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
        labelLarge = t.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.4.sp),
    )
}

private val TaarShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TaarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TaarColors, typography = TaarType, shapes = TaarShapes, content = content)
}

/** Spacing scale. Everything lines up on multiples of four. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val gutter = 20.dp
}

// ---- screen frame ----

/**
 * A scrolling screen: an icon back button, a large title, and content on a 20dp
 * gutter. [bottomInset] is false inside the tab shell, whose bar already sits above
 * the system navigation.
 */
@Composable
fun TaarScreen(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    bottomInset: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sides = if (bottomInset) WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical
    else WindowInsetsSides.Horizontal + WindowInsetsSides.Top
    Column(Modifier.fillMaxSize().background(TaarPalette.Background).windowInsetsPadding(WindowInsets.safeDrawing.only(sides))) {
        // Task screens keep the bar's space even while Back is hidden (during a
        // capture), so the title never jumps. Tab screens have no bar.
        val bar = onBack != null || bottomInset
        if (bar) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TaarPalette.Text)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter)
                .padding(top = if (bar) Space.xs else Space.xl, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Grey) }
            }
            content()
        }
    }
}

// ---- surfaces ----

enum class Tone { NEUTRAL, INFO, SUCCESS, WARNING, DANGER }

private fun surfaceOf(t: Tone) = when (t) {
    Tone.NEUTRAL -> TaarPalette.Surface
    Tone.INFO -> TaarPalette.BlueSurface
    Tone.SUCCESS -> TaarPalette.GreenSurface
    Tone.WARNING -> TaarPalette.AmberSurface
    Tone.DANGER -> TaarPalette.RedSurface
}

fun accentOf(t: Tone) = when (t) {
    Tone.NEUTRAL -> TaarPalette.Grey
    Tone.INFO -> TaarPalette.Blue
    Tone.SUCCESS -> TaarPalette.Green
    Tone.WARNING -> TaarPalette.Amber
    Tone.DANGER -> TaarPalette.Red
}

/** The one card: rounded, hairline outline, 18dp inside. */
@Composable
fun TaarCard(
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NEUTRAL,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = if (tone == Tone.NEUTRAL) TaarPalette.Outline else accentOf(tone).copy(alpha = 0.35f)
    Surface(
        modifier = modifier.fillMaxWidth().let { if (onClick != null) it.clip(MaterialTheme.shapes.large).clickable(onClick = onClick) else it },
        shape = MaterialTheme.shapes.large,
        color = surfaceOf(tone),
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(Space.m), content = content)
    }
}

/** A small uppercase label above a group. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = TaarPalette.Faint, modifier = modifier)
}

/** A rounded pill carrying a short status. */
@Composable
fun StatusPill(text: String, tone: Tone) {
    val c = accentOf(tone)
    Row(
        Modifier.clip(CircleShape).background(c.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        Text(text, style = MaterialTheme.typography.labelMedium, color = c)
    }
}

/** A message with an icon: information, a warning, or a problem. */
@Composable
fun Banner(text: String, tone: Tone = Tone.INFO, title: String? = null) {
    val c = accentOf(tone)
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(surfaceOf(tone)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(
            if (tone == Tone.DANGER || tone == Tone.WARNING) Icons.Filled.Warning else Icons.Filled.Info,
            contentDescription = null, tint = c, modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = c) }
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (title == null) c else TaarPalette.Text)
        }
    }
}

/** A tappable row: icon, title, subtitle, and a chevron or a trailing piece. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = TaarPalette.Yellow,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Box(
                Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey) }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = TaarPalette.Faint)
        }
    }
}

// ---- buttons ----

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                  colour: Color = TaarPalette.Yellow) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = colour, contentColor = Color(0xFF14110A),
            disabledContainerColor = TaarPalette.SurfaceHigh, disabledContentColor = TaarPalette.Faint),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, TaarPalette.Outline),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) TaarPalette.Text else TaarPalette.Faint) }
}

// ---- text ----

/** Plain explanatory text. */
@Composable
fun Hint(text: String, color: Color = TaarPalette.Grey) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** A problem the person needs to see, in words. */
@Composable
fun ErrorCard(text: String) = Banner(text, Tone.DANGER)

/** A numbered instruction. */
@Composable
fun Instruction(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
        NumberBadge(number.toString(), TaarPalette.Yellow, filled = false)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 2.dp))
    }
}

@Composable
private fun NumberBadge(text: String, colour: Color, filled: Boolean) {
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(if (filled) colour else colour.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (text == "✓") Icon(Icons.Filled.Check, null, tint = Color(0xFF0B0E14), modifier = Modifier.size(16.dp))
        else Text(text, color = if (filled) Color(0xFF0B0E14) else colour, style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold)
    }
}

enum class StepState { DONE, TODO, OPTIONAL, PROBLEM }

/** One step of a checklist: badge, title, detail, and its action. */
@Composable
fun StepRow(
    number: Int,
    title: String,
    state: StepState,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val (mark, colour, filled) = when (state) {
        StepState.DONE -> Triple("✓", TaarPalette.Green, true)
        StepState.TODO -> Triple(number.toString(), TaarPalette.Yellow, false)
        StepState.OPTIONAL -> Triple(number.toString(), TaarPalette.Grey, false)
        StepState.PROBLEM -> Triple("!", TaarPalette.Red, true)
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberBadge(mark, colour, filled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = if (state == StepState.DONE) TaarPalette.Grey else TaarPalette.Text)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Faint)
        }
        if (actionLabel != null) {
            Text(
                actionLabel, style = MaterialTheme.typography.labelLarge,
                color = if (state == StepState.TODO) TaarPalette.Yellow else TaarPalette.Grey,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAction).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Shown while the phone is capturing: a ring that fills over the three seconds,
 * what is happening, and which capture this is.
 */
@Composable
fun CapturingPanel(title: String, step: Int, total: Int, what: String) {
    val progress = remember(step) { Animatable(0f) }
    LaunchedEffect(step) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = CAPTURE_MILLIS, easing = LinearEasing))
    }
    TaarCard {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.l), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.l)) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { 1f }, modifier = Modifier.size(132.dp), color = TaarPalette.SurfaceHigh, strokeWidth = 10.dp,
                )
                CircularProgressIndicator(
                    progress = { progress.value }, modifier = Modifier.size(132.dp), color = TaarPalette.Yellow,
                    strokeWidth = 10.dp,
                )
                Text("%.0f s".format((1 - progress.value) * CAPTURE_MILLIS / 1000.0 + 0.49),
                    style = MaterialTheme.typography.headlineMedium)
            }
            Text("Keep the phone still", style = MaterialTheme.typography.titleLarge, color = TaarPalette.Yellow)
            Text(if (total > 1) "$title · capture $step of $total" else title,
                style = MaterialTheme.typography.titleSmall, color = TaarPalette.Grey)
            Text(what, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Faint, textAlign = TextAlign.Center)
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

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val h = 8.dp.toPx()
            val top = (size.height - h) / 2
            val r = CornerRadius(h / 2, h / 2)
            val xIdle = size.width * pos(idle)
            val xLive = size.width * pos(live)
            drawRoundRect(TaarPalette.Faint.copy(alpha = 0.5f), Offset(0f, top), Size(xIdle, h), r)
            drawRect(TaarPalette.Amber.copy(alpha = 0.6f), Offset(xIdle, top), Size(xLive - xIdle, h))
            drawRoundRect(TaarPalette.Yellow.copy(alpha = 0.85f), Offset(xLive, top), Size(size.width - xLive, h), r)
            val x = size.width * pos(contrast)
            drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(x, size.height / 2))
            drawCircle(TaarPalette.Background, radius = 5.dp.toPx(), center = Offset(x, size.height / 2))
        }
        Row(Modifier.fillMaxWidth()) {
            Text("No current", style = MaterialTheme.typography.labelSmall, color = TaarPalette.Grey,
                modifier = Modifier.weight(pos(idle)))
            Text("Unclear", style = MaterialTheme.typography.labelSmall, color = TaarPalette.Amber,
                modifier = Modifier.weight(pos(live) - pos(idle) + 0.08f))
            Text("Current flowing", style = MaterialTheme.typography.labelSmall, color = TaarPalette.Yellow,
                modifier = Modifier.weight(1f - pos(live)))
        }
        Hint("Below ${times(idle)} is room noise. Above ${times(live)} means current is flowing.")
    }
}

/**
 * A contrast as "8×" or "13.5×". A plain %.0f turned the 13.5 live threshold into
 * "13×", which is not the number the app uses.
 */
fun times(contrast: Double): String =
    if (kotlin.math.abs(contrast - kotlin.math.round(contrast)) < 0.05) "%.0f×".format(contrast)
    else "%.1f×".format(contrast)

/** A label and a value on one line, value in monospace, an optional line under it. */
@Composable
fun Metric(label: String, value: String, explain: String? = null, mono: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            verticalAlignment = Alignment.Top,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TaarPalette.Grey, maxLines = 1,
                modifier = Modifier.weight(1f))
            Text(value, style = if (mono) MaterialTheme.typography.bodyMedium.merge(TextStyle(fontFamily = FontFamily.Monospace))
                else MaterialTheme.typography.bodyMedium, color = TaarPalette.Text)
        }
        explain?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Faint) }
    }
}

/** A thin rule between rows inside a card. */
@Composable
fun Rule() = HorizontalDivider(color = TaarPalette.Outline, thickness = 1.dp)

@Composable
fun Gap(h: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(h))

@Composable
fun HGap(w: androidx.compose.ui.unit.Dp) = Spacer(Modifier.width(w))

/**
 * Taar's card, used by every screen in place of Material's: the same rounded,
 * hairline-outlined surface as [TaarCard], taking Material's parameters so each
 * screen's own padding and colours carry over unchanged.
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    colors: androidx.compose.material3.CardColors = androidx.compose.material3.CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = colors.containerColor,
        border = BorderStroke(1.dp, TaarPalette.Outline),
    ) { Column(content = content) }
}

/**
 * Taar's buttons, used by every screen in place of Material's so their corners
 * match the cards. Same parameters as Material's for the ones the app uses.
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: androidx.compose.material3.ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick = onClick, modifier = modifier, enabled = enabled,
    shape = MaterialTheme.shapes.medium, colors = colors, content = content,
)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(
    onClick = onClick, modifier = modifier, enabled = enabled,
    shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, TaarPalette.Outline),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = TaarPalette.Text), content = content,
)

/** The header of a collapsible card: title, one line of description, and an arrow. */
@Composable
fun ExpandRow(title: String, subtitle: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey)
        }
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp
            else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (open) "Collapse" else "Expand", tint = TaarPalette.Grey,
        )
    }
}
