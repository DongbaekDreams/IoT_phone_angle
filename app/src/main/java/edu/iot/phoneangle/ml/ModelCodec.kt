package edu.iot.phoneangle.ml

import edu.iot.phoneangle.data.PhonePose
import org.json.JSONArray
import org.json.JSONObject

/** Shared JSON shapes for on-device storage and federation HTTP. */
object ModelCodec {

    fun bundleToJson(bundle: PoseModelBundle): JSONObject = JSONObject().apply {
        put("version", bundle.version)
        put("task", "pose")
        put("sensorConfig", bundle.sensorConfig.id)
        put("featureDim", bundle.model.featureDim)
        put("trainedAtEpochMs", bundle.trainedAtEpochMs)
        put("trainAccuracy", bundle.trainAccuracy.toDouble())
        put("trainSampleCount", bundle.trainSampleCount)
        put("testAccuracy", bundle.testAccuracy?.toDouble() ?: JSONObject.NULL)
        put("epochs", bundle.epochs)
        put("classes", JSONArray(bundle.model.classes.map { it.shortId }))
        put("bias", JSONArray(bundle.model.bias.map { it.toDouble() }))
        put("weights", weightsToJson(bundle.model.weights))
    }

    fun bundleFromJson(obj: JSONObject): PoseModelBundle {
        val classes = buildList {
            val arr = obj.getJSONArray("classes")
            for (i in 0 until arr.length()) {
                add(PhonePose.fromShortId(arr.getString(i)) ?: error("Unknown class"))
            }
        }
        val featureDim = obj.getInt("featureDim")
        val biasArr = obj.getJSONArray("bias")
        val bias = FloatArray(biasArr.length()) { biasArr.getDouble(it).toFloat() }
        val weights = weightsFromJson(obj.getJSONArray("weights"))
        return PoseModelBundle(
            version = obj.optInt("version", 1),
            sensorConfig = SensorConfig.fromId(obj.optString("sensorConfig", SensorConfig.ALL.id)),
            model = SoftmaxClassifier(featureDim, classes, weights, bias),
            trainedAtEpochMs = obj.optLong("trainedAtEpochMs", 0L),
            trainAccuracy = obj.optDouble("trainAccuracy", 0.0).toFloat(),
            trainSampleCount = obj.optInt("trainSampleCount", 0),
            testAccuracy = if (obj.isNull("testAccuracy")) null else obj.getDouble("testAccuracy").toFloat(),
            epochs = obj.optInt("epochs", 0),
        )
    }

    fun updateToJson(clientId: String, sampleCount: Int, model: SoftmaxClassifier, sensorConfig: SensorConfig): JSONObject =
        JSONObject().apply {
            put("clientId", clientId)
            put("sampleCount", sampleCount)
            put("sensorConfig", sensorConfig.id)
            put("featureDim", model.featureDim)
            put("classes", JSONArray(model.classes.map { it.shortId }))
            put("bias", JSONArray(model.bias.map { it.toDouble() }))
            put("weights", weightsToJson(model.weights))
        }

    fun modelFromUpdateJson(obj: JSONObject): SoftmaxClassifier {
        val classes = buildList {
            val arr = obj.getJSONArray("classes")
            for (i in 0 until arr.length()) {
                add(PhonePose.fromShortId(arr.getString(i)) ?: error("Unknown class"))
            }
        }
        return SoftmaxClassifier(
            featureDim = obj.getInt("featureDim"),
            classes = classes,
            weights = weightsFromJson(obj.getJSONArray("weights")),
            bias = obj.getJSONArray("bias").let { b ->
                FloatArray(b.length()) { b.getDouble(it).toFloat() }
            },
        )
    }

    private fun weightsToJson(weights: Array<FloatArray>): JSONArray =
        JSONArray().also { rows ->
            weights.forEach { row -> rows.put(JSONArray(row.map { it.toDouble() })) }
        }

    private fun weightsFromJson(arr: JSONArray): Array<FloatArray> =
        Array(arr.length()) { r ->
            val row = arr.getJSONArray(r)
            FloatArray(row.length()) { c -> row.getDouble(c).toFloat() }
        }
}
