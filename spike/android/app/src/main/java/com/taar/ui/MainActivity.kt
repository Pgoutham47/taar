package com.taar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import com.taar.domain.VoiceCommand
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
import com.taar.domain.BoardMapStore
import com.taar.domain.ScanStore
import com.taar.domain.Store
import com.taar.sensor.AudioCapture
import com.taar.sensor.CaptureCoordinator
import com.taar.sensor.MagCapture
import com.taar.sensor.MagStream
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

    enum class Screen { HOME, PHONE_CHECK, CIRCUITS, REFERENCE, MEASURE, CABLE_SCAN, LIVE, CALIBRATE, GEIGER, BOARD_MAP }

    enum class Tab(val label: String) { HOME("Home"), TOOLS("Tools"), ASK("Ask AI"), HISTORY("History") }

    private lateinit var viewModel: TaarViewModel
    private lateinit var mag: MagCapture
    private lateinit var magStream: MagStream
    private lateinit var boardPhotos: BoardPhotos
    private lateinit var voice: ResultVoice
    private lateinit var voiceInput: VoiceInput
    private lateinit var agent: VoiceAgent
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
        magStream = MagStream(sensorManager)
        audio = AudioCapture()
        val store = Store(AndroidFileSystem(this))
        arcModel = ArcModel.load(this)
        val coordinator = CaptureCoordinator(mag, audio, MotionCapture(sensorManager), arcModel)
        val files = AndroidFileSystem(this)
        assistant = TaarAssistant(this)
        voice = ResultVoice(this)
        viewModel = TaarViewModel(coordinator, store, ScanStore(files), BoardMapStore(files), voice, assistant, arcModel?.selfCheck)
        boardPhotos = BoardPhotos(this)
        viewModel.bootstrap()

        agent = VoiceAgent(viewModel, voice)
        voiceInput = VoiceInput(this).apply {
            onHeard = { command, free -> agent.handle(command, free) }
            vocabulary = { VoiceCommand.vocabulary(viewModel.state.value.installation?.circuits?.map { it.label } ?: emptyList()) }
            onRefused = { agent.endConversation(null); agent.say("", it) }
            // The microphone is the arc sensor: never listen while it is recording.
            canListen = {
                val s = viewModel.state.value
                when {
                    !audioGranted -> "Allow the microphone first."
                    s.capture != null -> "Wait for the measurement to finish."
                    s.scan?.running == true -> "Wait for the cable scan to finish."
                    s.live.running -> "Pause the live view first."
                    else -> { voice.stop(); null }
                }
            }
            prepare()
        }
        sayForTest(intent)

        audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!audioGranted) requestAudio.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            TaarTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = TaarPalette.Background) {
                    var screen by remember { mutableStateOf(Screen.HOME) }
                    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                    val state by viewModel.state.collectAsState()
                    // Screens opened from the Board Map go back to it, not to Home.
                    var fromMap by remember { mutableStateOf(false) }
                    val home: () -> Unit = {
                        if (fromMap && screen != Screen.BOARD_MAP) {
                            screen = Screen.BOARD_MAP
                        } else {
                            fromMap = false
                            screen = Screen.HOME
                        }
                    }

                    // Back is swallowed while a capture runs: leaving mid-capture left
                    // the next screen waiting on a capture it had not started.
                    // The live view is the exception: it only watches, so back pauses it and leaves.
                    BackHandler(enabled = screen != Screen.HOME) {
                        if (screen == Screen.LIVE) { viewModel.pauseLive(); home() }
                        else if (!state.busy) home()
                    }
                    // On a tab other than Home, back returns to Home before leaving the app.
                    BackHandler(enabled = screen == Screen.HOME && tab != Tab.HOME) { tab = Tab.HOME }

                    // Voice goes where a tap would. Opening a mode by voice switches it on.
                    agent.openScreen = { fromMap = false; screen = it }
                    agent.navigate = { place ->
                        fromMap = false
                        when (place) {
                            VoiceCommand.Place.HOME -> { screen = Screen.HOME; tab = Tab.HOME }
                            VoiceCommand.Place.TOOLS -> { screen = Screen.HOME; tab = Tab.TOOLS }
                            VoiceCommand.Place.ASK -> { screen = Screen.HOME; tab = Tab.ASK }
                            VoiceCommand.Place.HISTORY -> { screen = Screen.HOME; tab = Tab.HISTORY }
                            VoiceCommand.Place.BOARD_MAP -> {
                                if (!state.boardMapEnabled) viewModel.setBoardMapEnabled(true)
                                screen = Screen.BOARD_MAP
                            }
                            VoiceCommand.Place.GEIGER -> {
                                if (!state.geigerEnabled) viewModel.setGeigerEnabled(true)
                                screen = Screen.GEIGER
                            }
                            VoiceCommand.Place.CIRCUITS -> screen = Screen.CIRCUITS
                            VoiceCommand.Place.CALIBRATE -> screen = Screen.CALIBRATE
                            VoiceCommand.Place.CABLE_SCAN -> screen = Screen.CABLE_SCAN
                            VoiceCommand.Place.LIVE -> screen = Screen.LIVE
                            VoiceCommand.Place.PHONE_CHECK -> screen = Screen.PHONE_CHECK
                        }
                    }
                    // A question asked by voice is answered by voice.
                    LaunchedEffect(state.assistant.busy) { if (!state.assistant.busy) agent.onAssistantIdle() }

                    // A capture ending restarts a conversation's quiet time.
                    LaunchedEffect(state.busy) { if (!state.busy) agent.captureEnded() }

                    // A conversation's next turn: listen again once nothing is recording,
                    // speaking or thinking. The short pause lets the speaker fall silent.
                    LaunchedEffect(agent.conversation, agent.waiting, state.busy, voice.speaking, agent.thinking,
                        state.assistant.busy, voiceInput.listening) {
                        if (agent.conversation && agent.waiting && !state.busy && !voice.speaking && !agent.thinking &&
                            !state.assistant.busy && !voiceInput.listening) {
                            kotlinx.coroutines.delay(400)
                            agent.turnStarted()
                            voiceInput.listenTurn()
                        }
                    }

                    Box(Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.HOME -> Tabs(tab, onTab = { tab = it }) {
                            when (tab) {
                                Tab.HOME -> HomeScreen(
                                    state = state,
                                    circuit = viewModel.selectedCircuit,
                                    onGo = { screen = it },
                                )
                                Tab.TOOLS -> ToolsScreen(
                                    geigerEnabled = state.geigerEnabled,
                                    onGeigerEnabled = { viewModel.setGeigerEnabled(it) },
                                    boardMapEnabled = state.boardMapEnabled,
                                    onBoardMapEnabled = { viewModel.setBoardMapEnabled(it) },
                                    onGo = { screen = it },
                                )
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
                            onCostHours = { viewModel.setCostHours(it) },
                            onTariffRate = { viewModel.setTariffRate(it) },
                            onCalibrate = { screen = Screen.CALIBRATE },
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

                        Screen.GEIGER -> GeigerScreen(stream = magStream, onBack = home)

                        Screen.BOARD_MAP -> {
                            LaunchedEffect(state.installation?.id) { viewModel.openBoardMap() }
                            BoardMapScreen(
                                state = state,
                                photos = boardPhotos,
                                onPlace = { id, x, y -> viewModel.placePin(id, x, y) },
                                onAddAt = { label, rating, x, y -> viewModel.addCircuitAt(label, rating, x, y) },
                                onRemove = { viewModel.removePin(it) },
                                onMeasure = { circuit ->
                                    state.installation?.let { viewModel.selectCircuit(it.id, circuit.id) }
                                    fromMap = true
                                    screen = if (circuit.baseline?.isSufficient == true) Screen.MEASURE else Screen.REFERENCE
                                },
                                onCircuits = { fromMap = true; screen = Screen.CIRCUITS },
                                onBack = home,
                            )
                        }

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
                    VoiceLayer(voiceInput, agent, bottom = if (screen == Screen.HOME) 96.dp else 16.dp, onMic = {
                        // One tap starts a conversation; another ends it.
                        if (agent.conversation || voiceInput.listening) {
                            voiceInput.cancel()
                            agent.endConversation("Okay. Tap the mic when you need me.")
                        } else {
                            agent.startConversation()
                        }
                    })
                    }
                }
            }
        }
    }

    /**
     * Holding volume-down talks to Taar, released stops. It works with the phone
     * pressed against a cable and the screen facing the wall. The volume itself is
     * left alone while the app is open.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && ::voiceInput.isInitialized) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) voiceInput.holdStart()
                KeyEvent.ACTION_UP -> voiceInput.holdEnd()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        sayForTest(intent)
    }

    /**
     * Debug builds only: `adb shell am start -n com.taar/.ui.MainActivity --es say "measure kitchen"`
     * hands the text to the voice agent as if it had been heard, so every command can
     * be exercised on an emulator, which has no microphone to speak into.
     */
    private fun sayForTest(intent: android.content.Intent?) {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val text = intent?.getStringExtra("say") ?: return
        if (!debuggable) return
        // Mid-conversation, the phrase stands in for what this listening turn heard.
        if (voiceInput.listening) {
            voiceInput.cancel()
            window.decorView.postDelayed({ agent.handle(text, text) }, 300)
        } else {
            window.decorView.post { agent.handle(text, text) }
        }
    }

    override fun onDestroy() {
        voiceInput.close()
        arcModel?.close()
        assistant.close()
        voice.close()
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
