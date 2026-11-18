package edu.iot.phoneangle.ml

import edu.iot.phoneangle.data.ClientRole
import edu.iot.phoneangle.data.PhonePose
import edu.iot.phoneangle.data.SyntheticPoseData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftmaxAndFedAvgTest {

    @Test
    fun syntheticPoseFeatures_trainAboveChance() {
        val examples = PhonePose.entries.flatMap { pose ->
            List(6) { i ->
                val trial = SyntheticPoseData.generateTrial(
                    pose = pose,
                    clientRole = ClientRole.CLIENT_A,
                    seed = pose.ordinal * 100L + i,
                )
                Preprocessor(SensorConfig.ALL).exampleFromTrial(trial)!!
            }
        }
        val (train, test) = splitTrainTest(examples, testFraction = 0.25f, seed = 1L)
        val result = LocalTrainer(TrainConfig(epochs = 60, seed = 1L)).train(train)
        val report = Evaluator().evaluate(result.model, test)
        assertTrue("train acc=${result.trainAccuracy}", result.trainAccuracy >= 0.85f)
        assertTrue("test acc=${report.accuracy}", report.accuracy >= 0.70f)
    }

    @Test
    fun fedAvg_averagesTwoClients() {
        val a = SoftmaxClassifier()
        val b = SoftmaxClassifier()
        a.weights[0][0] = 2f
        a.bias[0] = 2f
        b.weights[0][0] = 4f
        b.bias[0] = 4f
        val global = FedAvg.aggregate(
            listOf(
                FedAvg.ClientUpdate("a", sampleCount = 1, model = a),
                FedAvg.ClientUpdate("b", sampleCount = 1, model = b),
            ),
        )
        assertEquals(3f, global.weights[0][0], 1e-5f)
        assertEquals(3f, global.bias[0], 1e-5f)
    }

    @Test
    fun fedAvg_weightsBySampleCount() {
        val a = SoftmaxClassifier()
        val b = SoftmaxClassifier()
        a.bias[1] = 0f
        b.bias[1] = 10f
        val global = FedAvg.aggregate(
            listOf(
                FedAvg.ClientUpdate("a", sampleCount = 1, model = a),
                FedAvg.ClientUpdate("b", sampleCount = 3, model = b),
            ),
        )
        assertEquals(7.5f, global.bias[1], 1e-5f)
    }

    @Test
    fun preprocessor_featureDimMatchesModel() {
        val trial = SyntheticPoseData.generateTrial(PhonePose.SCREEN_UP, ClientRole.CLIENT_A, seed = 0)
        val features = Preprocessor(SensorConfig.GRAVITY_ONLY).featuresFromSamples(trial.samples)!!
        assertEquals(Preprocessor.FEATURE_DIM, features.size)
        assertEquals(Preprocessor.FEATURE_DIM, SoftmaxClassifier().featureDim)
    }

    @Test
    fun preprocessor_noGravitySensor_fallsBackToAccel() {
        val trial = SyntheticPoseData.generateTrial(PhonePose.PORTRAIT_UP, ClientRole.CLIENT_A, seed = 7)
        val noGravity = trial.copy(samples = trial.samples.map { it.copy(gravity = null) })
        val features = Preprocessor(SensorConfig.GRAVITY_ONLY).featuresFromSamples(noGravity.samples)!!
        assertTrue(
            "gravity features all ~0: ${features.take(3)}",
            features.take(3).any { kotlin.math.abs(it) > 0.01f },
        )
        val g = 9.80665f
        val accels = noGravity.samples.mapNotNull { it.accel }
        val accelMean = floatArrayOf(
            accels.map { it.x }.average().toFloat() / g,
            accels.map { it.y }.average().toFloat() / g,
            accels.map { it.z }.average().toFloat() / g,
        )
        for (c in 0 until 3) {
            assertEquals("axis $c", accelMean[c], features[c], 0.05f)
        }
    }

    @Test
    fun fedAvg_complementaryNonIidHalves_recoverGlobalAccuracy() {
        val setA = setOf(PhonePose.SCREEN_UP, PhonePose.SCREEN_DOWN, PhonePose.PORTRAIT_UP)
        val setB = PhonePose.entries.toSet() - setA
        val prep = Preprocessor(SensorConfig.GRAVITY_ONLY)
        fun examples(poses: Set<PhonePose>, perPose: Int, seedBase: Long) = poses.flatMap { pose ->
            List(perPose) { i ->
                prep.exampleFromTrial(
                    SyntheticPoseData.generateTrial(
                        pose,
                        ClientRole.CLIENT_A,
                        seed = seedBase + pose.ordinal * 1000L + i,
                    ),
                )!!
            }
        }
        val trainA = examples(setA, 10, 10_000L)
        val trainB = examples(setB, 10, 20_000L)
        val test = examples(PhonePose.entries.toSet(), 10, 30_000L)
        val eval = Evaluator()

        val accA = eval.evaluate(LocalTrainer(PoseWorkflow.BASELINE_TRAIN).train(trainA).model, test).accuracy
        val accB = eval.evaluate(LocalTrainer(PoseWorkflow.BASELINE_TRAIN).train(trainB).model, test).accuracy
        assertTrue("local A acc=$accA", accA <= 0.6f)
        assertTrue("local B acc=$accB", accB <= 0.6f)

        var global: SoftmaxClassifier? = null
        repeat(2) {
            val upA = LocalTrainer(PoseWorkflow.ROUND_TRAIN).train(trainA, base = global)
            val upB = LocalTrainer(PoseWorkflow.ROUND_TRAIN).train(trainB, base = global)
            global = FedAvg.aggregate(
                listOf(
                    FedAvg.ClientUpdate("a", upA.sampleCount, upA.model),
                    FedAvg.ClientUpdate("b", upB.sampleCount, upB.model),
                ),
            )
        }
        val fedAcc = eval.evaluate(global!!, test).accuracy
        assertTrue("federated acc=$fedAcc", fedAcc >= 0.9f)
    }
}
