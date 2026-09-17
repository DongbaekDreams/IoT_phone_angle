package edu.iot.phoneangle.ml

import edu.iot.phoneangle.data.PhonePose
import edu.iot.phoneangle.data.SensorSample
import edu.iot.phoneangle.data.Trial
import edu.iot.phoneangle.data.TrialTask
import kotlin.math.sqrt

/**
 * Converts a trial (or live sample buffer) into a fixed-length feature vector.
 * Pose holds are summarized with mean/std over the window — good for static poses.
 */
class Preprocessor(
    val sensorConfig: SensorConfig = SensorConfig.ALL,
) {

    fun featureDim(): Int = FEATURE_DIM

    fun exampleFromTrial(trial: Trial): LabeledExample? {
        if (trial.task != TrialTask.POSE) return null
        val pose = PhonePose.fromShortId(trial.label) ?: return null
        val features = featuresFromSamples(trial.samples) ?: return null
        return LabeledExample(features = features, label = pose)
    }

    fun examplesFromTrials(trials: List<Trial>): List<LabeledExample> =
        trials.mapNotNull { exampleFromTrial(it) }

    fun featuresFromSamples(samples: List<SensorSample>): FloatArray? {
        if (samples.isEmpty()) return null
        val window = downsample(samples, TARGET_SAMPLES)
        val features = FloatArray(FEATURE_DIM)

        val gMean = FloatArray(3)
        val gStd = FloatArray(3)
        val aMean = FloatArray(3)
        val aStd = FloatArray(3)
        val yMean = FloatArray(3)
        val yStd = FloatArray(3)
        val rMean = FloatArray(4)

        stats3(window.mapNotNull { it.gravity }, gMean, gStd)
        stats3(window.mapNotNull { it.accel }, aMean, aStd)
        stats3(window.mapNotNull { it.gyro }, yMean, yStd)
        mean4(window.mapNotNull { it.rotationVector }, rMean)

        var i = 0
        // gravity mean/std
        for (c in 0 until 3) {
            features[i++] = if (sensorConfig.useGravity()) gMean[c] / G else 0f
        }
        for (c in 0 until 3) {
            features[i++] = if (sensorConfig.useGravity()) gStd[c] / G else 0f
        }
        // accel mean/std
        for (c in 0 until 3) {
            features[i++] = if (sensorConfig.useAccel()) aMean[c] / G else 0f
        }
        for (c in 0 until 3) {
            features[i++] = if (sensorConfig.useAccel()) aStd[c] / G else 0f
        }
        // gyro mean/std
        for (c in 0 until 3) {
            features[i++] = if (sensorConfig.useGyro()) yMean[c] else 0f
        }
        for (c in 0 until 3) {
            features[i++] = if (sensorConfig.useGyro()) yStd[c] else 0f
        }
        // rotation vector mean (unitless)
        for (c in 0 until 4) {
            features[i++] = if (sensorConfig.useRotation()) rMean[c] else 0f
        }
        check(i == FEATURE_DIM)
        return features
    }

    private fun downsample(samples: List<SensorSample>, target: Int): List<SensorSample> {
        if (samples.size <= target) return samples
        val step = samples.size.toFloat() / target
        return List(target) { idx -> samples[(idx * step).toInt().coerceIn(0, samples.lastIndex)] }
    }

    private fun stats3(
        values: List<edu.iot.phoneangle.data.FloatArray3>,
        meanOut: FloatArray,
        stdOut: FloatArray,
    ) {
        if (values.isEmpty()) return
        val n = values.size.toFloat()
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        for (v in values) {
            sx += v.x
            sy += v.y
            sz += v.z
        }
        meanOut[0] = (sx / n).toFloat()
        meanOut[1] = (sy / n).toFloat()
        meanOut[2] = (sz / n).toFloat()
        var vx = 0.0
        var vy = 0.0
        var vz = 0.0
        for (v in values) {
            vx += (v.x - meanOut[0]).let { it * it }
            vy += (v.y - meanOut[1]).let { it * it }
            vz += (v.z - meanOut[2]).let { it * it }
        }
        stdOut[0] = sqrt(vx / n).toFloat()
        stdOut[1] = sqrt(vy / n).toFloat()
        stdOut[2] = sqrt(vz / n).toFloat()
    }

    private fun mean4(
        values: List<edu.iot.phoneangle.data.FloatArray4>,
        meanOut: FloatArray,
    ) {
        if (values.isEmpty()) return
        val n = values.size.toFloat()
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var sw = 0.0
        for (v in values) {
            sx += v.x
            sy += v.y
            sz += v.z
            sw += v.w
        }
        meanOut[0] = (sx / n).toFloat()
        meanOut[1] = (sy / n).toFloat()
        meanOut[2] = (sz / n).toFloat()
        meanOut[3] = (sw / n).toFloat()
    }

    companion object {
        const val FEATURE_DIM = 22 // 6 gravity + 6 accel + 6 gyro + 4 rotation
        const val TARGET_SAMPLES = 64
        private const val G = 9.80665f
    }
}

data class LabeledExample(
    val features: FloatArray,
    val label: PhonePose,
)
