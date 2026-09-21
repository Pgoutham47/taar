package com.taar.domain

import kotlin.math.sqrt

/**
 * On-device classification of a reading, trained from the technician's own labels.
 *
 * A nearest-centroid matcher rather than a neural network, for the same reason the
 * catalogue is a table: adding a class must cost nothing. A trained head's output
 * layer is fixed to the classes it was trained on, so a new fault means collecting
 * data, retraining and shipping a model — which is incompatible with a technician
 * labelling a reading on Tuesday and having it recognised on Wednesday.
 *
 * It also rejects. An unfamiliar reading returns null rather than the nearest of
 * several wrong answers, which for a tool that recommends opening a panel is the
 * behaviour that matters.
 */
interface Classifier {
    fun classify(features: DoubleArray): Prediction?
    fun train(samples: List<LabelledSample>)
    val classCount: Int
}

data class LabelledSample(val label: String, val features: DoubleArray) {
    override fun equals(other: Any?): Boolean =
        other is LabelledSample && label == other.label && features.contentEquals(other.features)

    override fun hashCode(): Int = 31 * label.hashCode() + features.contentHashCode()
}

data class Prediction(
    val label: String,
    /** Distance to the winning centroid, in normalised feature units. */
    val distance: Double,
    /** How much further the runner-up was; low means the classes overlap here. */
    val margin: Double,
)

/**
 * Features a reading contributes to classification.
 *
 * Deliberately few and all scale-free. Raw microtesla would make a model learned on
 * a lighting circuit useless on a motor feed; everything here is relative to the
 * circuit's own baseline.
 */
object Features {
    const val SIZE = 4

    fun of(metrics: Metrics): DoubleArray = doubleArrayOf(
        metrics.loadZ,
        metrics.arcZ,
        metrics.lineConfidence,
        metrics.loadVsRating ?: 0.0,
    )
}

class CentroidClassifier(
    /** Beyond this distance a reading is unfamiliar and gets no label. */
    private val rejectDistance: Double = DEFAULT_REJECT,
    /** Below this margin the classes overlap and the answer is not trustworthy. */
    private val minMargin: Double = DEFAULT_MIN_MARGIN,
) : Classifier {

    private val centroids = linkedMapOf<String, DoubleArray>()
    private var scale = DoubleArray(Features.SIZE) { 1.0 }

    override val classCount: Int get() = centroids.size

    override fun train(samples: List<LabelledSample>) {
        centroids.clear()
        if (samples.isEmpty()) return

        val size = samples.first().features.size
        require(samples.all { it.features.size == size }) { "feature lengths differ" }

        // Scale each dimension by its spread across all samples, so one
        // wide-ranging feature does not dominate the distance.
        scale = DoubleArray(size) { d ->
            val column = DoubleArray(samples.size) { samples[it].features[d] }
            Stats.mad(column).takeIf { it > 1e-9 } ?: 1.0
        }

        for ((label, group) in samples.groupBy { it.label }) {
            centroids[label] = DoubleArray(size) { d ->
                Stats.median(DoubleArray(group.size) { group[it].features[d] })
            }
        }
    }

    override fun classify(features: DoubleArray): Prediction? {
        if (centroids.isEmpty()) return null

        val ranked = centroids
            .map { (label, centroid) -> label to distance(features, centroid) }
            .sortedBy { it.second }

        val (label, best) = ranked.first()
        if (best > rejectDistance) return null

        val margin = if (ranked.size > 1) ranked[1].second - best else Double.MAX_VALUE
        // With a single class there is nothing to be confused with, so margin does
        // not apply; with several, an ambiguous answer is worse than none.
        if (ranked.size > 1 && margin < minMargin) return null

        return Prediction(label, best, margin)
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        var acc = 0.0
        for (i in a.indices) {
            val d = (a[i] - b[i]) / scale[i]
            acc += d * d
        }
        return sqrt(acc / a.size)
    }

    companion object {
        const val DEFAULT_REJECT = 3.0
        const val DEFAULT_MIN_MARGIN = 0.4
    }
}
