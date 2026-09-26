package com.taar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.taar.domain.Stats
import com.taar.sensor.MagCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the pre-check measurements and hands them to [PreCheckScreen].
 *
 * The screen itself is a pure function of its state so it can be previewed and
 * reasoned about; this is the part that touches hardware.
 *
 * Nothing runs until the person taps Start. The check measures the noise floor, and
 * starting it automatically meant measuring a phone that was still in someone's hand.
 */
@Composable
fun PreCheckHost(
    mag: MagCapture,
    audioAvailable: Boolean,
    onResult: (passed: Boolean, rateHz: Double) -> Unit,
    onDone: () -> Unit,
) {
    var state by remember {
        mutableStateOf(
            PreCheckState(
                magAvailable = mag.isAvailable,
                magMeasuredRateHz = 0.0,
                magJitter = 0.0,
                magRequestedRateHz = 0,
                audioAvailable = audioAvailable,
                noiseFloorUt = 0.0,
            ),
        )
    }
    var run by remember { mutableIntStateOf(0) }

    LaunchedEffect(run) {
        if (run == 0) return@LaunchedEffect
        state = state.copy(running = true, failed = false, finished = false)
        val samples = withContext(Dispatchers.IO) { mag.capture(durationSeconds = 3.0) }
        state = if (samples == null) {
            // Distinguish "capture failed" from "measured zero" — they look
            // identical on screen otherwise, and mean very different things.
            state.copy(running = false, failed = true, finished = true)
        } else {
            // Noise floor from the centred readings, which is what every threshold
            // is scaled against.
            val centred = DoubleArray(samples.valuesUt.size) {
                samples.valuesUt[it] - Stats.median(samples.valuesUt)
            }
            state.copy(
                magMeasuredRateHz = samples.measuredRateHz,
                magJitter = samples.jitter,
                magRequestedRateHz = samples.requestedRateHz,
                noiseFloorUt = Stats.stdDev(centred),
                running = false,
                failed = false,
                finished = true,
            )
        }
        onResult(state.passed, state.magMeasuredRateHz)
    }

    PreCheckScreen(
        state = state.copy(audioAvailable = audioAvailable),
        onRun = { run++ },
        onDone = onDone,
    )
}
