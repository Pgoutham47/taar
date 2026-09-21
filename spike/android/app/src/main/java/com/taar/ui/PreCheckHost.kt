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
 */
@Composable
fun PreCheckHost(
    mag: MagCapture,
    audioAvailable: Boolean,
    onContinue: () -> Unit,
) {
    var state by remember {
        mutableStateOf(
            PreCheckState(
                magAvailable = mag.isAvailable,
                magMeasuredRateHz = 0.0,
                magJitter = 0.0,
                magRequestedRateHz = 0,
                audioAvailable = audioAvailable,
                unprocessedGranted = false,
                noiseFloorUt = 0.0,
                hasBaseline = false,
            ),
        )
    }
    var run by remember { mutableIntStateOf(0) }

    LaunchedEffect(run) {
        val samples = withContext(Dispatchers.IO) { mag.capture(durationSeconds = 3.0) }
        if (samples != null) {
            // Noise floor from the centred readings, which is what every threshold
            // is scaled against.
            val centred = DoubleArray(samples.valuesUt.size) {
                samples.valuesUt[it] - Stats.median(samples.valuesUt)
            }
            state = state.copy(
                magMeasuredRateHz = samples.measuredRateHz,
                magJitter = samples.jitter,
                magRequestedRateHz = samples.requestedRateHz,
                noiseFloorUt = Stats.stdDev(centred),
            )
        }
    }

    PreCheckScreen(state = state, onRun = { run++ }, onContinue = onContinue)
}
