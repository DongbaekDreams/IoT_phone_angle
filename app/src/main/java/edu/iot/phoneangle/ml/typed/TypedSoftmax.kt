package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.ml.SoftmaxClassifier
import edu.iot.phoneangle.ml.TrainConfig
import kotlin.random.Random

/** Softmax over [TypedLabel] — separate from pose Softmax / FedAvg payloads. */
class TypedSoftmax(
    val featureDim: Int = FEATURE_DIM,
    val classes: List<TypedLabel> = TypedLabel.SNIPPED,
    weights: Array<FloatArray>? = null,
    bias: FloatArray? = null,
) {
    val numClasses: Int = classes.size
    val weights: Array<FloatArray> = weights?.map { it.copyOf() }?.toTypedArray()
        ?: Array(numClasses) { FloatArray(featureDim) }
    val bias: FloatArray = bias?.copyOf() ?: FloatArray(numClasses)

    fun predictProba(features: FloatArray, logitAdjust: FloatArray? = null): FloatArray {
        require(features.size == featureDim)
        val logits = FloatArray(numClasses)
        for (c in 0 until numClasses) {
            var sum = bias[c]
            val w = weights[c]
            for (i in 0 until featureDim) sum += w[i] * features[i]
            if (logitAdjust != null && c < logitAdjust.size) sum += logitAdjust[c]
            logits[c] = sum
        }
        return SoftmaxClassifier.softmax(logits)
    }

    fun predict(features: FloatArray, logitAdjust: FloatArray? = null): TypedPrediction {
        val proba = predictProba(features, logitAdjust)
        var best = 0
        for (i in 1 until proba.size) if (proba[i] > proba[best]) best = i
        return TypedPrediction(
            label = classes[best],
            confidence = proba[best],
            probabilities = classes.mapIndexed { i, label -> label to proba[i] }.toMap(),
        )
    }

    fun copy(): TypedSoftmax = TypedSoftmax(featureDim, classes, weights, bias)

    companion object {
        val FEATURE_DIM: Int get() = TypedPreprocessor.FEATURE_DIM
    }
}

data class TypedPrediction(
    val label: TypedLabel,
    val confidence: Float,
    val probabilities: Map<TypedLabel, Float>,
)

data class TypedTrainResult(
    val model: TypedSoftmax,
    val trainAccuracy: Float,
    val epochsRun: Int,
    val sampleCount: Int,
)

class TypedTrainer(private val config: TrainConfig = TrainConfig(epochs = 120, learningRate = 0.10f)) {
    fun train(
        examples: List<TypedExample>,
        base: TypedSoftmax? = null,
        classes: List<TypedLabel> = TypedLabel.SNIPPED,
        maxPerClass: Int = 600,
    ): TypedTrainResult {
        require(examples.isNotEmpty()) { "Need labeled typed examples to train" }
        val model = base?.copy() ?: TypedSoftmax(classes = classes)
        val classIndex = model.classes.withIndex().associate { it.value to it.index }
        val rng = Random(config.seed)
        // Resample to equal class counts so no class (google, idle…) dominates the head.
        val byClass = examples.groupBy { it.label }
        val target = byClass.values.maxOf { it.size }.coerceIn(1, maxPerClass)
        val balanced = buildList {
            for (label in model.classes) {
                val pool = byClass[label].orEmpty()
                if (pool.isEmpty()) continue
                repeat(target) { add(pool[rng.nextInt(pool.size)]) }
            }
        }
        val order = balanced.indices.toMutableList()

        repeat(config.epochs) {
            order.shuffle(rng)
            for (idx in order) {
                val ex = balanced[idx]
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

        val correct = examples.count { model.predict(it.features).label == it.label }
        return TypedTrainResult(
            model = model,
            trainAccuracy = correct.toFloat() / examples.size,
            epochsRun = config.epochs,
            sampleCount = examples.size,
        )
    }
}
