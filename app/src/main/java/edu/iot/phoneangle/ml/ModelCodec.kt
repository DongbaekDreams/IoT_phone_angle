package edu.iot.phoneangle.ml

import edu.iot.phoneangle.data.PhonePose
import org.json.JSONArray
import org.json.JSONObject

/** JSON shapes for on-device model storage. Weights/metadata only. */
object ModelCodec {

    fun bundleToJson(bundle: PoseModelBundle): JSONObject = JSONObject().apply {
        put("version", bundle.version)
        put("task", "pose")
        put("sensorConfig", bundle.sensorConfig.id)
        put("preprocessingVersion", Preprocessor.VERSION)
        put("trainedAtEpochMs", bundle.trainedAtEpochMs)
        put("trainAccuracy", bundle.trainAccuracy.toDouble())
        put("trainSampleCount", bundle.trainSampleCount)
        put("testAccuracy", bundle.testAccuracy?.toDouble() ?: JSONObject.NULL)
        put("epochs", bundle.epochs)
        put("round", bundle.round)
        put("sourceClientId", bundle.sourceClientId)
        put("contributors", JSONArray(bundle.contributors))
        putModel(bundle.model)
    }

    fun bundleFromJson(obj: JSONObject): PoseModelBundle = PoseModelBundle(
        version = obj.optInt("version", 1),
        sensorConfig = SensorConfig.fromId(obj.optString("sensorConfig", SensorConfig.ALL.id)),
        model = modelFromJson(obj),
        trainedAtEpochMs = obj.optLong("trainedAtEpochMs", 0L),
        trainAccuracy = obj.optDouble("trainAccuracy", 0.0).toFloat(),
        trainSampleCount = obj.optInt("trainSampleCount", 0),
        testAccuracy = if (!obj.has("testAccuracy") || obj.isNull("testAccuracy")) null else obj.getDouble("testAccuracy").toFloat(),
        epochs = obj.optInt("epochs", 0),
        round = obj.optInt("round", 0),
        sourceClientId = obj.optString("sourceClientId", ""),
        contributors = stringList(obj.optJSONArray("contributors")),
    )

    /** Writes featureDim/classes/bias/weights into [target]. */
    fun JSONObject.putModel(model: SoftmaxClassifier) {
        put("featureDim", model.featureDim)
        put("classes", JSONArray(model.classes.map { it.shortId }))
        put("bias", JSONArray(model.bias.map { it.toDouble() }))
        put("weights", weightsToJson(model.weights))
    }

    fun modelFromJson(obj: JSONObject): SoftmaxClassifier {
        val classes = buildList {
            val arr = obj.getJSONArray("classes")
            for (i in 0 until arr.length()) {
                add(PhonePose.fromShortId(arr.getString(i)) ?: error("Unknown class ${arr.getString(i)}"))
            }
        }
        val biasArr = obj.getJSONArray("bias")
        return SoftmaxClassifier(
            featureDim = obj.getInt("featureDim"),
            classes = classes,
            weights = weightsFromJson(obj.getJSONArray("weights")),
            bias = FloatArray(biasArr.length()) { biasArr.getDouble(it).toFloat() },
        )
    }

    fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return List(arr.length()) { arr.getString(it) }
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
