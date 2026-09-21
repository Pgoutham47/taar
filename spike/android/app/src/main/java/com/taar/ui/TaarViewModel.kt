package com.taar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    data class UiState(
        val installation: Installation? = null,
        val selectedCircuitId: String? = null,
        val busy: Boolean = false,
        val lastReading: Reading? = null,
        val lastStatus: Status = Status.UNKNOWN,
        val lastFaults: List<RankedFault> = emptyList(),
        val thresholds: Thresholds = Thresholds.DEFAULT,
        val baselineProgress: Int = 0,
        val message: String? = null,
        val installations: List<Installation> = emptyList(),
        val history: List<Store.LabelledReading> = emptyList(),
        val spectrogram: com.taar.dsp.Spectrogram.Result? = null,
        val prediction: Prediction? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val selectedCircuit: Circuit?
        get() = _state.value.let { s ->
            s.installation?.circuits?.firstOrNull { it.id == s.selectedCircuitId }
        }

    /**
     * Creates a starter board on first launch and selects its first circuit.
     *
     * Without this the app opens with nothing selected and every action is a no-op,
     * which on a demo floor reads as a broken build rather than an empty one.
     */
    private val classifier: Classifier = CentroidClassifier()

    fun refreshInstallations() {
        _state.value = _state.value.copy(
            installations = store.listInstallationIds().mapNotNull { store.loadInstallation(it) },
        )
    }

    fun createInstallation(name: String) {
        val inst = Installation(id = "board-${System.currentTimeMillis()}", name = name)
        store.saveInstallation(inst)
        refreshInstallations()
    }

    fun saveInstallation(updated: Installation) {
        store.saveInstallation(updated)
        _state.value = _state.value.copy(installation = updated, message = "Saved.")
        refreshInstallations()
    }

    fun loadHistory() {
        val id = _state.value.installation?.id ?: return
        _state.value = _state.value.copy(history = store.loadReadings(id))
    }

    fun bootstrapIfEmpty() {
        val existing = store.listInstallationIds().firstOrNull()
        if (existing != null) {
            openInstallation(existing)
        } else {
            val starter = Installation(
                id = "board-1",
                name = "Board 1",
                circuits = listOf(
                    Circuit("c1", "Circuit 1", breakerRatingA = 6.0),
                    Circuit("c2", "Circuit 2", breakerRatingA = 16.0),
                    Circuit("c3", "Circuit 3", breakerRatingA = 16.0),
                ),
            )
            store.saveInstallation(starter)
            _state.value = _state.value.copy(installation = starter)
        }
        _state.value.installation?.circuits?.firstOrNull()?.let { selectCircuit(it.id) }
    }

    fun openInstallation(id: String) {
        _state.value = _state.value.copy(installation = store.loadInstallation(id))
    }

    fun selectCircuit(circuitId: String) {
        _state.value = _state.value.copy(selectedCircuitId = circuitId, lastFaults = emptyList())
        refreshThresholds()
    }

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

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, baselineProgress = 0, message = null)

            val fields = mutableListOf<Double>()
            val arcs = mutableListOf<Double>()
            val lines = mutableListOf<Double>()

            repeat(Baseline.MIN_SAMPLES) { i ->
                val r = coordinator.capture()
                if (r != null) {
                    fields += r.fieldAmplitudeUt
                    arcs += r.arcModulationIndex
                    lines += r.lineConfidence
                }
                _state.value = _state.value.copy(baselineProgress = i + 1)
            }

            if (fields.size < Baseline.MIN_SAMPLES) {
                _state.value = _state.value.copy(
                    busy = false,
                    message = "Only ${fields.size} of ${Baseline.MIN_SAMPLES} captures succeeded. " +
                        "Check the pre-check screen.",
                )
                return@launch
            }

            val baseline = Baseline(
                System.currentTimeMillis(),
                fields.toDoubleArray(), arcs.toDoubleArray(), lines.toDoubleArray(),
            )
            val updated = installation.copy(
                circuits = installation.circuits.map {
                    if (it.id == circuit.id) it.copy(baseline = baseline) else it
                },
            )
            store.saveInstallation(updated)
            _state.value = _state.value.copy(
                installation = updated, busy = false,
                message = "Reference recorded for ${circuit.label}.",
            )
            refreshThresholds()
        }
    }

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

        if (circuit.baseline?.isSufficient != true) {
            _state.value = _state.value.copy(
                message = "Record a reference for this circuit first.",
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)

            val result = coordinator.capture()
            if (result == null) {
                _state.value = _state.value.copy(busy = false, message = "Capture failed.")
                return@launch
            }

            val reading = Reading(
                circuitId = circuit.id,
                epochMillis = System.currentTimeMillis(),
                fieldAmplitudeUt = result.fieldAmplitudeUt,
                lineConfidence = result.lineConfidence,
                arcModulationIndex = result.arcModulationIndex,
                fieldEstimateUsable = result.fieldEstimateUsable,
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

            _state.value = _state.value.copy(
                busy = false,
                lastReading = reading,
                lastFaults = ranked,
                lastStatus = RulesEngine.status(ranked),
                spectrogram = result.spectrogram,
                prediction = prediction,
            )
        }
    }

    /** Confirming a label is what moves the thresholds; nothing else does. */
    fun label(status: Status) {
        val reading = _state.value.lastReading ?: return
        val installation = _state.value.installation ?: return
        if (installation.isBenchRig) {
            _state.value = _state.value.copy(
                message = "Bench rig — label recorded but excluded from calibration.",
            )
        }
        store.appendReading(installation.id, reading, status)
        refreshThresholds()
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
        val circuit = selectedCircuit ?: return
        val installation = _state.value.installation ?: return
        val baseline = circuit.baseline ?: return
        if (installation.isBenchRig) return
        _state.value = _state.value.copy(
            thresholds = store.calibrate(installation.id, circuit.id, baseline),
        )
    }

    private companion object {
        /** Below this a centroid is one or two points and means nothing. */
        const val MIN_TRAINING_SAMPLES = 6
    }
}
