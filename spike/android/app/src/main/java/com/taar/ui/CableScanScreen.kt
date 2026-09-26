package com.taar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taar.domain.CableScan
import com.taar.domain.Circuit
import com.taar.domain.Fusion
import com.taar.domain.ScanStore

/**
 * Cable Scan: slide the phone along a cable, one point every few seconds, and see
 * where the anomaly signal was strongest. The logic is in [CableScan]; this is
 * only the screen.
 */
@Composable
fun CableScanScreen(
    state: TaarViewModel.UiState,
    circuit: Circuit?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: (ScanStore.Session) -> Unit,
    onBack: () -> Unit,
) {
    val scan = state.scan
    var showWhy by rememberSaveable { mutableStateOf(false) }
    val result = scan?.result
    val why = result?.takeIf { showWhy && scan.running.not() }

    TaarScreen(
        title = if (why != null) "Why this zone" else "Cable Scan",
        subtitle = circuit?.let { "${state.installation?.name} › ${it.label}" },
        onBack = when {
            scan?.running == true -> null
            why != null -> { { showWhy = false } }
            else -> onBack
        },
    ) {
        if (circuit == null || circuit.baseline?.isSufficient != true) {
            ErrorCard("Choose a circuit and record its reference first. Go back to Home.")
            return@TaarScreen
        }
        when {
            scan == null -> Unit
            why != null -> ScanWhy(why, onBack = { showWhy = false })
            scan.running -> Running(scan, onStop)
            result != null -> ScanResult(scan, result, onWhy = { showWhy = true },
                onScanAgain = { showWhy = false; onStart() }, onDone = onBack)
            else -> Intro(state.scanHistory, onStart = { showWhy = false; onStart() }, onOpen = onOpen)
        }
    }
}

@Composable
private fun Intro(history: List<ScanStore.Session>, onStart: () -> Unit, onOpen: (ScanStore.Session) -> Unit) {
    Text("Find where along this cable the anomaly signal is strongest.",
        style = MaterialTheme.typography.bodyLarge)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Instruction(1, "Hold the phone flat against the cable, a little before the area you want to check.")
            Instruction(2, "When it says Listening, hold still for 3 seconds.")
            Instruction(3, "When it says Move, slide the phone one hand-width along the cable.")
            Instruction(4, "Keep the same side of the phone on the cable, at the same distance, all the way.")
            Instruction(5, "Go a little past the area, then tap Stop. At least 6 points works best.")
        }
    }
    Hint("Location comes from the sound pattern, which is clearest near its source. Positions are scan " +
        "steps, not measured distances.")
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Start Cable Scan", style = MaterialTheme.typography.titleMedium)
    }

    if (history.isNotEmpty()) {
        HorizontalDivider()
        Text("Earlier scans of this cable", style = MaterialTheme.typography.titleSmall)
        for (s in history.take(5)) {
            val r = CableScan.analyse(s.points)
            Text(
                "${time(s.epochMillis)} · ${s.points.size} points · " + when (r.verdict) {
                    CableScan.Verdict.ZONE_FOUND -> "strongest around point ${r.peak}"
                    CableScan.Verdict.UNIFORM -> "no single area stood out"
                    CableScan.Verdict.ISOLATED_SPIKE -> "one noisy point only"
                    CableScan.Verdict.NO_ANOMALY -> "no anomaly zone"
                    CableScan.Verdict.TOO_FEW_POINTS -> "too few points"
                } + "  ›",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clickable { onOpen(s) }.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Running(scan: TaarViewModel.Scan, onStop: () -> Unit) {
    val last = scan.points.lastOrNull()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (scan.listening) "Point ${scan.points.size + 1} · Listening — hold still"
                else "Move — slide one hand-width along the cable",
                style = MaterialTheme.typography.headlineSmall,
                color = if (scan.listening) TaarPalette.Yellow else TaarPalette.Blue,
                fontWeight = FontWeight.SemiBold,
            )
            Hint("${scan.points.size} point${if (scan.points.size == 1) "" else "s"} captured")
        }
    }

    if (last != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Last point (${last.index + 1})", style = MaterialTheme.typography.titleSmall)
                if (last.accepted) {
                    StrengthBar("Strength", last.strength, colourOf(liveState(last)))
                } else {
                    Text("Discarded: ${last.rejectReason}", color = TaarPalette.Grey)
                }
                Text("Measurement quality: ${last.quality.name}", style = MaterialTheme.typography.bodyMedium,
                    color = when (last.quality) {
                        Fusion.Quality.GOOD -> TaarPalette.Green
                        Fusion.Quality.FAIR -> TaarPalette.Amber
                        Fusion.Quality.POOR -> TaarPalette.Red
                    })
            }
        }
        CableLine(scan.points.map { liveState(it) }, peak = null)
    }

    Button(
        onClick = onStop,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TaarPalette.Red),
    ) { Text("Stop and show result", style = MaterialTheme.typography.titleMedium) }
    Hint("The scan also stops by itself after 30 points.")
}

@Composable
private fun ScanResult(
    scan: TaarViewModel.Scan,
    r: CableScan.Result,
    onWhy: () -> Unit,
    onScanAgain: () -> Unit,
    onDone: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("CABLE SCAN COMPLETE", style = MaterialTheme.typography.labelLarge, color = TaarPalette.Grey)
            Row2("Cable", scan.circuitLabel)
            Row2("Scan points", "${r.points.size}")
            Row2("Measurement quality", "${r.good + r.fair} used / ${r.discarded} discarded" +
                if (r.fair > 0) " (${r.fair} fair)" else "")
        }
    }

    Text("Anomaly strength", style = MaterialTheme.typography.titleSmall)
    for (s in r.points) {
        val n = s.point.index + 1
        if (s.smoothed == null) {
            Text("Point $n  ·  discarded (${s.point.rejectReason ?: "too few points"})",
                style = MaterialTheme.typography.bodySmall, color = TaarPalette.Grey)
        } else {
            StrengthBar("Point $n", s.smoothed, colourOf(s.state))
        }
    }

    CableLine(r.points.map { it.state }, peak = r.peak?.let { it - 1 })
    Legend()

    val (title, colour) = when (r.verdict) {
        CableScan.Verdict.ZONE_FOUND -> "STRONGEST ANOMALY ZONE" to TaarPalette.Red
        CableScan.Verdict.UNIFORM -> "NO SINGLE ZONE STANDS OUT" to TaarPalette.Amber
        CableScan.Verdict.ISOLATED_SPIKE -> "ONE NOISY POINT ONLY" to TaarPalette.Amber
        CableScan.Verdict.NO_ANOMALY -> "NO ANOMALY ZONE OBSERVED" to TaarPalette.Green
        CableScan.Verdict.TOO_FEW_POINTS -> "NOT ENOUGH POINTS" to TaarPalette.Grey
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (r.verdict == CableScan.Verdict.ZONE_FOUND) TaarPalette.RedSurface
            else CardDefaults.cardColors().containerColor,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colour, fontWeight = FontWeight.Bold)
            r.peak?.let {
                Text("Around point $it", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(r.message, style = MaterialTheme.typography.bodyMedium)
            if (r.currentAbsent) {
                Hint("Most points showed no current in the cable. An arc needs current, so the sound may " +
                    "come from something nearby.", color = TaarPalette.Amber)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onWhy, modifier = Modifier.weight(1f)) { Text("Why?") }
        OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
    }
    Button(onClick = onScanAgain, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Scan again", style = MaterialTheme.typography.titleMedium)
    }
    Hint(CableScan.CAVEAT)
}

@Composable
private fun ScanWhy(r: CableScan.Result, onBack: () -> Unit) {
    Text("How this result was reached", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    for (w in r.why) Text("• $w", style = MaterialTheme.typography.bodyMedium)

    HorizontalDivider()
    Text("Every point", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Hint("Measured strength, then after comparison with neighbours. Strength combines the arc rule's " +
        "score against this wire's normal with the on-device AI's probability.")
    for (s in r.points) {
        val p = s.point
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("Point ${p.index + 1} · ${s.state.name.lowercase().replace('_', ' ')}",
                style = MaterialTheme.typography.titleSmall, color = colourOf(s.state))
            Text(
                buildString {
                    append("measured %.2f".format(p.strength))
                    s.smoothed?.let { append(" → %.2f".format(it)) }
                    append(" · rule %+.1f".format(p.arcZ))
                    p.aiProbability?.let { append(" · AI ${(it * 100).toInt()}%") }
                    append(" · current %.0f×".format(com.taar.domain.LineState.contrastOf(p.lineConfidence)))
                    append(" · ${p.quality.name.lowercase()}")
                    p.rejectReason?.let { append(" · discarded: $it") }
                },
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = TaarPalette.Grey,
            )
        }
    }
    Hint(CableScan.CAVEAT)
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Back to result", style = MaterialTheme.typography.titleMedium)
    }
}

// ---- pieces ----

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StrengthBar(label: String, value: Double, colour: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
        Box(Modifier.weight(1f).height(14.dp).background(TaarPalette.Grey.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0.02, 1.0).toFloat()).height(14.dp).background(colour))
        }
        Text("%.2f".format(value), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/** The cable as a line, one dot per scan point, and a marker under the peak. */
@Composable
private fun CableLine(states: List<CableScan.State>, peak: Int?) {
    if (states.isEmpty()) return
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val pad = 14.dp.toPx()
        val y = 20.dp.toPx()
        val n = states.size
        fun x(i: Int) = if (n == 1) size.width / 2 else pad + i * (size.width - 2 * pad) / (n - 1)
        drawLine(TaarPalette.Grey, Offset(pad, y), Offset(size.width - pad, y), strokeWidth = 3.dp.toPx())
        states.forEachIndexed { i, s ->
            val r = (if (s == CableScan.State.STRONGEST) 8 else 5).dp.toPx()
            drawCircle(colourOf(s), r, Offset(x(i), y))
        }
        peak?.let { i ->
            val tip = y + 12.dp.toPx()
            val base = tip + 12.dp.toPx()
            val half = 7.dp.toPx()
            drawPath(Path().apply {
                moveTo(x(i), tip); lineTo(x(i) - half, base); lineTo(x(i) + half, base); close()
            }, TaarPalette.Red)
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((label, s) in listOf("Normal" to CableScan.State.NORMAL, "Elevated" to CableScan.State.ELEVATED,
            "Strongest" to CableScan.State.STRONGEST, "Discarded" to CableScan.State.DISCARDED)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(10.dp).height(10.dp).background(colourOf(s)))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** During a scan, before smoothing: a point's own measured level. */
private fun liveState(p: CableScan.Point) = when {
    !p.accepted -> CableScan.State.DISCARDED
    p.strength >= CableScan.ELEVATED_AT -> CableScan.State.ELEVATED
    else -> CableScan.State.NORMAL
}

private fun colourOf(s: CableScan.State): Color = when (s) {
    CableScan.State.NORMAL -> TaarPalette.Green
    CableScan.State.ELEVATED -> TaarPalette.Amber
    CableScan.State.STRONGEST -> TaarPalette.Red
    CableScan.State.DISCARDED -> TaarPalette.Grey
}
