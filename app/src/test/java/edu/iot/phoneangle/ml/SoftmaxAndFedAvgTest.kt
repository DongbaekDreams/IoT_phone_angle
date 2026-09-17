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
}
