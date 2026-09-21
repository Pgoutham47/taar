package com.taar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.taar.data.AndroidFileSystem
import com.taar.domain.Metrics
import com.taar.domain.Store
import com.taar.sensor.AudioCapture
import com.taar.sensor.CaptureCoordinator
import com.taar.sensor.MagCapture

/**
 * Wiring and navigation.
 *
 * Dependencies are constructed by hand. The graph is four objects deep and an
 * injection framework would add a build step, a vocabulary and a failure mode for
 * no benefit at this size.
 */
class MainActivity : ComponentActivity() {

    private enum class Screen { PRE_CHECK, BOARDS, EDIT, MEASURE, RESULT, HISTORY }

    private lateinit var viewModel: TaarViewModel
    private lateinit var mag: MagCapture
    private lateinit var audio: AudioCapture

    private val requestAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Capture works without it; the pre-check screen reports the state. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        mag = MagCapture(sensorManager)
        audio = AudioCapture()
        val store = Store(AndroidFileSystem(this))
        viewModel = TaarViewModel(CaptureCoordinator(mag, audio), store)
        viewModel.bootstrapIfEmpty()
        viewModel.refreshInstallations()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudio.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MaterialTheme(colorScheme = TaarColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.PRE_CHECK) }
                    val state by viewModel.state.collectAsState()

                    BackHandler(enabled = screen != Screen.MEASURE) { screen = Screen.MEASURE }

                    when (screen) {
                        Screen.PRE_CHECK -> PreCheckHost(
                            mag = mag,
                            audioAvailable = ContextCompat.checkSelfPermission(
                                this@MainActivity, Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED,
                            onContinue = { screen = Screen.MEASURE },
                        )

                        Screen.BOARDS -> InstallationListScreen(
                            installations = state.installations,
                            onOpen = { viewModel.openInstallation(it); screen = Screen.EDIT },
                            onCreate = { viewModel.createInstallation(it) },
                        )

                        Screen.EDIT -> state.installation?.let { inst ->
                            CircuitEditorScreen(
                                installation = inst,
                                onSave = { viewModel.saveInstallation(it) },
                                onSelect = { viewModel.selectCircuit(it); screen = Screen.MEASURE },
                                onBack = { screen = Screen.MEASURE },
                            )
                        } ?: run { screen = Screen.BOARDS }

                        Screen.HISTORY -> {
                            LaunchedEffect(Unit) { viewModel.loadHistory() }
                            HistoryScreen(
                                readings = state.history,
                                circuitLabels = state.installation?.circuits
                                    ?.associate { it.id to it.label } ?: emptyMap(),
                                onBack = { screen = Screen.MEASURE },
                            )
                        }

                        Screen.MEASURE -> MeasureScreen(
                            state = state,
                            circuitLabel = viewModel.selectedCircuit?.label,
                            onBaseline = { viewModel.recordBaseline() },
                            onMeasure = { viewModel.measure(); screen = Screen.RESULT },
                            onGo = { screen = it },
                        )

                        Screen.RESULT -> {
                            val reading = state.lastReading
                            val circuit = viewModel.selectedCircuit
                            if (reading == null || circuit == null) {
                                screen = Screen.MEASURE
                            } else {
                                ResultScreen(
                                    reading = reading,
                                    status = state.lastStatus,
                                    faults = state.lastFaults,
                                    thresholds = state.thresholds,
                                    basis = circuit.basis,
                                    impliedCurrentA = Metrics.derive(reading, circuit)?.impliedCurrentA,
                                    spectrogram = state.spectrogram,
                                    prediction = state.prediction,
                                    onLabel = { viewModel.label(it) },
                                    onMeasureAgain = { screen = Screen.MEASURE },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MeasureScreen(
        state: TaarViewModel.UiState,
        circuitLabel: String?,
        onBaseline: () -> Unit,
        onMeasure: () -> Unit,
        onGo: (Screen) -> Unit,
    ) {
        Column(
            Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Taar", style = MaterialTheme.typography.headlineSmall)
            Text(
                state.installation?.name?.let { "$it · ${circuitLabel ?: "no circuit selected"}" }
                    ?: "No board open",
                style = MaterialTheme.typography.bodySmall,
            )

            state.message?.let {
                Card(Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(onClick = onBaseline, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (state.busy && state.baselineProgress > 0)
                        "Capturing reference ${state.baselineProgress}…"
                    else "Record reference",
                )
            }
            Button(onClick = onMeasure, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.busy) "Measuring…" else "Measure")
            }

            OutlinedButton(onClick = { onGo(Screen.BOARDS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Boards")
            }
            OutlinedButton(onClick = { onGo(Screen.EDIT) }, modifier = Modifier.fillMaxWidth()) {
                Text("Edit circuits")
            }
            OutlinedButton(onClick = { onGo(Screen.HISTORY) }, modifier = Modifier.fillMaxWidth()) {
                Text("History")
            }
            OutlinedButton(onClick = { onGo(Screen.PRE_CHECK) }, modifier = Modifier.fillMaxWidth()) {
                Text("Pre-check")
            }
        }
    }
}

private val TaarColors = darkColorScheme(
    primary = Color(0xFFFFD54A),
    onPrimary = Color(0xFF1A1400),
    background = Color(0xFF0D0F13),
    surface = Color(0xFF161A21),
    onBackground = Color(0xFFEEF1F6),
    onSurface = Color(0xFFEEF1F6),
)
