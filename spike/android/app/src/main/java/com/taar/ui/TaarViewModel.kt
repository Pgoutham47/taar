package com.taar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taar.domain.AmpCalibration
import com.taar.domain.Baseline
import com.taar.domain.BoardMap
import com.taar.domain.BoardMapStore
import com.taar.domain.LineState
import com.taar.domain.SpokenResult
import com.taar.domain.EnergyCost
import com.taar.domain.VoiceCommand
import com.taar.domain.Classifier
import com.taar.domain.CentroidClassifier
import com.taar.domain.CableScan
import com.taar.domain.AssistantPrompt
import com.taar.domain.Features
import com.taar.domain.ProductKnowledge
import com.taar.domain.Fusion
import com.taar.domain.LabelledSample
import com.taar.domain.Prediction
import com.taar.domain.Circuit
import com.taar.domain.Installation
import com.taar.domain.Metrics
import com.taar.domain.RankedFault
import com.taar.domain.Reading
import com.taar.domain.RulesEngine
import com.taar.domain.ScanStore
import com.taar.domain.Status
import com.taar.domain.Store
import com.taar.domain.Thresholds
import com.taar.ml.ArcModel
import com.taar.ml.TaarAssistant
import com.taar.sensor.CaptureCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds screen state and sequences captures.
 *
 * Contains no measurement logic and no interpretation: it calls the coordinator,
 * hands the result to the domain and stores what comes back. That separation is why
 * the rules and thresholds are tested off-device.
 */
class TaarViewModel(
    private val coordinator: CaptureCoordinator,
    private val store: Store,
    private val scanStore: ScanStore,
    private val boardMaps: BoardMapStore,
    private val speaker: Speaker,
    private val assistant: TaarAssistant,
    /** Null when the on-device model did not load. */
    private val aiSelfCheck: ArcModel.SelfCheck? = null,
) : ViewModel() {

    /** What a running capture is for, so the screen can say so. */
    enum class CaptureKind { REFERENCE, MEASURE, CALIBRATE_OFF, CALIBRATE_ON }

    /** A capture in progress: capture [step] of [total]. */
    /** [settling]: the second after the tap, before recording starts, while the phone steadies. */
    data class CaptureProgress(val kind: CaptureKind, val step: Int, val total: Int, val settling: Boolean = false)

    /** One capture as the live view shows it: the raw signals and every result derived from them. */
    data class LiveFrame(
        val number: Int,
        val reading: CaptureCoordinator.Reading,
        /** Null without a sufficient reference: no deviation, rules or fusion then. */
        val metrics: Metrics?,
        val faults: List<RankedFault>,
        val fusion: Fusion.Analysis?,
        val thresholds: Thresholds,
    )

    /** The live physics view. [frame] survives a pause; stop and reset clear it. */
    data class Live(
        val running: Boolean = false,
        val listening: Boolean = false,
        val frame: LiveFrame? = null,
        val captures: Int = 0,
    )

    /** The on-device language model's panel under a result. */
    data class Assistant(
        val installed: Boolean = false,
        /** 0..1 while the model file is being copied in. */
        val importing: Float? = null,
        val busy: Boolean = false,
        val question: String? = null,
        val answer: String = "",
        val concerns: List<String> = emptyList(),
        val error: String? = null,
    )

    /** One message in the Ask tab. [sources] are the notes an answer was written from. */
    data class ChatMessage(
        val fromUser: Boolean,
        val text: String,
        val sources: List<String> = emptyList(),
        val concerns: List<String> = emptyList(),
        val error: String? = null,
    )

    /** A Cable Scan in progress or just finished. */
    data class Scan(
        val circuitLabel: String,
        val running: Boolean = false,
        /** True while a point is being captured; false while the technician moves the phone. */
        val listening: Boolean = false,
        val points: List<CableScan.Point> = emptyList(),
        val result: CableScan.Result? = null,
    )

    /** The last phone check, persisted so it need not be repeated every launch. */
    data class PhoneCheck(val passed: Boolean, val rateHz: Double, val atMillis: Long)

    data class Calibration(
        val watts: String = "",
        val offFields: List<Double> = emptyList(),
        val offConfidences: List<Double> = emptyList(),
        val onFields: List<Double> = emptyList(),
        val onConfidences: List<Double> = emptyList(),
        val result: AmpCalibration.Result? = null,
        val saved: Boolean = false,
    ) {
        val offDone: Boolean get() = offFields.isNotEmpty()
        val onDone: Boolean get() = onFields.isNotEmpty()
    }

    data class UiState(
        val installation: Installation? = null,
        val selectedCircuitId: String? = null,
        val capture: CaptureProgress? = null,
        /** Null until a measurement completes; cleared when a new one starts. */
        val lastReading: Reading? = null,
        val lastStatus: Status = Status.UNKNOWN,
        val lastFaults: List<RankedFault> = emptyList(),
        val lastImpliedCurrentA: Double? = null,
        /** The label the technician confirmed on the current reading, if any. */
        val lastLabel: Status? = null,
        val thresholds: Thresholds = Thresholds.DEFAULT,
        /** One-off problem to show on the current screen. */
        val error: String? = null,
        /** Set when a reference has just been recorded, for the confirmation panel. */
        val referenceJustRecorded: Boolean = false,
        val supplyIsolated: Boolean = false,
        val phoneCheck: PhoneCheck? = null,
        val calibration: Calibration = Calibration(),
        val installations: List<Installation> = emptyList(),
        val history: List<Store.LabelledReading> = emptyList(),
        val spectrogram: com.taar.dsp.Spectrogram.Result? = null,
        val prediction: Prediction? = null,
        /** The on-device model's probability the last measurement sounded like an arc. */
        val aiArcProbability: Float? = null,
        val aiSelfCheck: ArcModel.SelfCheck? = null,
        /** The model's scores for each capture of the reference just recorded. */
        val referenceAi: List<Float> = emptyList(),
        /** How fast the phone turned during the last measurement, rad/s. */
        val lastMotion: Double? = null,
        /** The same, for each capture of the reference just recorded. */
        val referenceMotion: List<Double> = emptyList(),
        /** The unified result of the last measurement. */
        val fusion: Fusion.Analysis? = null,
        val scan: Scan? = null,
        /** Earlier Cable Scans of the selected circuit, newest first. */
        val scanHistory: List<ScanStore.Session> = emptyList(),
        val live: Live = Live(),
        val assistant: Assistant = Assistant(),
        val chat: List<ChatMessage> = emptyList(),
        /** Geiger mode is an extra, off until switched on in Tools. */
        val geigerEnabled: Boolean = false,
        /** Board Map is an extra too, off until switched on in Tools. */
        val boardMapEnabled: Boolean = false,
        /** The selected board's map: where each circuit's dot sits, and what it shows. */
        val boardMap: BoardMap.Map = BoardMap.Map(),
        val boardDots: Map<String, BoardMap.Dot> = emptyMap(),
        /** Rupees per unit, for the cost of a calibrated reading. One rate for the whole home. */
        val tariffRate: Double = EnergyCost.DEFAULT_RATE,
        /** How many hours a day the selected circuit's load runs, as the technician set it. */
        val costHours: Double = EnergyCost.DEFAULT_HOURS,
    ) {
        val busy: Boolean get() = capture != null || scan?.running == true || live.running
    }

    private val _state = MutableStateFlow(
        UiState(aiSelfCheck = aiSelfCheck, assistant = Assistant(installed = assistant.installed)),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    val selectedCircuit: Circuit?
        get() = _state.value.let { s ->
            s.installation?.circuits?.firstOrNull { it.id == s.selectedCircuitId }
        }

    private val classifier: Classifier = CentroidClassifier()

    private fun update(block: (UiState) -> UiState) {
        _state.value = block(_state.value)
    }

    // ---- start-up ----

    /**
     * Reopens the board and circuit that were open last time, or creates a starter
     * board on first launch.
     *
     * Without a starter the app opens with nothing selected and every action is a
     * no-op, which on a demo floor reads as a broken build rather than an empty one.
     */
    fun bootstrap() {
        if (store.listInstallationIds().isEmpty()) {
            store.saveInstallation(
                Installation(
                    id = "board-1",
                    name = "Board 1",
                    circuits = listOf(
                        Circuit("c1", "Circuit 1", breakerRatingA = 6.0),
                        Circuit("c2", "Circuit 2", breakerRatingA = 16.0),
                        Circuit("c3", "Circuit 3", breakerRatingA = 16.0),
                    ),
                ),
            )
        }
        refreshInstallations()

        val boardId = store.loadSetting(KEY_BOARD)
            ?.takeIf { id -> _state.value.installations.any { it.id == id } }
            ?: _state.value.installations.first().id
        val installation = store.loadInstallation(boardId)
        val circuitId = store.loadSetting(KEY_CIRCUIT)
            ?.takeIf { id -> installation?.circuits?.any { it.id == id } == true }
            ?: installation?.circuits?.firstOrNull()?.id

        update {
            it.copy(
                installation = installation,
                selectedCircuitId = circuitId,
                phoneCheck = loadPhoneCheck(),
                geigerEnabled = store.loadSetting(KEY_GEIGER) == "true",
                tariffRate = store.loadSetting(KEY_RATE)?.toDoubleOrNull()?.takeIf { r -> r > 0 } ?: EnergyCost.DEFAULT_RATE,
                costHours = hoursFor(circuitId),
                boardMapEnabled = store.loadSetting(KEY_BOARD_MAP) == "true",
            )
        }
        refreshThresholds()
    }

    // ---- cost ----

    private fun hoursFor(circuitId: String?): Double =
        circuitId?.let { store.loadSetting("$KEY_HOURS$it")?.toDoubleOrNull() } ?: EnergyCost.DEFAULT_HOURS

    fun setCostHours(hours: Double) {
        val id = _state.value.selectedCircuitId ?: return
        if (hours <= 0 || hours > 24) return
        store.saveSetting("$KEY_HOURS$id", hours.toString())
        update { it.copy(costHours = hours) }
    }

    fun setTariffRate(rate: Double) {
        if (rate <= 0 || rate > 100) return
        store.saveSetting(KEY_RATE, rate.toString())
        update { it.copy(tariffRate = rate) }
    }

    fun setGeigerEnabled(on: Boolean) {
        store.saveSetting(KEY_GEIGER, on.toString())
        update { it.copy(geigerEnabled = on) }
    }

    fun setBoardMapEnabled(on: Boolean) {
        store.saveSetting(KEY_BOARD_MAP, on.toString())
        update { it.copy(boardMapEnabled = on) }
    }

    // ---- board map ----

    /** Loads the selected board's map, and each circuit's dot from its latest result or reading. */
    fun openBoardMap() {
        val inst = _state.value.installation ?: return
        val map = boardMaps.load(inst.id)
        val latest = store.loadReadings(inst.id).map { it.reading }
            .groupBy { it.circuitId }.mapValues { (_, rs) -> rs.maxBy { it.epochMillis } }
        val dots = inst.circuits.associate { c -> c.id to BoardMap.dotOf(map.results[c.id], latest[c.id]) }
        update { it.copy(boardMap = map, boardDots = dots) }
    }

    /** Every circuit's latest result, for "which wires have a problem?". Same source as the map's dots. */
    fun latestDots(): List<Pair<String, BoardMap.Dot>> {
        val inst = _state.value.installation ?: return emptyList()
        openBoardMap()
        val dots = _state.value.boardDots
        return inst.circuits.map { it.label to (dots[it.id] ?: BoardMap.dotOf(null, null)) }
    }

    // ---- voice ----

    /**
     * Asks the on-device model to rephrase a request the rules did not recognise
     * into one of their commands, and parses that with the same rules. [onDone]
     * gets null when there is no model, it is busy, or the rephrasing still means
     * nothing -- the model can only ever pick an action the rules already know.
     */
    fun rephraseCommand(heard: String, onDone: (VoiceCommand.Command?) -> Unit) {
        val a = _state.value.assistant
        if (!a.installed || a.busy) { onDone(null); return }
        val circuits = _state.value.installation?.circuits ?: emptyList()
        val names = circuits.map { VoiceCommand.Name(it.id, it.label) }
        update { it.copy(assistant = it.assistant.copy(busy = true)) }
        viewModelScope.launch {
            var reply = ""
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                assistant.generate(VoiceCommand.modelPrompt(heard, circuits.map { it.label })) { reply = it }
            }
            update { it.copy(assistant = it.assistant.copy(busy = false)) }
            onDone(VoiceCommand.parse(VoiceCommand.firstLine(reply), names))
        }
    }

    fun placePin(circuitId: String, x: Double, y: Double) {
        val inst = _state.value.installation ?: return
        boardMaps.placePin(inst.id, circuitId, BoardMap.Pin(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0)))
        openBoardMap()
    }

    /**
     * Board Map's quickest setup: a tap on a switch in the photo makes a new
     * circuit and puts its dot there, in one step.
     */
    fun addCircuitAt(label: String, breakerRatingA: Double?, x: Double, y: Double) {
        val inst = _state.value.installation ?: return
        val id = "c${System.currentTimeMillis()}"
        saveInstallation(inst.copy(circuits = inst.circuits + Circuit(id, label, breakerRatingA)))
        placePin(id, x, y)
    }

    fun removePin(circuitId: String) {
        val inst = _state.value.installation ?: return
        boardMaps.removePin(inst.id, circuitId)
        openBoardMap()
    }

    fun recordPhoneCheck(passed: Boolean, rateHz: Double) {
        val check = PhoneCheck(passed, rateHz, System.currentTimeMillis())
        store.saveSetting(KEY_PHONE_CHECK, "${check.passed} ${check.rateHz} ${check.atMillis}")
        update { it.copy(phoneCheck = check) }
    }

    private fun loadPhoneCheck(): PhoneCheck? =
        store.loadSetting(KEY_PHONE_CHECK)?.split(" ")?.takeIf { it.size == 3 }?.let {
            runCatching { PhoneCheck(it[0].toBoolean(), it[1].toDouble(), it[2].toLong()) }
                .getOrNull()
        }

    // ---- boards and circuits ----

    fun refreshInstallations() {
        update {
            it.copy(installations = store.listInstallationIds().mapNotNull { id -> store.loadInstallation(id) })
        }
    }

    fun createInstallation(name: String) {
        val inst = Installation(id = "board-${System.currentTimeMillis()}", name = name)
        store.saveInstallation(inst)
        refreshInstallations()
    }

    /** Saves a board. Every edit on the circuits screen goes through here immediately. */
    fun saveInstallation(updated: Installation) {
        store.saveInstallation(updated)
        update {
            it.copy(installation = if (it.installation?.id == updated.id) updated else it.installation)
        }
        refreshInstallations()
    }

    fun addCircuit(installationId: String, label: String, breakerRatingA: Double?) {
        val inst = store.loadInstallation(installationId) ?: return
        saveInstallation(
            inst.copy(
                circuits = inst.circuits + Circuit(
                    id = "c${System.currentTimeMillis()}",
                    label = label,
                    breakerRatingA = breakerRatingA,
                ),
            ),
        )
    }

    fun updateCircuit(installationId: String, circuit: Circuit) {
        val inst = store.loadInstallation(installationId) ?: return
        saveInstallation(inst.copy(circuits = inst.circuits.map { if (it.id == circuit.id) circuit else it }))
    }

    fun removeCircuit(installationId: String, circuitId: String) {
        val inst = store.loadInstallation(installationId) ?: return
        saveInstallation(inst.copy(circuits = inst.circuits.filterNot { it.id == circuitId }))
        if (_state.value.installation?.id == installationId && _state.value.selectedCircuitId == circuitId) {
            update { it.copy(selectedCircuitId = null) }
        }
    }

    fun renameInstallation(installationId: String, name: String) {
        val inst = store.loadInstallation(installationId) ?: return
        saveInstallation(inst.copy(name = name))
    }

    fun setBenchRig(installationId: String, bench: Boolean) {
        val inst = store.loadInstallation(installationId) ?: return
        saveInstallation(inst.copy(isBenchRig = bench))
    }

    /** Opens a board and selects one of its circuits, and remembers both. */
    fun selectCircuit(installationId: String, circuitId: String) {
        val inst = store.loadInstallation(installationId) ?: return
        store.saveSetting(KEY_BOARD, installationId)
        store.saveSetting(KEY_CIRCUIT, circuitId)
        update {
            it.copy(
                installation = inst,
                selectedCircuitId = circuitId,
                lastReading = null,
                lastFaults = emptyList(),
                lastLabel = null,
                calibration = Calibration(),
                referenceJustRecorded = false,
                error = null,
                costHours = hoursFor(circuitId),
            )
        }
        refreshThresholds()
    }

    fun loadHistory() {
        val id = _state.value.installation?.id ?: return
        update { it.copy(history = store.loadReadings(id)) }
    }

    fun clearError() = update { it.copy(error = null) }

    // ---- reference ----

    fun beginReference() = update { it.copy(referenceJustRecorded = false, error = null) }

    /**
     * Records a reference for the selected circuit.
     *
     * Takes [Baseline.MIN_SAMPLES] captures rather than one. A single capture would
     * give a median with no spread, and every threshold in the app is expressed in
     * MADs from that spread.
     */
    fun recordBaseline() {
        val circuit = selectedCircuit ?: return
        val installation = _state.value.installation ?: return
        if (_state.value.busy) return
        // Busy at once, as in measure(), so the microphone is never reopened in between.
        update { it.copy(capture = CaptureProgress(CaptureKind.REFERENCE, 1, Baseline.MIN_SAMPLES, settling = true)) }

        viewModelScope.launch {
            update { it.copy(referenceJustRecorded = false, error = null) }
            val captures = captureSeries(CaptureKind.REFERENCE, Baseline.MIN_SAMPLES)

            if (captures.size < Baseline.MIN_SAMPLES) {
                update {
                    it.copy(
                        capture = null,
                        error = "Only ${captures.size} of ${Baseline.MIN_SAMPLES} captures worked. " +
                            "Run the phone check, then try again.",
                    )
                }
                return@launch
            }

            val baseline = Baseline(
                System.currentTimeMillis(),
                captures.map { it.fieldAmplitudeUt }.toDoubleArray(),
                captures.map { it.arcModulationIndex }.toDoubleArray(),
                captures.map { it.lineConfidence }.toDoubleArray(),
            )
            val updated = installation.copy(
                circuits = installation.circuits.map {
                    if (it.id == circuit.id) it.copy(baseline = baseline) else it
                },
            )
            store.saveInstallation(updated)
            update {
                it.copy(
                    installation = updated, capture = null, referenceJustRecorded = true,
                    referenceAi = captures.mapNotNull { c -> c.aiArcProbability },
                    referenceMotion = captures.mapNotNull { c -> c.motionRadPerS },
                )
            }
            refreshInstallations()
            refreshThresholds()
        }
    }

    // ---- measure ----

    fun setSupplyIsolated(isolated: Boolean) = update { it.copy(supplyIsolated = isolated) }

    /**
     * Captures once and diagnoses.
     *
     * Refuses without a sufficient baseline rather than scoring against a default.
     * A number produced without a reference would look exactly like one produced
     * with it.
     */
    fun measure() {
        val circuit = selectedCircuit ?: return
        val installation = _state.value.installation ?: return
        if (_state.value.busy) return

        if (circuit.baseline?.isSufficient != true) {
            update { it.copy(error = "Record a reference for this circuit first.") }
            return
        }
        // Busy from this instant, not from inside the coroutine: a voice conversation
        // checks busy to decide whether it may open the microphone again.
        update { it.copy(capture = CaptureProgress(CaptureKind.MEASURE, 1, 1, settling = true)) }

        viewModelScope.launch {
            // Cleared before capturing, so the screen can never show the previous
            // reading while the new one is still being taken.
            update {
                it.copy(
                    lastReading = null, lastFaults = emptyList(), lastLabel = null,
                    lastImpliedCurrentA = null, spectrogram = null, prediction = null,
                    aiArcProbability = null, lastMotion = null, fusion = null, error = null,
                    assistant = it.assistant.copy(question = null, answer = "", concerns = emptyList(), error = null),
                )
            }
            val result = captureSeries(CaptureKind.MEASURE, 1).firstOrNull()
            if (result == null) {
                update { it.copy(capture = null, error = "The capture failed. Run the phone check, then try again.") }
                return@launch
            }

            val reading = Reading(
                circuitId = circuit.id,
                epochMillis = System.currentTimeMillis(),
                fieldAmplitudeUt = result.fieldAmplitudeUt,
                lineConfidence = result.lineConfidence,
                lineContrast = result.lineContrast,
                arcModulationIndex = result.arcModulationIndex,
                fieldEstimateUsable = result.fieldEstimateUsable,
                supplyIsolated = _state.value.supplyIsolated,
            )

            val metrics = Metrics.derive(reading, circuit)
            val ranked = metrics?.let { RulesEngine.rank(it, _state.value.thresholds) } ?: emptyList()

            store.appendReading(installation.id, reading)

            // The classifier is advisory and sits alongside the rules, never in
            // front of them. A learned label with no explanation is not something
            // to act on at a live panel; it is a hint that the rules missed a
            // pattern this technician has seen before.
            trainClassifier(installation.id)
            val prediction = metrics?.let { classifier.classify(Features.of(it)) }

            // Reads what the layers above produced; changes none of it.
            val fusion = metrics?.let { m ->
                Fusion.analyse(
                    Fusion.Input(
                        metrics = m,
                        thresholds = _state.value.thresholds,
                        faults = ranked,
                        aiArcProbability = result.aiArcProbability,
                        aiThreshold = ArcModel.THRESHOLD,
                        motionRadPerS = result.motionRadPerS,
                        audioCaptured = result.spectrogram != null,
                        unprocessedAudio = result.unprocessedAudioGranted,
                        breakerRatingA = circuit.breakerRatingA,
                        history = earlierMetrics(installation.id, circuit, reading.epochMillis),
                    ),
                )
            }

            // The map shows what this screen showed, not a re-derivation of it.
            fusion?.let { f ->
                boardMaps.recordResult(installation.id, circuit.id,
                    BoardMap.LastResult(reading.epochMillis, f.outcome, LineState.of(reading.lineConfidence)))
                // Spoken only now, after the recording has finished: the phone is often
                // pressed against the cable, screen away from the technician.
                val cost = EnergyCost.estimate(metrics?.impliedCurrentA, _state.value.costHours, _state.value.tariffRate)
                speaker.speak(SpokenResult.of(f.outcome, LineState.of(reading.lineConfidence), circuit.label,
                    metrics?.impliedCurrentA, cost))
            }

            update {
                it.copy(
                    capture = null,
                    lastReading = reading,
                    lastFaults = ranked,
                    lastStatus = RulesEngine.status(ranked),
                    lastImpliedCurrentA = metrics?.impliedCurrentA,
                    spectrogram = result.spectrogram,
                    prediction = prediction,
                    aiArcProbability = result.aiArcProbability,
                    lastMotion = result.motionRadPerS,
                    fusion = fusion,
                )
            }
        }
    }

    // ---- on-device assistant ----

    /** Copies the model file the technician picked into private storage. */
    fun importAssistantModel(uri: android.net.Uri) {
        if (_state.value.assistant.importing != null || _state.value.assistant.busy) return
        update { it.copy(assistant = it.assistant.copy(importing = 0f, error = null)) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val error = assistant.import(uri) { p -> update { it.copy(assistant = it.assistant.copy(importing = p)) } }
            update { it.copy(assistant = it.assistant.copy(importing = null, installed = assistant.installed, error = error)) }
        }
    }

    /**
     * Asks the model about the result on screen. It sees only that result's
     * evidence; the answer streams in, and is checked for certainty about safety.
     */
    fun askAssistant(question: String) {
        val analysis = _state.value.fusion ?: return
        if (_state.value.assistant.busy) return
        val circuit = selectedCircuit
        val prompt = AssistantPrompt.build(analysis, circuit?.label, circuit?.breakerRatingA, question)
        update { it.copy(assistant = it.assistant.copy(busy = true, question = question.trim().ifEmpty {
            AssistantPrompt.PRESETS[0] }, answer = "", concerns = emptyList(), error = null)) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val error = assistant.generate(prompt) { text ->
                update { it.copy(assistant = it.assistant.copy(answer = text)) }
            }
            update {
                it.copy(assistant = it.assistant.copy(busy = false, error = error,
                    concerns = AssistantPrompt.concerns(it.assistant.answer)))
            }
        }
    }

    /**
     * The Ask tab: a question about the app, answered from the notes that match it.
     * One question at a time, sharing the model with the result panel.
     */
    fun askGeneral(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.assistant.busy || !_state.value.assistant.installed) return
        val notes = ProductKnowledge.lookup(q)
        val prompt = AssistantPrompt.general(q, notes)
        update {
            it.copy(
                assistant = it.assistant.copy(busy = true, error = null),
                chat = it.chat + ChatMessage(true, q) + ChatMessage(false, "", sources = notes.map { n -> n.title }),
            )
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val error = assistant.generate(prompt) { text ->
                update { s -> s.copy(chat = s.chat.dropLast(1) + s.chat.last().copy(text = text)) }
            }
            update { s ->
                val last = s.chat.last()
                s.copy(
                    assistant = s.assistant.copy(busy = false),
                    chat = s.chat.dropLast(1) + last.copy(error = error, concerns = AssistantPrompt.concerns(last.text)),
                )
            }
        }
    }

    fun clearChat() {
        if (!_state.value.assistant.busy) update { it.copy(chat = emptyList()) }
    }

    // ---- live physics view ----

    @Volatile private var liveOn = false

    /**
     * Captures back to back and publishes each one with everything derived from it.
     * The same coordinator, rules and fusion as a measurement; nothing is stored, so
     * watching never touches a circuit's history or thresholds.
     */
    fun startLive() {
        if (_state.value.busy) return
        speaker.stop()
        liveOn = true
        update { it.copy(live = it.live.copy(running = true)) }
        viewModelScope.launch {
            while (liveOn) {
                update { it.copy(live = it.live.copy(listening = true)) }
                val result = coordinator.capture(detail = true)
                val circuit = selectedCircuit
                if (result != null) {
                    val thresholds = _state.value.thresholds
                    val done = circuit?.let { interpret(result, it, thresholds) }
                    update {
                        val n = it.live.captures + 1
                        it.copy(live = it.live.copy(captures = n, frame = LiveFrame(n, result, done?.metrics,
                            done?.faults ?: emptyList(), done?.fusion, thresholds)))
                    }
                }
                update { it.copy(live = it.live.copy(listening = false)) }
            }
            update { it.copy(live = it.live.copy(running = false, listening = false)) }
        }
    }

    /** Stops after the capture in progress and keeps the last frame on screen. */
    fun pauseLive() {
        liveOn = false
    }

    /** Stops and clears the screen. */
    fun stopLive() {
        liveOn = false
        update { it.copy(live = it.live.copy(frame = null, captures = 0)) }
    }

    /** Clears the display; a running view carries on from a fresh start. */
    fun resetLive() = update { it.copy(live = it.live.copy(frame = null, captures = 0)) }

    // ---- cable scan ----

    /** Set false by [stopCableScan]; the loop finishes the point it is on, then stops. */
    @Volatile private var scanning = false

    fun beginCableScan() {
        val circuit = selectedCircuit ?: return
        if (_state.value.busy) return
        val installation = _state.value.installation ?: return
        update {
            it.copy(
                scan = Scan(circuit.label),
                scanHistory = scanStore.list(installation.id, circuit.id),
                error = null,
            )
        }
    }

    /**
     * Captures one point after another until stopped, each exactly as a measurement
     * is captured and interpreted. Only the comparison across points is new.
     */
    fun startCableScan() {
        val circuit = selectedCircuit ?: return
        val installation = _state.value.installation ?: return
        if (_state.value.busy || circuit.baseline?.isSufficient != true) return

        speaker.stop()
        scanning = true
        update { it.copy(scan = Scan(circuit.label, running = true)) }
        viewModelScope.launch {
            val thresholds = _state.value.thresholds
            val points = mutableListOf<CableScan.Point>()
            while (scanning && points.size < MAX_SCAN_POINTS) {
                update { s -> s.copy(scan = s.scan?.copy(listening = true)) }
                val result = coordinator.capture()
                val done = result?.let { interpret(it, circuit, thresholds) }
                val reading = done?.reading
                val metrics = done?.metrics
                val fused = done?.fusion
                if (result != null && reading != null && metrics != null && fused != null) {
                    val audio = result.spectrogram != null
                    points += CableScan.Point(
                        index = points.size,
                        epochMillis = reading.epochMillis,
                        lineConfidence = reading.lineConfidence,
                        loadZ = metrics.loadZ,
                        arcZ = metrics.arcZ,
                        aiProbability = result.aiArcProbability,
                        outcome = fused.outcome,
                        quality = fused.quality,
                        motionRadPerS = result.motionRadPerS,
                        audioCaptured = audio,
                        strength = CableScan.strengthOf(metrics.arcZ, result.aiArcProbability, thresholds),
                    )
                }
                update { s -> s.copy(scan = s.scan?.copy(listening = false, points = points.toList())) }
                if (!scanning || points.size >= MAX_SCAN_POINTS) break
                delay(SCAN_MOVE_MILLIS)
            }
            scanning = false

            val analysis = CableScan.analyse(points)
            if (points.isNotEmpty()) {
                scanStore.save(installation.id, ScanStore.Session(circuit.id, circuit.label,
                    points.first().epochMillis, points.toList()))
            }
            update {
                it.copy(
                    scan = it.scan?.copy(running = false, listening = false, points = points.toList(), result = analysis),
                    scanHistory = scanStore.list(installation.id, circuit.id),
                )
            }
        }
    }

    fun stopCableScan() {
        scanning = false
    }

    /** Reopens an earlier scan's result from history. */
    fun openScan(session: ScanStore.Session) {
        if (_state.value.busy) return
        update { it.copy(scan = Scan(session.circuitLabel, points = session.points,
            result = CableScan.analyse(session.points))) }
    }

    /** A capture interpreted exactly as a measurement is: metrics, rules, fusion. */
    private class Interpreted(
        val reading: Reading,
        val metrics: Metrics?,
        val faults: List<RankedFault>,
        val fusion: Fusion.Analysis?,
    )

    /**
     * The one place a capture becomes a result, shared by Cable Scan and the live
     * view. History is left out of the fusion: their points are positions
     * or moments, not earlier readings of the circuit.
     */
    private fun interpret(result: CaptureCoordinator.Reading, circuit: Circuit, thresholds: Thresholds): Interpreted {
        val reading = toReading(result, circuit)
        val metrics = Metrics.derive(reading, circuit)
        val faults = metrics?.let { RulesEngine.rank(it, thresholds) } ?: emptyList()
        val fusion = metrics?.let { m ->
            Fusion.analyse(
                Fusion.Input(
                    metrics = m, thresholds = thresholds, faults = faults,
                    aiArcProbability = result.aiArcProbability, aiThreshold = ArcModel.THRESHOLD,
                    motionRadPerS = result.motionRadPerS, audioCaptured = result.spectrogram != null,
                    unprocessedAudio = result.unprocessedAudioGranted,
                    breakerRatingA = circuit.breakerRatingA, history = emptyList(),
                ),
            )
        }
        return Interpreted(reading, metrics, faults, fusion)
    }

    private fun toReading(result: CaptureCoordinator.Reading, circuit: Circuit) = Reading(
        circuitId = circuit.id,
        epochMillis = System.currentTimeMillis(),
        fieldAmplitudeUt = result.fieldAmplitudeUt,
        lineConfidence = result.lineConfidence,
        lineContrast = result.lineContrast,
        arcModulationIndex = result.arcModulationIndex,
        fieldEstimateUsable = result.fieldEstimateUsable,
        supplyIsolated = false,
    )

    /** Confirming a label is what moves the thresholds; nothing else does. */
    fun label(status: Status) {
        val reading = _state.value.lastReading ?: return
        val installation = _state.value.installation ?: return
        if (_state.value.lastLabel != null) return
        store.appendReading(installation.id, reading, status)
        update { it.copy(lastLabel = status) }
        if (!installation.isBenchRig) refreshThresholds()
    }

    // ---- amp calibration ----

    fun beginCalibration() = update { it.copy(calibration = Calibration(), error = null) }

    fun setCalibrationWatts(watts: String) =
        update { it.copy(calibration = it.calibration.copy(watts = watts).withResult()) }

    fun captureCalibration(applianceOn: Boolean) {
        if (_state.value.busy || selectedCircuit == null) return
        viewModelScope.launch {
            val kind = if (applianceOn) CaptureKind.CALIBRATE_ON else CaptureKind.CALIBRATE_OFF
            val captures = captureSeries(kind, CALIBRATION_CAPTURES)
            update { s ->
                val c = s.calibration
                val next = if (applianceOn) {
                    c.copy(
                        onFields = captures.filter { it.fieldEstimateUsable }.map { it.fieldAmplitudeUt },
                        onConfidences = captures.filter { it.fieldEstimateUsable }.map { it.lineConfidence },
                    )
                } else {
                    // A new OFF run invalidates any ON run taken against the old one.
                    Calibration(
                        watts = c.watts,
                        offFields = captures.filter { it.fieldEstimateUsable }.map { it.fieldAmplitudeUt },
                        offConfidences = captures.filter { it.fieldEstimateUsable }.map { it.lineConfidence },
                    )
                }
                s.copy(capture = null, calibration = next.withResult())
            }
        }
    }

    private fun Calibration.withResult(): Calibration {
        if (!offDone || !onDone) return copy(result = null, saved = false)
        val amps = watts.toDoubleOrNull()?.let { AmpCalibration.ampsFromWatts(it) } ?: 0.0
        return copy(
            result = AmpCalibration.calibrate(offFields, offConfidences, onFields, onConfidences, amps),
            saved = false,
        )
    }

    fun saveCalibration() {
        val ok = _state.value.calibration.result as? AmpCalibration.Result.Ok ?: return
        val circuit = selectedCircuit ?: return
        val installation = _state.value.installation ?: return
        saveInstallation(
            installation.copy(
                circuits = installation.circuits.map {
                    if (it.id == circuit.id) it.copy(utPerAmp = ok.utPerAmp) else it
                },
            ),
        )
        update { it.copy(calibration = it.calibration.copy(saved = true)) }
    }

    fun clearCalibration() {
        val circuit = selectedCircuit ?: return
        val installation = _state.value.installation ?: return
        saveInstallation(
            installation.copy(
                circuits = installation.circuits.map {
                    if (it.id == circuit.id) it.copy(utPerAmp = null) else it
                },
            ),
        )
        beginCalibration()
    }

    // ---- internals ----

    /** Runs [count] captures back to back, publishing progress. Failed captures are dropped. */
    private suspend fun captureSeries(kind: CaptureKind, count: Int): List<CaptureCoordinator.Reading> {
        val out = mutableListOf<CaptureCoordinator.Reading>()
        // The microphone is the arc sensor: nothing may be playing while it records.
        speaker.stop()
        // Every series starts from a tap on the screen; let the phone settle first.
        update { it.copy(capture = CaptureProgress(kind, 1, count, settling = true)) }
        delay(SETTLE_MILLIS)
        for (i in 1..count) {
            update { it.copy(capture = CaptureProgress(kind, i, count)) }
            coordinator.capture()?.let { out += it }
        }
        return out
    }

    /**
     * Retrains from every labelled reading at this board.
     *
     * Cheap enough to do on each capture — a centroid is a median over a handful of
     * four-element vectors — and doing it here means a label applied a minute ago is
     * already in effect.
     */
    private fun trainClassifier(installationId: String) {
        val circuits = _state.value.installation?.circuits ?: return
        val samples = store.loadReadings(installationId)
            .mapNotNull { lr ->
                val label = lr.label ?: return@mapNotNull null
                val circuit = circuits.firstOrNull { it.id == lr.reading.circuitId }
                    ?: return@mapNotNull null
                val m = Metrics.derive(lr.reading, circuit) ?: return@mapNotNull null
                LabelledSample(label.name, Features.of(m))
            }
        if (samples.size >= MIN_TRAINING_SAMPLES) classifier.train(samples)
    }

    /**
     * This circuit's earlier readings, newest first, as metrics against its current
     * reference. Labelling a reading stores it a second time, so they are counted once.
     */
    private fun earlierMetrics(installationId: String, circuit: Circuit, before: Long): List<Metrics> =
        store.loadReadings(installationId)
            .map { it.reading }
            .filter { it.circuitId == circuit.id && it.epochMillis < before }
            .distinctBy { it.epochMillis }
            .sortedByDescending { it.epochMillis }
            .take(EARLIER_READINGS)
            .mapNotNull { Metrics.derive(it, circuit) }

    private fun refreshThresholds() {
        val circuit = selectedCircuit
        val installation = _state.value.installation
        val baseline = circuit?.baseline
        if (installation == null || baseline == null || installation.isBenchRig) {
            update { it.copy(thresholds = Thresholds.DEFAULT) }
            return
        }
        update { it.copy(thresholds = store.calibrate(installation.id, circuit.id, baseline)) }
    }

    private companion object {
        /** Below this a centroid is one or two points and means nothing. */
        const val MIN_TRAINING_SAMPLES = 6

        /** How far back the fusion layer looks for a repeated deviation. */
        const val EARLIER_READINGS = 5

        /** Time to slide the phone to the next point between captures. */
        const val SCAN_MOVE_MILLIS = 1_500L

        /** A scan stops itself here, so a forgotten one does not run forever. */
        const val MAX_SCAN_POINTS = 30

        /** Per appliance state. Three gives a median that one bad capture cannot move. */
        const val CALIBRATION_CAPTURES = 3

        const val KEY_BOARD = "board"
        const val KEY_CIRCUIT = "circuit"
        const val KEY_PHONE_CHECK = "phone_check"
        const val KEY_GEIGER = "geiger_enabled"
        const val KEY_RATE = "tariff_rate"
        /** Followed by the circuit id: hours are per circuit, since a geyser and a fridge differ. */
        const val KEY_HOURS = "hours:"
        const val KEY_BOARD_MAP = "board_map_enabled"

        /**
         * Wait after the tap before recording. The recording used to start the
         * instant Measure was tapped, so the tap's own jolt was inside it and could
         * mark a good reading as moved.
         */
        const val SETTLE_MILLIS = 1000L
    }
}
