package edu.iot.phoneangle.ml.typed

import android.content.Context
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.store.LocalDatasetStore
import kotlin.math.abs

class TypedWorkflow(context: Context) {
    val dataset = LocalDatasetStore(context)
    /** Snipped-word model (class project): one whole trial per word, google/yahoo/other. */
    val models = TypedModelStore(context)
    /** Live-listen model: sliding windows + idle class. */
    val liveModels = TypedModelStore(context, TypedModelStore.LIVE_FILE_NAME)
    private val preprocessor = TypedPreprocessor()

    data class Status(
        val total: Int,
        val perClass: Map<TypedLabel, Int>,
    ) {
        fun count(label: TypedLabel): Int = perClass[label] ?: 0
        val idleCount: Int get() = count(TypedLabel.IDLE)
        val ready: Boolean
            get() = TypedLabel.SNIPPED.all { count(it) >= MIN_PER_CLASS }
    }

    data class LiveOutcome(
        val bundle: TypedModelBundle,
        val trainWindows: Int,
        val testWindows: Int,
        val report: TypedEvalReport?,
        val replay: TypedReplayEval.Report? = null,
    )

    data class TrainOutcome(
        val bundle: TypedModelBundle,
        val trainCount: Int,
        val testCount: Int,
        val report: TypedEvalReport?,
    )

    /** Label counts only — reads trial headers, not sample payloads. */
    fun status(): Status {
        val raw = dataset.countByLabel(TrialTask.TYPED_WORD)
        val per = TypedLabel.LIVE.associateWith { label ->
            raw.entries.sumOf { (k, n) -> if (TypedLabel.fromCollectLabel(k) == label) n else 0 }
        }
        return Status(TypedLabel.SNIPPED.sumOf { per[it] ?: 0 }, per)
    }

    /** Burst-aligned (+ whole-trial) examples for the snipped model; idle excluded. */
    fun channelExamples(): List<TypedChannelExample> =
        preprocessor.channelExamplesFromTrials(dataset.loadAll(TrialTask.TYPED_WORD))
            .filter { it.label != TypedLabel.IDLE }

    /**
     * Live / flag model: progressive burst examples + idle windows.
     * Entire recordings stay in train or test (split by trial id only).
     * Biases, scaler, and weights fit on train exclusively.
     */
    fun trainLive(save: Boolean = true): LiveOutcome {
        val windowing = TypedWindowing(preprocessor)
        val trials = dataset.loadAll(TrialTask.TYPED_WORD) + dataset.loadAll(TrialTask.TYPED_FEED)
        val base = windowing.windowsFromTrials(trials)
        val rng = kotlin.random.Random(8223)
        val (trainBase, testRaw) = splitBySession(base)
        val trainRaw = trainBase.flatMap { ex ->
            if (ex.label == TypedLabel.IDLE) listOf(ex) else TypedAugment.expand(ex, rng)
        }
        val missing = TypedLabel.LIVE.filter { label -> trainRaw.none { it.label == label } }
        check(missing.isEmpty()) {
            "Live model needs examples for ${missing.joinToString { it.id }}" +
                if (TypedLabel.IDLE in missing) " — record Idle under Extra credit." else "."
        }
        val fitSet = if (trainRaw.size <= 400) trainRaw else trainRaw.shuffled(rng).take(400)
        val biases = preprocessor.fitBiases(fitSet.map { it.channels })
        val trainPpv = trainRaw.map {
            TypedExample(preprocessor.featuresFromChannels(it.channels, biases), it.label, it.trialId)
        }
        val scaler = FeatureScaler()
        val trainFeats = scaler.fitTransform(trainPpv.map { it.features })
        val train = trainPpv.zip(trainFeats) { ex, f -> ex.copy(features = f) }
        val test = testRaw.map {
            TypedExample(
                scaler.transform(preprocessor.featuresFromChannels(it.channels, biases)),
                it.label,
                it.trialId,
            )
        }
        val result = TypedTrainer(SNIPPED_TRAIN_CONFIG).train(train, classes = TypedLabel.LIVE)
        val report = if (test.isNotEmpty()) evaluate(result.model, test) else null
        val classCounts = TypedLabel.LIVE.associateWith { label -> train.count { it.label == label } }
        val bundle = TypedModelBundle(
            model = result.model,
            scaler = scaler,
            rocketBiases = biases,
            trainedAtEpochMs = System.currentTimeMillis(),
            trainAccuracy = result.trainAccuracy,
            trainSampleCount = result.sampleCount,
            testAccuracy = report?.accuracy,
            epochs = result.epochsRun,
            classCounts = classCounts,
            windowMs = windowing.windowMs,
        )
        if (save) liveModels.save(bundle)
        val testSessions = trials.filter { abs(it.id.hashCode()) % 100 < 25 }
        val replay = if (testSessions.isNotEmpty()) {
            runCatching { TypedReplayEval.replay(bundle, testSessions) }.getOrNull()
        } else {
            null
        }
        return LiveOutcome(bundle, train.size, test.size, report, replay)
    }

    /** Whole-session assignment — never split overlapping windows from one recording. */
    fun splitBySession(examples: List<TypedChannelExample>): Pair<List<TypedChannelExample>, List<TypedChannelExample>> {
        val sessionIsTest = HashMap<String, Boolean>()
        val train = mutableListOf<TypedChannelExample>()
        val test = mutableListOf<TypedChannelExample>()
        for (ex in examples) {
            val key = ex.trialId.substringBefore('#')
            val isTest = sessionIsTest.getOrPut(key) { abs(key.hashCode()) % 100 < 25 }
            if (isTest) test += ex else train += ex
        }
        return train to test
    }

    fun trainBaseline(save: Boolean = true): TrainOutcome {
        val all = channelExamples()
        // Session-level split, then augment train only (time-warp + hesitation).
        val (trainBase, testRaw) = splitBySession(all)
        check(TypedLabel.SNIPPED.all { label -> trainBase.count { it.label == label } >= 1 }) {
            "Need at least one train trial for google, yahoo, and other (have ${status().perClass})."
        }
        val uniqueTrials = trainBase.map { it.trialId }.toSet().size
        check(uniqueTrials >= MIN_TOTAL || trainBase.size >= MIN_TOTAL) {
            "Need at least $MIN_TOTAL typed trials to train (have ~$uniqueTrials sessions / ${trainBase.size} examples)."
        }
        val rng = kotlin.random.Random(8223)
        val trainRaw = trainBase.flatMap { TypedAugment.expand(it, rng) }
        val fitSet = if (trainRaw.size <= 400) trainRaw else trainRaw.shuffled(rng).take(400)
        val biases = preprocessor.fitBiases(fitSet.map { it.channels })
        val trainPpv = trainRaw.map {
            TypedExample(preprocessor.featuresFromChannels(it.channels, biases), it.label, it.trialId)
        }
        val scaler = FeatureScaler()
        val trainFeats = scaler.fitTransform(trainPpv.map { it.features })
        val train = trainPpv.zip(trainFeats) { ex, f -> ex.copy(features = f) }
        val test = if (testRaw.isEmpty()) emptyList() else {
            testRaw.map {
                val ppv = preprocessor.featuresFromChannels(it.channels, biases)
                TypedExample(scaler.transform(ppv), it.label, it.trialId)
            }
        }
        val result = TypedTrainer(SNIPPED_TRAIN_CONFIG).train(train)
        val report = if (test.isNotEmpty()) evaluate(result.model, test) else null
        val classCounts = TypedLabel.SNIPPED.associateWith { label -> trainBase.count { it.label == label } }
        val bundle = TypedModelBundle(
            model = result.model,
            scaler = scaler,
            rocketBiases = biases,
            trainedAtEpochMs = System.currentTimeMillis(),
            trainAccuracy = result.trainAccuracy,
            trainSampleCount = trainBase.size,
            testAccuracy = report?.accuracy,
            epochs = result.epochsRun,
            classCounts = classCounts,
        )
        if (save) models.save(bundle)
        return TrainOutcome(bundle, train.size, test.size, report)
    }

    fun evaluateHoldout(bundle: TypedModelBundle = models.load() ?: error("No typed model")): TypedEvalReport? {
        // Same session split as training; never evaluate on augmented copies.
        val (_, testRaw) = splitBySession(channelExamples())
        if (testRaw.isEmpty()) return null
        check(bundle.scaler.fitted) { "Typed model missing scaler — retrain" }
        val test = testRaw.map {
            val ppv = preprocessor.featuresFromChannels(it.channels, bundle.rocketBiases)
            TypedExample(bundle.scaler.transform(ppv), it.label, it.trialId)
        }
        return evaluate(bundle.model, test)
    }

    fun evaluate(model: TypedSoftmax, examples: List<TypedExample>): TypedEvalReport {
        val classes = model.classes
        val confusion = Array(classes.size) { IntArray(classes.size) }
        var correct = 0
        for (ex in examples) {
            val pred = model.predict(ex.features).label
            val ti = classes.indexOf(ex.label)
            val pi = classes.indexOf(pred)
            if (ti >= 0 && pi >= 0) {
                confusion[ti][pi]++
                if (ti == pi) correct++
            }
        }
        val n = examples.size.coerceAtLeast(1)
        val perClass = classes.mapIndexed { i, label ->
            val support = confusion[i].sum()
            val tp = confusion[i][i]
            val fp = classes.indices.sumOf { r -> if (r == i) 0 else confusion[r][i] }
            val fn = support - tp
            val precision = if (tp + fp == 0) 0f else tp.toFloat() / (tp + fp)
            val recall = if (tp + fn == 0) 0f else tp.toFloat() / (tp + fn)
            val f1 = if (precision + recall == 0f) 0f else 2f * precision * recall / (precision + recall)
            TypedClassMetrics(label, precision, recall, f1, support, fp, fn)
        }
        return TypedEvalReport(
            sampleCount = examples.size,
            accuracy = correct.toFloat() / n,
            macroF1 = perClass.map { it.f1 }.average().toFloat(),
            perClass = perClass,
            confusion = confusion,
            classes = classes,
        )
    }

    fun splitChannels(examples: List<TypedChannelExample>): Pair<List<TypedChannelExample>, List<TypedChannelExample>> {
        val train = mutableListOf<TypedChannelExample>()
        val test = mutableListOf<TypedChannelExample>()
        for (ex in examples) {
            val h = abs(ex.trialId.hashCode()) % 100
            if (h < 25) test += ex else train += ex
        }
        return train to test
    }

    companion object {
        const val MIN_PER_CLASS = 8
        const val MIN_TOTAL = 18
        /** Longer, slightly gentler SGD for the small MiniRocket Softmax head. */
        val SNIPPED_TRAIN_CONFIG = edu.iot.phoneangle.ml.TrainConfig(
            epochs = 200,
            learningRate = 0.08f,
            l2 = 5e-4f,
            seed = 8223L,
        )
    }
}

data class TypedClassMetrics(
    val label: TypedLabel,
    val precision: Float,
    val recall: Float,
    val f1: Float,
    val support: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
)

data class TypedEvalReport(
    val sampleCount: Int,
    val accuracy: Float,
    val macroF1: Float,
    val perClass: List<TypedClassMetrics>,
    val confusion: Array<IntArray>,
    val classes: List<TypedLabel>,
)
