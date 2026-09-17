package edu.iot.phoneangle.ml

import edu.iot.phoneangle.data.PhonePose
import kotlin.math.exp
import kotlin.random.Random

/**
 * Multiclass logistic regression (softmax). Lightweight, FedAvg-friendly weights.
 */
class SoftmaxClassifier(
    val featureDim: Int = Preprocessor.FEATURE_DIM,
    val classes: List<PhonePose> = PhonePose.entries.toList(),
    weights: Array<FloatArray>? = null,
    bias: FloatArray? = null,
) {
    val numClasses: Int = classes.size

    /** [class][feature] */
    val weights: Array<FloatArray> = weights?.map { it.copyOf() }?.toTypedArray()
        ?: Array(numClasses) { FloatArray(featureDim) }

    val bias: FloatArray = bias?.copyOf() ?: FloatArray(numClasses)

    fun predictProba(features: FloatArray): FloatArray {
        require(features.size == featureDim)
        val logits = FloatArray(numClasses)
        for (c in 0 until numClasses) {
            var sum = bias[c]
            val w = weights[c]
            for (i in 0 until featureDim) sum += w[i] * features[i]
            logits[c] = sum
        }
        return softmax(logits)
    }

    fun predict(features: FloatArray): PosePrediction {
        val proba = predictProba(features)
        var best = 0
        for (i in 1 until proba.size) if (proba[i] > proba[best]) best = i
        return PosePrediction(
            pose = classes[best],
            confidence = proba[best],
            probabilities = classes.mapIndexed { i, pose -> pose to proba[i] }.toMap(),
        )
    }

    fun copy(): SoftmaxClassifier =
        SoftmaxClassifier(featureDim, classes, weights, bias)

    companion object {
        fun softmax(logits: FloatArray): FloatArray {
            var max = logits[0]
            for (i in 1 until logits.size) if (logits[i] > max) max = logits[i]
            var sum = 0.0
            val exps = DoubleArray(logits.size)
            for (i in logits.indices) {
                exps[i] = exp((logits[i] - max).toDouble())
                sum += exps[i]
            }
            return FloatArray(logits.size) { (exps[it] / sum).toFloat() }
        }
    }
}

data class PosePrediction(
    val pose: PhonePose,
    val confidence: Float,
    val probabilities: Map<PhonePose, Float>,
)

data class TrainConfig(
    val epochs: Int = 80,
    val learningRate: Float = 0.15f,
    val l2: Float = 1e-3f,
    val seed: Long = 42L,
)

data class TrainResult(
    val model: SoftmaxClassifier,
    val trainAccuracy: Float,
    val epochsRun: Int,
    val sampleCount: Int,
)

class LocalTrainer(
    private val config: TrainConfig = TrainConfig(),
) {
    fun train(examples: List<LabeledExample>, base: SoftmaxClassifier? = null): TrainResult {
        require(examples.isNotEmpty()) { "Need labeled examples to train" }
        val model = base?.copy() ?: SoftmaxClassifier()
        val classIndex = model.classes.withIndex().associate { it.value to it.index }
        val rng = Random(config.seed)
        val order = examples.indices.toMutableList()

        repeat(config.epochs) {
            order.shuffle(rng)
            for (idx in order) {
                val ex = examples[idx]
                val y = classIndex.getValue(ex.label)
                val proba = model.predictProba(ex.features)
                for (c in 0 until model.numClasses) {
                    val err = proba[c] - if (c == y) 1f else 0f
                    model.bias[c] -= config.learningRate * (err + config.l2 * model.bias[c])
                    val w = model.weights[c]
                    for (f in 0 until model.featureDim) {
                        w[f] -= config.learningRate * (err * ex.features[f] + config.l2 * w[f])
                    }
                }
            }
        }

        val correct = examples.count { model.predict(it.features).pose == it.label }
        return TrainResult(
            model = model,
            trainAccuracy = correct.toFloat() / examples.size,
            epochsRun = config.epochs,
            sampleCount = examples.size,
        )
    }
}
