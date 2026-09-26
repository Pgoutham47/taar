package com.taar.ml

import android.content.Context
import com.taar.dsp.ArcFeatures
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The on-device arc model: a 16.6 KB TFLite network over [ArcFeatures].
 *
 * Advisory only. It is a second opinion beside the rule in ArcDetector, which it
 * was measured against: on a recording session it never trained on, it cut false
 * alarms from 10% to under 1% while missing about 5% of arcs. The rule still decides.
 *
 * Trained by spike/audio/train_arc.py. The arc class is synthetic, played through
 * a speaker and recorded on the iQOO; see spike/audio/README.md.
 */
class ArcModel private constructor(private val interpreter: Interpreter) : Closeable {

    data class SelfCheck(val passed: Boolean, val rows: Int, val worstDifference: Double)

    /** Outcome of running the model on the training machine's golden rows at load. */
    var selfCheck = SelfCheck(false, 0, Double.NaN)
        private set

    private val input = Array(1) { FloatArray(ArcFeatures.COUNT) }
    private val output = Array(1) { FloatArray(1) }

    /** Probability the capture is arc-like, in [0, 1]. */
    @Synchronized
    fun probability(features: FloatArray): Float {
        require(features.size == ArcFeatures.COUNT) { "expected ${ArcFeatures.COUNT} features" }
        features.copyInto(input[0])
        interpreter.run(input, output)
        return output[0][0]
    }

    /** Null when the capture cannot be scored, e.g. at a sample rate the model never saw. */
    fun probability(audio: DoubleArray, sampleRateHz: Double): Float? =
        ArcFeatures.of(audio, sampleRateHz)?.let(::probability)

    @Synchronized
    override fun close() = interpreter.close()

    companion object {
        const val THRESHOLD = 0.5f

        private const val MODEL_ASSET = "taar_arc.tflite"
        private const val SELF_CHECK_ASSET = "arc_selfcheck.csv"
        private const val SELF_CHECK_TOLERANCE = 1e-4

        /** Null if the model cannot be loaded; the app then runs on the rule alone. */
        fun load(context: Context): ArcModel? = runCatching {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buffer.put(bytes).rewind()
            val interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(1))
            ArcModel(interpreter).also { it.selfCheck = it.runSelfCheck(context) }
        }.getOrNull()
    }

    /**
     * Runs the rows the training script scored and compares outputs. Proves on the
     * device itself that this runtime reproduces the model as trained, which a JVM
     * test cannot: the interpreter is native code.
     */
    private fun runSelfCheck(context: Context): SelfCheck {
        val lines = context.assets.open(SELF_CHECK_ASSET).bufferedReader().readLines()
            .drop(1).filter { it.isNotBlank() }
        var worst = 0.0
        for (line in lines) {
            val cells = line.split(",")
            val expected = cells[2].toDouble()
            val features = FloatArray(ArcFeatures.COUNT) { cells[3 + it].toFloat() }
            worst = maxOf(worst, abs(probability(features) - expected))
        }
        return SelfCheck(lines.isNotEmpty() && worst <= SELF_CHECK_TOLERANCE, lines.size, worst)
    }
}
