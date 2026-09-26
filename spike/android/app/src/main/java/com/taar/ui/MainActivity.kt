package com.taar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.taar.data.AndroidFileSystem
import com.taar.domain.Store
import com.taar.sensor.AudioCapture
import com.taar.sensor.CaptureCoordinator
import com.taar.sensor.MagCapture

/**
 * Wiring and navigation.
 *
 * Every screen is reached from Home, and Back always returns there. The flow is
 * linear on purpose -- phone check, circuit, reference, measure -- and Home shows
 * which of those are done, so there is never a question of what to press next.
 *
 * Dependencies are constructed by hand. The graph is four objects deep and an
 * injection framework would add a build step, a vocabulary and a failure mode for
 * no benefit at this size.
 */
class MainActivity : ComponentActivity() {

    enum class Screen { HOME, PHONE_CHECK, CIRCUITS, REFERENCE, MEASURE, CALIBRATE, HISTORY }

    private lateinit var viewModel: TaarViewModel
    private lateinit var mag: MagCapture
    private lateinit var audio: AudioCapture

    private var audioGranted by mutableStateOf(false)

    private val requestAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> audioGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        mag = MagCapture(sensorManager)
        audio = AudioCapture()
        val store = Store(AndroidFileSystem(this))
        viewModel = TaarViewModel(CaptureCoordinator(mag, audio), store)
        viewModel.bootstrap()

        audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!audioGranted) requestAudio.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            MaterialTheme(colorScheme = TaarColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.HOME) }
                    val state by viewModel.state.collectAsState()
                    val home = { screen = Screen.HOME }

                    // Back is swallowed while a capture runs: leaving mid-capture left
                    // the next screen waiting on a capture it had not started.
                    BackHandler(enabled = screen != Screen.HOME) { if (!state.busy) home() }

                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            state = state,
                            circuit = viewModel.selectedCircuit,
                            onGo = { screen = it },
                        )

                        Screen.PHONE_CHECK -> PreCheckHost(
                            mag = mag,
                            audioAvailable = audioGranted,
                            onResult = { passed, rate -> viewModel.recordPhoneCheck(passed, rate) },
                            onDone = home,
                        )

                        Screen.CIRCUITS -> {
                            LaunchedEffect(Unit) { viewModel.refreshInstallations() }
                            CircuitsScreen(
                                installations = state.installations,
                                selectedBoardId = state.installation?.id,
                                selectedCircuitId = state.selectedCircuitId,
                                onSelect = { board, circuit ->
                                    viewModel.selectCircuit(board, circuit); home()
                                },
                                onAddBoard = { viewModel.createInstallation(it) },
                                onAddCircuit = { board, label, rating -> viewModel.addCircuit(board, label, rating) },
                                onUpdateCircuit = { board, c -> viewModel.updateCircuit(board, c) },
                                onRemoveCircuit = { board, c -> viewModel.removeCircuit(board, c) },
                                onBench = { board, bench -> viewModel.setBenchRig(board, bench) },
                                onRenameBoard = { board, name -> viewModel.renameInstallation(board, name) },
                                onBack = home,
                            )
                        }

                        Screen.REFERENCE -> {
                            LaunchedEffect(Unit) { viewModel.beginReference() }
                            ReferenceScreen(
                                state = state,
                                circuit = viewModel.selectedCircuit,
                                onStart = { viewModel.recordBaseline() },
                                onMeasure = { screen = Screen.MEASURE },
                                onBack = home,
                            )
                        }

                        Screen.MEASURE -> MeasureScreen(
                            state = state,
                            circuit = viewModel.selectedCircuit,
                            onSupplyIsolated = { viewModel.setSupplyIsolated(it) },
                            onMeasure = { viewModel.measure() },
                            onLabel = { viewModel.label(it) },
                            onBack = home,
                        )

                        Screen.CALIBRATE -> {
                            LaunchedEffect(Unit) { viewModel.beginCalibration() }
                            CalibrateScreen(
                                state = state,
                                circuit = viewModel.selectedCircuit,
                                onWatts = { viewModel.setCalibrationWatts(it) },
                                onCapture = { viewModel.captureCalibration(it) },
                                onSave = { viewModel.saveCalibration() },
                                onClear = { viewModel.clearCalibration() },
                                onBack = home,
                            )
                        }

                        Screen.HISTORY -> {
                            LaunchedEffect(Unit) { viewModel.loadHistory() }
                            HistoryScreen(
                                boardName = state.installation?.name ?: "",
                                readings = state.history,
                                circuitLabels = state.installation?.circuits
                                    ?.associate { it.id to it.label } ?: emptyMap(),
                                onBack = home,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TaarColors = darkColorScheme(
    primary = Color(0xFFFFD54A),
    onPrimary = Color(0xFF1A1400),
    background = Color(0xFF0D0F13),
    surface = Color(0xFF0D0F13),
    surfaceVariant = Color(0xFF1B2029),
    onBackground = Color(0xFFEEF1F6),
    onSurface = Color(0xFFEEF1F6),
    onSurfaceVariant = Color(0xFFEEF1F6),
)
