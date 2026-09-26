package com.taar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.taar.data.AndroidFileSystem
import com.taar.domain.ScanStore
import com.taar.domain.Store
import com.taar.sensor.AudioCapture
import com.taar.sensor.CaptureCoordinator
import com.taar.sensor.MagCapture
import com.taar.sensor.MotionCapture
import com.taar.ml.ArcModel
import com.taar.ml.TaarAssistant

/**
 * Wiring and navigation.
 *
 * Four tabs -- Home, Tools, Ask, History -- and every task screen opens full-screen
 * from them, with Back returning to the tabs. The measuring flow is linear on
 * purpose -- phone check, circuit, reference, measure -- and Home shows which of
 * those are done, so there is never a question of what to press next.
 *
 * Dependencies are constructed by hand. The graph is four objects deep and an
 * injection framework would add a build step, a vocabulary and a failure mode for
 * no benefit at this size.
 */
class MainActivity : ComponentActivity() {

    enum class Screen { HOME, PHONE_CHECK, CIRCUITS, REFERENCE, MEASURE, CABLE_SCAN, LIVE, CALIBRATE }

    enum class Tab(val label: String) { HOME("Home"), TOOLS("Tools"), ASK("Ask AI"), HISTORY("History") }

    private lateinit var viewModel: TaarViewModel
    private lateinit var mag: MagCapture
    private lateinit var audio: AudioCapture
    private var arcModel: ArcModel? = null
    private lateinit var assistant: TaarAssistant

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
        arcModel = ArcModel.load(this)
        val coordinator = CaptureCoordinator(mag, audio, MotionCapture(sensorManager), arcModel)
        val files = AndroidFileSystem(this)
        assistant = TaarAssistant(this)
        viewModel = TaarViewModel(coordinator, store, ScanStore(files), assistant, arcModel?.selfCheck)
        viewModel.bootstrap()

        audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!audioGranted) requestAudio.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            TaarTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = TaarPalette.Background) {
                    var screen by remember { mutableStateOf(Screen.HOME) }
                    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                    val state by viewModel.state.collectAsState()
                    val home = { screen = Screen.HOME }

                    // Back is swallowed while a capture runs: leaving mid-capture left
                    // the next screen waiting on a capture it had not started.
                    // The live view is the exception: it only watches, so back pauses it and leaves.
                    BackHandler(enabled = screen != Screen.HOME) {
                        if (screen == Screen.LIVE) { viewModel.pauseLive(); home() }
                        else if (!state.busy) home()
                    }
                    // On a tab other than Home, back returns to Home before leaving the app.
                    BackHandler(enabled = screen == Screen.HOME && tab != Tab.HOME) { tab = Tab.HOME }

                    when (screen) {
                        Screen.HOME -> Tabs(tab, onTab = { tab = it }) {
                            when (tab) {
                                Tab.HOME -> HomeScreen(
                                    state = state,
                                    circuit = viewModel.selectedCircuit,
                                    onGo = { screen = it },
                                )
                                Tab.TOOLS -> ToolsScreen(onGo = { screen = it })
                                Tab.ASK -> AskScreen(
                                    state = state,
                                    onAsk = { viewModel.askGeneral(it) },
                                    onImportModel = { viewModel.importAssistantModel(it) },
                                    onClear = { viewModel.clearChat() },
                                )
                                Tab.HISTORY -> {
                                    LaunchedEffect(Unit) { viewModel.loadHistory() }
                                    HistoryScreen(
                                        boardName = state.installation?.name ?: "",
                                        readings = state.history,
                                        circuitLabels = state.installation?.circuits
                                            ?.associate { it.id to it.label } ?: emptyMap(),
                                        onBack = null,
                                    )
                                }
                            }
                        }

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
                            onAsk = { viewModel.askAssistant(it) },
                            onImportModel = { viewModel.importAssistantModel(it) },
                            onBack = home,
                        )

                        Screen.CABLE_SCAN -> {
                            LaunchedEffect(Unit) { viewModel.beginCableScan() }
                            CableScanScreen(
                                state = state,
                                circuit = viewModel.selectedCircuit,
                                onStart = { viewModel.startCableScan() },
                                onStop = { viewModel.stopCableScan() },
                                onOpen = { viewModel.openScan(it) },
                                onBack = home,
                            )
                        }

                        Screen.LIVE -> LivePhysicsScreen(
                            state = state,
                            circuit = viewModel.selectedCircuit,
                            onStart = { viewModel.startLive() },
                            onPause = { viewModel.pauseLive() },
                            onStop = { viewModel.stopLive() },
                            onReset = { viewModel.resetLive() },
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

                    }
                }
            }
        }
    }

    override fun onDestroy() {
        arcModel?.close()
        assistant.close()
        super.onDestroy()
    }
}

/** The tab shell: the current tab's screen above a bottom bar. */
@androidx.compose.runtime.Composable
private fun Tabs(
    current: MainActivity.Tab,
    onTab: (MainActivity.Tab) -> Unit,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        HorizontalDivider(color = TaarPalette.Outline)
        NavigationBar(containerColor = TaarPalette.Surface, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
            for (t in MainActivity.Tab.entries) {
                NavigationBarItem(
                    selected = t == current,
                    onClick = { onTab(t) },
                    icon = {
                        Icon(
                            when (t) {
                                MainActivity.Tab.HOME -> Icons.Filled.Home
                                MainActivity.Tab.TOOLS -> Icons.Filled.Build
                                MainActivity.Tab.ASK -> Icons.Filled.Star
                                MainActivity.Tab.HISTORY -> Icons.AutoMirrored.Filled.List
                            },
                            contentDescription = null,
                        )
                    },
                    label = { Text(t.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TaarPalette.Yellow, selectedTextColor = TaarPalette.Yellow,
                        indicatorColor = TaarPalette.Yellow.copy(alpha = 0.14f),
                        unselectedIconColor = TaarPalette.Faint, unselectedTextColor = TaarPalette.Faint,
                    ),
                )
            }
        }
    }
}
