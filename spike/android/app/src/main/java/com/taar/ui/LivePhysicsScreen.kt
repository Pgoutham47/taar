package com.taar.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taar.domain.Circuit
import com.taar.domain.Fusion
import com.taar.domain.LineState
import com.taar.dsp.ArcFeatures
import com.taar.ml.ArcModel
import com.taar.sensor.CaptureCoordinator
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * "See What Taar Sees": the path from physical signal to result, drawn from the
 * pipeline's own values on every capture.
 *
 * Nothing on this screen is simulated. Each frame is one ordinary 3 s capture, and
 * every plot and number is either a value the detectors computed or a thinned copy
 * of the data they computed it from. The status is the fusion layer's result, not
 * a second diagnosis.
 */
@Composable
fun LivePhysicsScreen(
    state: TaarViewModel.UiState,
    circuit: Circuit?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val live = state.live
    val frame = live.frame
    var folded by rememberSaveable { mutableStateOf(true) }
    var showReference by rememberSaveable { mutableStateOf(true) }
    var showAi by rememberSaveable { mutableStateOf(false) }

    // Leaving the screen stops capturing; the view is never left running unseen.
    DisposableEffect(Unit) { onDispose { onPause() } }

    TaarScreen(
        title = "See What Taar Sees",
        subtitle = circuit?.let { "${state.installation?.name} › ${it.label}" }
            ?: "Live physics view",
        onBack = { onPause(); onBack() },
    ) {
        StatusBanner(frame, live)
        Controls(live, onStart, onPause, onStop, onReset)

        if (frame == null) {
            Hint(
                "Tap Start. Taar captures 3 seconds at a time, exactly as it does when measuring, and " +
                    "shows each step from the raw sensor signals to the result. Nothing is recorded or saved.",
            )
            if (circuit?.baseline?.isSufficient != true) {
                Hint("No reference for this circuit yet: signals will show, but there is no result to compare " +
                    "against. Record a reference from Home for the full chain.", color = TaarPalette.Amber)
            }
            return@TaarScreen
        }
        val view = frame.reading.view

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = folded, onClick = { folded = !folded },
                label = { Text(if (folded) "Magnetic: processed" else "Magnetic: raw") })
            FilterChip(selected = showReference, onClick = { showReference = !showReference },
                label = { Text("Reference") })
            FilterChip(selected = showAi, onClick = { showAi = !showAi }, label = { Text("AI details") })
        }

        MagneticPanel(frame, view, circuit, folded, showReference)
        AudioPanel(frame, view, circuit, showReference, showAi, state.aiSelfCheck)
        PipelinePanel(frame, view, live.listening)
        WhyPanel(frame)
        Hint(
            "An explanation of what the phone's sensors observe, not a certified electrical test. " +
                "Where the result suggests an anomaly, further inspection is recommended.",
        )
    }
}

// ---- status and controls ----

private data class StatusLook(val dot: String, val label: String, val colour: Color)

private fun statusOf(frame: TaarViewModel.LiveFrame?): StatusLook = when (frame?.fusion?.outcome?.tone) {
    null -> if (frame == null) StatusLook("⚪", "READY", TaarPalette.Grey)
    else StatusLook("⚪", "NO REFERENCE", TaarPalette.Grey)
    Fusion.Tone.NORMAL -> StatusLook("🟢", "NORMAL", TaarPalette.Green)
    Fusion.Tone.ADVISORY, Fusion.Tone.WARNING -> StatusLook("🟡", "POSSIBLE ANOMALY", TaarPalette.Yellow)
    Fusion.Tone.CRITICAL -> StatusLook("🔴", "ANOMALY DETECTED", TaarPalette.Red)
    Fusion.Tone.UNRELIABLE -> StatusLook("⚪", "UNRELIABLE", TaarPalette.Grey)
}

@Composable
private fun StatusBanner(frame: TaarViewModel.LiveFrame?, live: TaarViewModel.Live) {
    val look = statusOf(frame)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${look.dot} ${look.label}", style = MaterialTheme.typography.headlineMedium,
                color = look.colour, fontWeight = FontWeight.Bold)
            frame?.fusion?.let {
                Text(it.outcome.title, style = MaterialTheme.typography.titleMedium)
            }
            if (frame != null && frame.fusion == null) {
                Hint("Record a reference for this circuit to get a result. The signals below are live.")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(
                    when {
                        live.listening -> TaarPalette.Red
                        live.running -> TaarPalette.Yellow
                        else -> TaarPalette.Grey
                    },
                ))
                Text(
                    when {
                        live.listening -> "Capturing 3 s…"
                        live.running -> "Processing"
                        frame != null -> "Paused"
                        else -> "Stopped"
                    } + (frame?.let { " · capture #${it.number} · updates every 3 s" } ?: ""),
                    style = MaterialTheme.typography.labelMedium, color = TaarPalette.Grey,
                )
            }
        }
    }
}

@Composable
private fun Controls(
    live: TaarViewModel.Live,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (live.running) {
            Button(onClick = onPause, modifier = Modifier.weight(1f).height(48.dp)) { Text("Pause") }
        } else {
            Button(onClick = onStart, modifier = Modifier.weight(1f).height(48.dp)) {
                Text(if (live.frame != null) "Resume" else "Start")
            }
        }
        OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).height(48.dp)) { Text("Stop") }
        OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f).height(48.dp)) { Text("Reset") }
    }
}

// ---- magnetometer ----

@Composable
private fun MagneticPanel(
    frame: TaarViewModel.LiveFrame,
    view: CaptureCoordinator.LiveView?,
    circuit: Circuit?,
    folded: Boolean,
    showReference: Boolean,
) {
    val r = frame.reading
    val line = LineState.of(r.lineConfidence)
    val contrast = LineState.contrastOf(r.lineConfidence)
    val reference = circuit?.baseline?.medianFieldUt
    Panel("MAGNETOMETER", "Physical signal: the magnetic field of current in the cable") {
        if (view == null || view.magT.isEmpty()) {
            Hint("No magnetometer data in this capture.")
        } else if (folded) {
            Text("Processed · ${view.magAxis}-axis folded onto one ${view.lineHz.toInt()} Hz cycle",
                style = MaterialTheme.typography.labelMedium, color = TaarPalette.Grey)
            FoldedPlot(view, if (showReference) reference else null)
            Hint(
                "Each dot is a real sample, placed by where it fell in the 20 ms mains cycle. The sensor reads " +
                    "%.0f times a second, too slowly to trace 50 Hz directly, but its irregular timing visits "
                        .format(view.magRateHz) +
                    "every part of the cycle. The line is the detector's own 50 Hz fit.",
            )
        } else {
            Text("Raw · ${view.magAxis}-axis · %.0f Hz · %.1f s · drift removed"
                .format(view.magRateHz, view.magT.lastOrNull() ?: 0.0),
                style = MaterialTheme.typography.labelMedium, color = TaarPalette.Grey)
            TracePlot(view.magT, view.magValuesUt)
            Hint("The samples as they arrived. 50 Hz is faster than half the sensor's rate, so it does not " +
                "appear as a 50 Hz wave here; switch to processed to see it recovered.")
        }
        Readout("50 Hz amplitude", "%.2f µT".format(r.fieldAmplitudeUt) +
            (if (showReference && reference != null) "   reference %.2f µT".format(reference) else ""))
        Readout("Stands out from noise", "%.0f×  (none < 8×, flowing > 13.5×)".format(contrast))
        Readout(
            "Detected pattern",
            when (line) {
                LineState.FLOWING -> "50 Hz variation present"
                LineState.UNCLEAR -> "Weak 50 Hz variation — unclear"
                LineState.NONE -> "No 50 Hz variation above noise"
            },
            colour = when (line) {
                LineState.FLOWING -> TaarPalette.Yellow
                LineState.UNCLEAR -> TaarPalette.Amber
                LineState.NONE -> TaarPalette.Grey
            },
        )
    }
}

@Composable
private fun FoldedPlot(v: CaptureCoordinator.LiveView, referenceUt: Double?) {
    val mean = v.magValuesUt.average()
    val ys = DoubleArray(v.magValuesUt.size) { v.magValuesUt[it] - mean }
    val span = maxOf(ys.maxOfOrNull { abs(it) } ?: 0.0, v.magFitAmplitudeUt, referenceUt ?: 0.0, 0.05) * 1.15
    Canvas(Modifier.fillMaxWidth().height(140.dp).background(PlotBg)) {
        val w = size.width
        val h = size.height
        fun py(y: Double) = (h / 2 - (y / span) * (h / 2)).toFloat()
        drawLine(Grid, Offset(0f, h / 2), Offset(w, h / 2), 1f)
        referenceUt?.let { ref ->
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            drawLine(TaarPalette.Blue, Offset(0f, py(ref)), Offset(w, py(ref)), 2f, pathEffect = dash)
            drawLine(TaarPalette.Blue, Offset(0f, py(-ref)), Offset(w, py(-ref)), 2f, pathEffect = dash)
        }
        for (i in ys.indices) {
            val phase = v.lineHz * v.magT[i]
            val x = ((phase - floor(phase)) * w).toFloat()
            drawCircle(TaarPalette.Grey, 3f, Offset(x, py(ys[i])))
        }
        val path = Path()
        for (k in 0..100) {
            val x = k / 100.0
            val y = v.magFitAmplitudeUt * sin(2 * PI * x + v.magFitPhaseRad)
            if (k == 0) path.moveTo(0f, py(y)) else path.lineTo((x * w).toFloat(), py(y))
        }
        drawPath(path, TaarPalette.Yellow, style = Stroke(width = 4f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("0 ms", style = MaterialTheme.typography.labelSmall, color = TaarPalette.Grey)
        Text("── fit  · · samples" + (if (referenceUt != null) "  - - reference" else ""),
            style = MaterialTheme.typography.labelSmall, color = TaarPalette.Grey)
        Text("20 ms", style = MaterialTheme.typography.labelSmall, color = TaarPalette.Grey)
    }
}

@Composable
private fun TracePlot(t: DoubleArray, y: DoubleArray) {
    val span = maxOf(y.maxOfOrNull { abs(it) } ?: 0.0, 0.05) * 1.15
    val t1 = t.lastOrNull()?.takeIf { it > 0 } ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(110.dp).background(PlotBg)) {
        val h = size.height
        drawLine(Grid, Offset(0f, h / 2), Offset(size.width, h / 2), 1f)
        val path = Path()
        for (i in y.indices) {
            val x = (t[i] / t1 * size.width).toFloat()
            val py = (h / 2 - (y[i] / span) * (h / 2)).toFloat()
            if (i == 0) path.moveTo(x, py) else path.lineTo(x, py)
        }
        drawPath(path, TaarPalette.Yellow, style = Stroke(width = 2f))
    }
}

// ---- audio ----

@Composable
private fun AudioPanel(
    frame: TaarViewModel.LiveFrame,
    view: CaptureCoordinator.LiveView?,
    circuit: Circuit?,
    showReference: Boolean,
    showAi: Boolean,
    selfCheck: ArcModel.SelfCheck?,
) {
    val m = frame.metrics
    val t = frame.thresholds
    val p = frame.reading.aiArcProbability
    val mi = frame.reading.arcModulationIndex
    val reference = circuit?.baseline?.medianArcIndex
    Panel("AUDIO", "Physical signal: sound; sparking pulses 100 times a second") {
        if (view == null || view.audioMin.isEmpty()) {
            Hint("No sound captured. Check microphone permission.")
        } else {
            Text("Raw microphone · 44.1 kHz · %.1f s".format(view.audioSeconds),
                style = MaterialTheme.typography.labelMedium, color = TaarPalette.Grey)
            WavePlot(view.audioMin, view.audioMax)

            Text("Detector's view · 4–16 kHz envelope · 60 ms · grid every 10 ms",
                style = MaterialTheme.typography.labelMedium, color = TaarPalette.Grey)
            EnvelopePlot(view.envelopeSnippet, view.envelopeRateHz)
            Hint("Arcing shows as bursts landing on the grid lines: one every 10 ms, 100 per second.")

            Text("Envelope spectrum 20–400 Hz · the input the AI reads",
                style = MaterialTheme.typography.labelMedium, color = TaarPalette.Grey)
            SpectrumPlot(view.envelopeSpectrum)
        }

        Readout("100 Hz share", "%.3f".format(mi) +
            (if (showReference && reference != null) "   reference %.3f".format(reference) else ""))
        val ruleOn = m != null && m.arcZ >= t.warningZ
        Readout(
            "Rule",
            when {
                m == null -> "needs a reference"
                ruleOn -> "DETECTED  (%+.1f, warns at %.1f)".format(m.arcZ, t.warningZ)
                else -> "not detected  (%+.1f, warns at %.1f)".format(m.arcZ, t.warningZ)
            },
            colour = if (ruleOn) TaarPalette.Amber else Color.Unspecified,
        )
        Readout(
            "AI",
            when {
                p == null -> "not available"
                p >= AI_CONFIRMED -> "CONFIRMED  (arc-like ${(p * 100).toInt()}%)"
                p <= AI_REJECTED -> "NOT CONFIRMED  (arc-like ${(p * 100).toInt()}%)"
                else -> "UNCERTAIN  (arc-like ${(p * 100).toInt()}%)"
            },
            colour = when {
                p == null -> TaarPalette.Grey
                p >= AI_CONFIRMED -> TaarPalette.Amber
                p <= AI_REJECTED -> TaarPalette.Green
                else -> TaarPalette.Yellow
            },
        )
        Readout(
            "Pattern",
            when {
                ruleOn && (p ?: 0f) >= ArcModel.THRESHOLD -> "Periodic impulsive activity at 100 Hz"
                ruleOn || (p ?: 0f) >= ArcModel.THRESHOLD -> "Possible periodic impulsive activity"
                else -> "No periodic impulsive pattern"
            },
        )
        if (showAi) {
            Hint(
                "On-device TFLite network: 89 features (76 from the spectrum above, 12 pitch bands, the 100 Hz " +
                    "share) → 32 → 16 → 1. 16.6 KB, runs on this phone. Fusion counts arc-like at " +
                    "${(ArcModel.THRESHOLD * 100).toInt()}% or more; this screen calls it confirmed at " +
                    "${(AI_CONFIRMED * 100).toInt()}%+ and uncertain between ${(AI_REJECTED * 100).toInt()}% and " +
                    "${(AI_CONFIRMED * 100).toInt()}%. " +
                    (selfCheck?.let { if (it.passed) "Start-up self-check passed (${it.rows}/${it.rows})." else "Self-check FAILED." }
                        ?: "Model not loaded."),
            )
        }
    }
}

@Composable
private fun WavePlot(lo: FloatArray, hi: FloatArray) {
    val span = maxOf(hi.maxOrNull() ?: 0f, -(lo.minOrNull() ?: 0f), 1e-4f) * 1.1f
    Canvas(Modifier.fillMaxWidth().height(80.dp).background(PlotBg)) {
        val h = size.height
        val dx = size.width / lo.size
        for (i in lo.indices) {
            val x = i * dx
            drawLine(TaarPalette.Blue, Offset(x, h / 2 - hi[i] / span * h / 2), Offset(x, h / 2 - lo[i] / span * h / 2), maxOf(1f, dx * 0.8f))
        }
    }
}

@Composable
private fun EnvelopePlot(env: DoubleArray, rateHz: Double) {
    if (env.isEmpty() || rateHz <= 0) return
    val mean = env.average()
    val span = maxOf(env.maxOf { abs(it - mean) }, 1e-9) * 1.15
    Canvas(Modifier.fillMaxWidth().height(90.dp).background(PlotBg)) {
        val w = size.width
        val h = size.height
        val perGrid = 0.010 * rateHz
        var g = 0.0
        while (g < env.size) {
            val x = (g / env.size * w).toFloat()
            drawLine(Grid, Offset(x, 0f), Offset(x, h), 1f)
            g += perGrid
        }
        val path = Path()
        for (i in env.indices) {
            val x = (i.toFloat() / env.size) * w
            val y = (h / 2 - ((env[i] - mean) / span) * (h / 2)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, TaarPalette.Amber, style = Stroke(width = 3f))
    }
}

@Composable
private fun SpectrumPlot(bins: DoubleArray) {
    if (bins.isEmpty()) return
    val top = maxOf(bins.maxOrNull() ?: 0.0, 1e-9)
    // The bins either side of 100 Hz are the ones the rule's 100 Hz share draws on.
    val hundred = ((100.0 - ArcFeatures.ENV_LO_HZ) / ArcFeatures.ENV_BIN_HZ).toInt()
    Canvas(Modifier.fillMaxWidth().height(80.dp).background(PlotBg)) {
        val dx = size.width / bins.size
        for (i in bins.indices) {
            val bh = (bins[i] / top * size.height).toFloat()
            drawRect(
                if (i == hundred || i == hundred - 1) TaarPalette.Red else TaarPalette.Grey,
                Offset(i * dx, size.height - bh),
                androidx.compose.ui.geometry.Size(maxOf(1f, dx * 0.8f), bh),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("20 Hz", style = MaterialTheme.typography.labelSmall, color = TaarPalette.Grey)
        Text("red = 100 Hz", style = MaterialTheme.typography.labelSmall, color = TaarPalette.Red)
        Text("400 Hz", style = MaterialTheme.typography.labelSmall, color = TaarPalette.Grey)
    }
}

// ---- pipeline ----

@Composable
private fun PipelinePanel(frame: TaarViewModel.LiveFrame, view: CaptureCoordinator.LiveView?, listening: Boolean) {
    val r = frame.reading
    val m = frame.metrics
    val steps = listOf(
        "RAW SENSOR" to "Magnetometer %d samples @ %.0f Hz · microphone 44.1 kHz%s".format(
            view?.magT?.size ?: 0, view?.magRateHz ?: 0.0, if (r.unprocessedAudioGranted) " (raw)" else " (processed)"),
        "FILTER" to "Magnetic drift removed per axis · audio 4–16 kHz band-pass → envelope",
        "FEATURES" to "50 Hz %.0f× · %.2f µT · 100 Hz share %.3f · 89 audio features".format(
            LineState.contrastOf(r.lineConfidence), r.fieldAmplitudeUt, r.arcModulationIndex),
        "RULE / MODEL" to buildString {
            append("Current ${LineState.of(r.lineConfidence).name.lowercase()}")
            m?.let { append(" · arc rule %+.1f".format(it.arcZ)) }
            r.aiArcProbability?.let { append(" · AI ${(it * 100).toInt()}%") }
        },
        "SENSOR FUSION" to (frame.fusion?.let { "${it.outcome.title} · evidence ${it.strength.name.lowercase()}" }
            ?: "Needs a reference"),
        "RESULT" to statusOf(frame).let { "${it.dot} ${it.label}" },
    )
    // Lights the steps in order each time a new capture arrives, so the chain reads
    // as a chain; while capturing, only the first is active.
    var lit by remember { mutableIntStateOf(-1) }
    LaunchedEffect(frame.number) {
        for (i in steps.indices) { lit = i; delay(160) }
    }
    Panel("PROCESSING PIPELINE", "From sensor to result, this capture") {
        steps.forEachIndexed { i, (name, value) ->
            val active = if (listening) i == 0 else i <= lit
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape)
                    .background(if (active) TaarPalette.Yellow else TaarPalette.Grey.copy(alpha = 0.35f)))
                Column {
                    Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        color = if (active) Color.Unspecified else TaarPalette.Grey)
                    Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = TaarPalette.Grey)
                }
            }
            if (i < steps.lastIndex) Text("   ↓", color = TaarPalette.Grey, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---- why ----

@Composable
private fun WhyPanel(frame: TaarViewModel.LiveFrame) {
    val f = frame.fusion ?: return
    Panel("WHY TAAR SHOWS THIS", "The evidence behind the status, from the fusion layer") {
        for (s in f.signals) {
            val (mark, colour) = when (s.verdict) {
                Fusion.Verdict.SUPPORTS -> "▲" to TaarPalette.Amber
                Fusion.Verdict.AGAINST -> "●" to TaarPalette.Green
                Fusion.Verdict.NEUTRAL -> "·" to TaarPalette.Grey
                Fusion.Verdict.UNAVAILABLE -> "–" to TaarPalette.Grey
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(mark, color = colour, style = MaterialTheme.typography.bodyMedium)
                Text("${s.name}: ${s.value}", style = MaterialTheme.typography.bodyMedium, color = colour)
            }
        }
        Hint("▲ points to an anomaly · ● points to normal · · context · – not available")
        for (w in f.why.take(3)) Text("✓ $w", style = MaterialTheme.typography.bodySmall)
    }
}

// ---- pieces ----

@Composable
private fun Panel(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = TaarPalette.Yellow)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TaarPalette.Grey)
            content()
        }
    }
}

@Composable
private fun Readout(label: String, value: String, colour: Color = Color.Unspecified) {
    // Label a fixed column, value wraps on the right, so a long value never runs into it.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey,
            modifier = Modifier.width(112.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colour,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
    }
}

private val PlotBg = Color(0xFF12161D)
private val Grid = Color(0xFF2A313C)

/** Display bands for the model's probability; fusion itself uses [ArcModel.THRESHOLD]. */
private const val AI_CONFIRMED = 0.7f
private const val AI_REJECTED = 0.3f
