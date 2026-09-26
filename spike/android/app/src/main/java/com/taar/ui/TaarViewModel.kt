package com.taar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taar.domain.AmpCalibration
import com.taar.domain.Baseline
import com.taar.domain.Classifier
import com.taar.domain.CentroidClassifier
import com.taar.domain.Features
import com.taar.domain.LabelledSample
import com.taar.domain.Prediction
import com.taar.domain.Circuit
import com.taar.domain.Installation
import com.taar.domain.Metrics
import com.taar.domain.RankedFault
import com.taar.domain.Reading
import com.taar.domain.RulesEngine
import com.taar.domain.Status
import com.taar.domain.Store
import com.taar.domain.Thresholds
import com.taar.sensor.CaptureCoordinator
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
) : ViewModel() {

    /** What a running capture is for, so the screen can say so. */
    enum class CaptureKind { REFERENCE, MEASURE, CALIBRATE_OFF, CALIBRATE_ON }

    /** A capture in progress: capture [step] of [total]. */
    data class CaptureProgress(val kind: CaptureKind, val step: Int, val total: Int)

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
    ) {
        val busy: Boolean get() = capture != null
    }

    private val _state = MutableStateFlow(UiState())
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
            )
        }
        refreshThresholds()
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
            update { it.copy(installation = updated, capture = null, referenceJustRecorded = true) }
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

        viewModelScope.launch {
            // Cleared before capturing, so the screen can never show the previous
            // reading while the new one is still being taken.
            update {
                it.copy(
                    lastReading = null, lastFaults = emptyList(), lastLabel = null,
                    lastImpliedCurrentA = null, spectrogram = null, prediction = null, error = null,
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

            update {
                it.copy(
                    capture = null,
                    lastReading = reading,
                    lastFaults = ranked,
                    lastStatus = RulesEngine.status(ranked),
                    lastImpliedCurrentA = metrics?.impliedCurrentA,
                    spectrogram = result.spectrogram,
                    prediction = prediction,
                )
            }
        }
    }

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

        /** Per appliance state. Three gives a median that one bad capture cannot move. */
        const val CALIBRATION_CAPTURES = 3

        const val KEY_BOARD = "board"
        const val KEY_CIRCUIT = "circuit"
        const val KEY_PHONE_CHECK = "phone_check"
    }
}
