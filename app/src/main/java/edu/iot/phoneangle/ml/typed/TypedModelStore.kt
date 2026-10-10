package edu.iot.phoneangle.ml.typed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TypedModelBundle(
    val model: TypedSoftmax,
    val scaler: FeatureScaler,
    val rocketBiases: FloatArray,
    val featureVersion: Int = TypedPreprocessor.VERSION,
    val rocketVersion: Int = MiniRocketTransform.VERSION,
    val trainedAtEpochMs: Long,
    val trainAccuracy: Float?,
    val trainSampleCount: Int,
    val testAccuracy: Float? = null,
    val epochs: Int = 0,
    /** Train counts per [TypedLabel] id — used to debias majority-class live preds. */
    val classCounts: Map<TypedLabel, Int> = emptyMap(),
    /** Live models: IMU window length the model was trained on (0 = snipped whole-trial model). */
    val windowMs: Long = 0L,
) {
    /** logit' = logit - ln(prior) so frequent classes are not auto-winners. */
    val logitAdjust: FloatArray by lazy {
        val n = classCounts.values.sum().coerceAtLeast(1).toFloat()
        FloatArray(model.classes.size) { i ->
            val c = classCounts[model.classes[i]] ?: 1
            -kotlin.math.ln((c.toFloat() / n).coerceIn(1e-3f, 1f))
        }
    }
}

class TypedModelStore(context: Context, fileName: String = FILE_NAME) {
    private val file = File(File(context.filesDir, "models").also { it.mkdirs() }, fileName)

    fun save(bundle: TypedModelBundle) {
        file.writeText(toJson(bundle).toString(2))
    }

    fun load(): TypedModelBundle? {
        if (!file.exists()) return null
        return runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun has(): Boolean = file.exists()

    fun delete() {
        file.delete()
    }

    private fun toJson(b: TypedModelBundle): JSONObject = JSONObject().apply {
        put("kind", "typed_word_minirocket_mv")
        put("featureVersion", b.featureVersion)
        put("rocketVersion", b.rocketVersion)
        put("featureDim", b.model.featureDim)
        put("trainedAtEpochMs", b.trainedAtEpochMs)
        put("trainAccuracy", b.trainAccuracy?.toDouble())
        put("trainSampleCount", b.trainSampleCount)
        put("testAccuracy", b.testAccuracy?.toDouble())
        put("epochs", b.epochs)
        put("windowMs", b.windowMs)
        put("classes", JSONArray(b.model.classes.map { it.id }))
        val (mu, sigma) = b.scaler.toArrays()
        put("scalerMu", JSONArray().also { a -> mu.forEach { a.put(it.toDouble()) } })
        put("scalerSigma", JSONArray().also { a -> sigma.forEach { a.put(it.toDouble()) } })
        put("rocketBiases", JSONArray().also { a -> b.rocketBiases.forEach { a.put(it.toDouble()) } })
        put(
            "weights",
            JSONArray().also { arr ->
                for (row in b.model.weights) {
                    arr.put(JSONArray().also { r -> row.forEach { r.put(it.toDouble()) } })
                }
            },
        )
        put("bias", JSONArray().also { arr -> b.model.bias.forEach { arr.put(it.toDouble()) } })
        put(
            "classCounts",
            JSONObject().also { o ->
                for (label in TypedLabel.entries) {
                    o.put(label.id, (b.classCounts[label] ?: 0))
                }
            },
        )
    }

    private fun fromJson(obj: JSONObject): TypedModelBundle {
        require(obj.optString("kind") == "typed_word_minirocket_mv") { "Not a typed MiniRocket model — retrain" }
        require(obj.getInt("featureVersion") == TypedPreprocessor.VERSION) { "Typed feature version mismatch — retrain" }
        require(obj.getInt("rocketVersion") == MiniRocketTransform.VERSION) { "Rocket version mismatch — retrain" }
        val classes = buildList {
            val arr = obj.getJSONArray("classes")
            for (i in 0 until arr.length()) add(TypedLabel.fromId(arr.getString(i)))
        }
        val featureDim = obj.getInt("featureDim")
        require(featureDim == TypedPreprocessor.FEATURE_DIM) { "Feature dim mismatch — retrain" }
        val wArr = obj.getJSONArray("weights")
        val weights = Array(wArr.length()) { r ->
            val row = wArr.getJSONArray(r)
            FloatArray(row.length()) { c -> row.getDouble(c).toFloat() }
        }
        val bArr = obj.getJSONArray("bias")
        val bias = FloatArray(bArr.length()) { bArr.getDouble(it).toFloat() }
        fun arr(key: String): FloatArray {
            val a = obj.getJSONArray(key)
            return FloatArray(a.length()) { a.getDouble(it).toFloat() }
        }
        val scaler = FeatureScaler().also { it.load(arr("scalerMu"), arr("scalerSigma")) }
        val rocketBiases = arr("rocketBiases")
        require(rocketBiases.size == featureDim) { "Rocket bias dim mismatch — retrain" }
        val counts = linkedMapOf<TypedLabel, Int>()
        val cObj = obj.optJSONObject("classCounts")
        if (cObj != null) {
            for (label in TypedLabel.entries) {
                counts[label] = cObj.optInt(label.id, 0)
            }
        }
        return TypedModelBundle(
            model = TypedSoftmax(featureDim, classes, weights, bias),
            scaler = scaler,
            rocketBiases = rocketBiases,
            featureVersion = obj.getInt("featureVersion"),
            rocketVersion = obj.getInt("rocketVersion"),
            trainedAtEpochMs = obj.getLong("trainedAtEpochMs"),
            trainAccuracy = obj.optDouble("trainAccuracy", Double.NaN).takeUnless { it.isNaN() }?.toFloat(),
            trainSampleCount = obj.getInt("trainSampleCount"),
            testAccuracy = obj.optDouble("testAccuracy", Double.NaN).takeUnless { it.isNaN() }?.toFloat(),
            epochs = obj.optInt("epochs", 0),
            classCounts = counts,
            windowMs = obj.optLong("windowMs", 0L),
        )
    }

    companion object {
        const val FILE_NAME = "typed_local.json"
        const val LIVE_FILE_NAME = "typed_live_local.json"
    }
}
