package edu.iot.phoneangle.ml

import edu.iot.phoneangle.data.PhonePose
import edu.iot.phoneangle.data.SensorSample

/**
 * Live predictions from a rolling sample buffer + saved model.
 */
class InferenceEngine(
    private var bundle: PoseModelBundle?,
) {
    private val buffer = ArrayDeque<SensorSample>()
    private val maxBuffer = 80

    val hasModel: Boolean get() = bundle != null

    fun setModel(bundle: PoseModelBundle?) {
        this.bundle = bundle
    }

    fun clearBuffer() = buffer.clear()

    fun offer(sample: SensorSample) {
        buffer.addLast(sample)
        while (buffer.size > maxBuffer) buffer.removeFirst()
    }

    fun predict(): PosePrediction? {
        val current = bundle ?: return null
        if (buffer.size < 10) return null
        val preprocessor = Preprocessor(current.sensorConfig)
        val features = preprocessor.featuresFromSamples(buffer.toList()) ?: return null
        return current.model.predict(features)
    }
}

/**
 * Metrics for held-out evaluation.
 */
class Evaluator {
    fun evaluate(model: SoftmaxClassifier, examples: List<LabeledExample>): EvaluationReport {
        val classes = model.classes
        val confusion = Array(classes.size) { IntArray(classes.size) }
        var correct = 0
        for (ex in examples) {
            val pred = model.predict(ex.features).pose
            val ti = classes.indexOf(ex.label)
            val pi = classes.indexOf(pred)
            if (ti >= 0 && pi >= 0) {
                confusion[ti][pi]++
                if (ti == pi) correct++
            }
        }
        val n = examples.size.coerceAtLeast(1)
        val perClass = classes.mapIndexed { i, pose ->
            val support = confusion[i].sum()
            val tp = confusion[i][i]
            val acc = if (support == 0) 0f else tp.toFloat() / support
            val fp = classes.indices.sumOf { r -> if (r == i) 0 else confusion[r][i] }
            val fn = support - tp
            val precision = if (tp + fp == 0) 0f else tp.toFloat() / (tp + fp)
            val recall = if (tp + fn == 0) 0f else tp.toFloat() / (tp + fn)
            val f1 = if (precision + recall == 0f) 0f else 2f * precision * recall / (precision + recall)
            ClassMetrics(pose, accuracy = acc, precision = precision, recall = recall, f1 = f1, support = support)
        }
        val macroF1 = perClass.map { it.f1 }.average().toFloat()
        return EvaluationReport(
            sampleCount = examples.size,
            accuracy = correct.toFloat() / n,
            macroF1 = macroF1,
            perClass = perClass,
            confusion = confusion,
            classes = classes,
        )
    }
}

data class ClassMetrics(
    val pose: PhonePose,
    val accuracy: Float,
    val precision: Float,
    val recall: Float,
    val f1: Float,
    val support: Int,
)

data class EvaluationReport(
    val sampleCount: Int,
    val accuracy: Float,
    val macroF1: Float,
    val perClass: List<ClassMetrics>,
    val confusion: Array<IntArray>,
    val classes: List<PhonePose>,
) {
    fun formatConfusion(): String {
        val header = classes.joinToString("\t") { it.shortId.take(6) }
        val rows = confusion.mapIndexed { i, row ->
            "${classes[i].shortId.take(6)}\t" + row.joinToString("\t")
        }
        return "true\\pred\t$header\n" + rows.joinToString("\n")
    }
}

/** Stratified train/test split by pose label. */
fun splitTrainTest(
    examples: List<LabeledExample>,
    testFraction: Float = 0.25f,
    seed: Long = 42L,
): Pair<List<LabeledExample>, List<LabeledExample>> {
    val rng = kotlin.random.Random(seed)
    val train = mutableListOf<LabeledExample>()
    val test = mutableListOf<LabeledExample>()
    examples.groupBy { it.label }.forEach { (_, group) ->
        val shuffled = group.shuffled(rng)
        val testCount = (shuffled.size * testFraction).toInt().coerceAtLeast(
            if (shuffled.size >= 2) 1 else 0,
        )
        test += shuffled.take(testCount)
        train += shuffled.drop(testCount)
    }
    return train to test
}
