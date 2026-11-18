package edu.iot.phoneangle.ml

import android.content.Context
import edu.iot.phoneangle.ProjectSettings
import edu.iot.phoneangle.data.ClientRole
import edu.iot.phoneangle.data.PhonePose
import edu.iot.phoneangle.data.Trial
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.store.LocalDatasetStore

/**
 * One place for "collect -> train -> evaluate" so every screen uses identical logic.
 *
 * - Baseline: from scratch, local data only, saved to [ModelSlot.LOCAL_BASELINE].
 * - Round-local: warm-started from the global model, saved to [ModelSlot.ROUND_LOCAL].
 * - Evaluation always uses this phone's stable held-out trials ([StableSplit]).
 *
 * Non-IID rule: a phone trains only on its [ProjectSettings.trainingPoses] — within a nationality
 * the class and personal phones get complementary halves of the six poses (canada-class and
 * peru-personal on one half, peru-class and canada-personal on the other). Any same-nationality
 * pair, the two class phones, or all four phones cover all six; canada-class + peru-personal or
 * canada-personal + peru-class would duplicate a half. Anything else a phone records is
 * evaluation-only, so each phone's local model genuinely never sees half the problem and
 * federation has something to add. Trials recorded under the other role (e.g. before the phone's
 * identity was switched) are ignored.
 */
class PoseWorkflow(context: Context) {

    private val settings = ProjectSettings(context)
    val dataset = LocalDatasetStore(context)
    val models = ModelStore(context)

    data class DataStatus(
        val totalTrials: Int,
        val perPose: Map<PhonePose, Int>,
        val needed: List<Pair<PhonePose, Int>>,
        val trainable: Int,
    ) {
        val ready: Boolean get() = trainable >= MIN_TOTAL_EXAMPLES
        val guidanceComplete: Boolean get() = needed.isEmpty()
    }

    data class TrainOutcome(
        val bundle: PoseModelBundle,
        val trainCount: Int,
        val testCount: Int,
        val report: EvaluationReport?,
    )

    fun status(role: ClientRole = settings.clientRole): DataStatus {
        val counts = dataset.countByPose(role)
        val training = settings.trainingPoses()
        val needed = PhonePose.entries.mapNotNull { pose ->
            val want = if (pose in training) MIN_PRIMARY else MIN_COVER
            val have = counts[pose] ?: 0
            if (have < want) pose to (want - have) else null
        }
        val trainable = training.sumOf { StableSplit.trainCount(counts[it] ?: 0) }
        return DataStatus(counts.values.sum(), counts, needed, trainable)
    }

    fun examples(config: SensorConfig): List<LabeledExample> =
        Preprocessor(config).examplesFromTrials(dataset.loadAll(TrialTask.POSE))

    /**
     * Train = this role's primary-pose trials not in the stable holdout.
     * Test = held-out primary trials + every cover (non-primary) trial, so all six poses are scored.
     */
    fun split(
        config: SensorConfig,
        role: ClientRole = settings.clientRole,
        own: List<Trial> = ownTrials(role),
    ): Pair<List<LabeledExample>, List<LabeledExample>> {
        val holdout = StableSplit.holdoutIds(own)
        val (train, test) = own.partition { t ->
            val pose = PhonePose.fromShortId(t.label)
            pose != null && pose in settings.trainingPoses() && t.id !in holdout
        }
        val prep = Preprocessor(config)
        return prep.examplesFromTrials(train) to prep.examplesFromTrials(test)
    }

    fun trainBaseline(config: SensorConfig, save: Boolean = true): TrainOutcome {
        val (train, test) = split(config)
        check(train.size >= MIN_TOTAL_EXAMPLES) {
            "Need at least $MIN_TOTAL_EXAMPLES primary-pose training trials (have ${train.size}). Record more primary poses."
        }
        val result = LocalTrainer(BASELINE_TRAIN).train(train)
        val report = if (test.isNotEmpty()) Evaluator().evaluate(result.model, test) else null
        val bundle = PoseModelBundle(
            sensorConfig = config,
            model = result.model,
            trainedAtEpochMs = System.currentTimeMillis(),
            trainAccuracy = result.trainAccuracy,
            trainSampleCount = result.sampleCount,
            testAccuracy = report?.accuracy,
            epochs = result.epochsRun,
            round = 0,
            sourceClientId = settings.clientId,
        )
        if (save) models.save(ModelSlot.LOCAL_BASELINE, bundle)
        return TrainOutcome(bundle, train.size, test.size, report)
    }

    /**
     * Warm start from [global] (or from scratch if no global exists yet) and train on local data.
     * The result is saved as the round-local update for round `global.round + 1`.
     */
    fun trainRoundLocal(global: PoseModelBundle?, fallbackConfig: SensorConfig): TrainOutcome {
        val config = global?.sensorConfig ?: fallbackConfig
        val (train, test) = split(config)
        check(train.size >= MIN_TOTAL_EXAMPLES) {
            "Need at least $MIN_TOTAL_EXAMPLES training trials (have ${train.size}). Record more poses."
        }
        val result = LocalTrainer(ROUND_TRAIN).train(train, base = global?.model)
        val report = if (test.isNotEmpty()) Evaluator().evaluate(result.model, test) else null
        val bundle = PoseModelBundle(
            sensorConfig = config,
            model = result.model,
            trainedAtEpochMs = System.currentTimeMillis(),
            trainAccuracy = result.trainAccuracy,
            trainSampleCount = result.sampleCount,
            testAccuracy = report?.accuracy,
            epochs = result.epochsRun,
            round = (global?.round ?: 0) + 1,
            sourceClientId = settings.clientId,
        )
        models.save(ModelSlot.ROUND_LOCAL, bundle)
        return TrainOutcome(bundle, train.size, test.size, report)
    }

    /** This role's pose trials (parses sample arrays — call off the UI thread, reuse when evaluating several models). */
    fun ownTrials(role: ClientRole = settings.clientRole): List<Trial> =
        dataset.loadAll(TrialTask.POSE).filter { it.clientRole == role }

    /** Evaluate [bundle] on this phone's held-out trials. Null if there are none. */
    fun evaluateHoldout(bundle: PoseModelBundle, own: List<Trial> = ownTrials()): EvaluationReport? {
        val (_, test) = split(bundle.sensorConfig, own = own)
        if (test.isEmpty()) return null
        return Evaluator().evaluate(bundle.model, test)
    }

    companion object {
        const val MIN_TOTAL_EXAMPLES = 6
        const val MIN_PRIMARY = 10
        const val MIN_COVER = 4

        val DEFAULT_SENSOR_CONFIG = SensorConfig.ALL

        val BASELINE_TRAIN = TrainConfig(epochs = 80, learningRate = 0.15f)

        /** Fewer local epochs per round limits client drift on non-IID data. */
        val ROUND_TRAIN = TrainConfig(epochs = 40, learningRate = 0.1f)
    }
}
