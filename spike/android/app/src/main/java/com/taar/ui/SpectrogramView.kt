package com.taar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.taar.dsp.Spectrogram

/**
 * Draws the envelope spectrogram.
 *
 * A 100 Hz line running the width of the image is what an arc looks like, and the
 * marker is drawn at that row so the technician can see whether the detector is
 * responding to the thing it claims to be responding to. A picture nobody can check
 * against the number is decoration.
 */
@Composable
fun SpectrogramView(result: Spectrogram.Result, modifier: Modifier = Modifier) {
    Column(modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            val cellW = size.width / result.frameCount
            val cellH = size.height / result.binCount

            for (f in 0 until result.frameCount) {
                for (b in 0 until result.binCount) {
                    // Low bins at the bottom, as a spectrogram is normally read.
                    val y = size.height - (b + 1) * cellH
                    drawRect(
                        color = heat(result.cells[f][b]),
                        topLeft = Offset(f * cellW, y),
                        size = Size(cellW + 0.5f, cellH + 0.5f),
                    )
                }
            }

            val markerBin = (100.0 / (result.maxFrequencyHz / result.binCount)).toInt()
            val markerY = size.height - (markerBin + 0.5f).toFloat() * cellH
            drawLine(
                color = Color(0x66FFFFFF),
                start = Offset(0f, markerY),
                end = Offset(size.width, markerY),
                strokeWidth = 1f,
            )
        }
        Text(
            "0–%.0f Hz envelope · %.1f s · line marks 100 Hz"
                .format(result.maxFrequencyHz, result.durationSeconds),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Dark blue through amber to white. Monotonic in lightness, so it reads in print. */
private fun heat(v: Double): Color {
    val t = v.coerceIn(0.0, 1.0).toFloat()
    return when {
        t < 0.5f -> lerp(Color(0xFF0D0F13), Color(0xFF3B4A7A), t / 0.5f)
        t < 0.8f -> lerp(Color(0xFF3B4A7A), Color(0xFFFFD54A), (t - 0.5f) / 0.3f)
        else -> lerp(Color(0xFFFFD54A), Color(0xFFFFFFFF), (t - 0.8f) / 0.2f)
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
)
