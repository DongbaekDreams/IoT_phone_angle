package edu.iot.phoneangle.ml.typed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Everything the live listener needs, plus the held-out report from training. */
data class TapModelBundle(
    val position: TapPositionModel,
    /** Tap-onset sensitivity picked on this device's train trials. */
    val sensitivity: Float,
    /** Word-spotting margin picked on validation trials. */
    val threshold: Float,
    val trainedAtEpochMs: Long,
    val trainTaps: Int,
    val report: TapEvalReport? = null,
    val featureVersion: Int = TapStream.FEATURE_VERSION,
    /** Thumb-side / double-key-timing evidence weights picked on validation. */
    val weights: WordSpotter.Weights = WordSpotter.Weights(),
    /** Learned tap-onset detector; null = energy fallback with [sensitivity]. */
    val detector: TapDetector? = null,
    /** Mean raw tap features: the [TapCentering] center until a session has enough taps. */
    val featureMean: FloatArray? = null,
)

class TapModelStore(context: Context, fileName: String = FILE_NAME) {
    private val file = File(File(context.filesDir, "models").also { it.mkdirs() }, fileName)

    fun save(bundle: TapModelBundle) = file.writeText(toJson(bundle).toString(2))

    fun load(): TapModelBundle? {
        if (!file.exists()) return null
        return runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun has(): Boolean = file.exists()

    companion object {
        const val FILE_NAME = "typed_tap_local.json"
        /** This phone's own model (what it shares), separate from the active, possibly federated one. */
        const val LOCAL_ONLY_FILE_NAME = "typed_tap_local_only.json"
        private const val KIND = "typed_tap_spotter"

        private fun FloatArray.json() = JSONArray().also { a -> forEach { a.put(it.toDouble()) } }
        private fun JSONObject.floats(key: String): FloatArray {
            val a = getJSONArray(key)
            return FloatArray(a.length()) { a.getDouble(it).toFloat() }
        }

        fun toJson(b: TapModelBundle): JSONObject = JSONObject().apply {
            val p = b.position
            put("kind", KIND)
            put("featureVersion", b.featureVersion)
            put("featureNames", JSONArray(TapStream.FEATURE_NAMES))
            put("trainedAtEpochMs", b.trainedAtEpochMs)
            put("trainTaps", b.trainTaps)
            put("sensitivity", b.sensitivity.toDouble())
            put("threshold", b.threshold.toDouble())
            put("thumbWeight", b.weights.thumb.toDouble())
            put("timingWeight", b.weights.doubleKeyTiming.toDouble())
            put("movementWeight", b.weights.movement.toDouble())
            b.featureMean?.let { put("featureMean", it.json()) }
            b.detector?.let { d ->
                put("detector", JSONObject().apply {
                    put("w", d.w.json())
                    put("b", d.b.toDouble())
                    put("threshold", d.threshold.toDouble())
                })
            }
            // Shareable (FedAvg) weights.
            put("wx", p.wx.json())
            put("bx", p.bx.toDouble())
            put("wy", p.wy.json())
            put("by", p.by.toDouble())
            put("wt", p.wt.json())
            put("bt", p.bt.toDouble())
            p.move?.let { m ->
                put("move", JSONObject().apply {
                    put("wdx", m.wdx.json())
                    put("bdx", m.bdx.toDouble())
                    put("wdy", m.wdy.json())
                    put("bdy", m.bdy.toDouble())
                    put("sigmaDx", m.sigmaDx.toDouble())
                    put("sigmaDy", m.sigmaDy.toDouble())
                    put("calDx", JSONArray(listOf(m.alphaDx.toDouble(), m.betaDx.toDouble())))
                    put("calDy", JSONArray(listOf(m.alphaDy.toDouble(), m.betaDy.toDouble())))
                })
            }
            // Device-local calibration.
            put("scalerMu", p.scaler.mu.json())
            put("scalerSigma", p.scaler.sigma.json())
            put("sigmaX", p.sigmaX.toDouble())
            put("sigmaY", p.sigmaY.toDouble())
            put("calX", JSONArray(listOf(p.alphaX.toDouble(), p.betaX.toDouble())))
            put("calY", JSONArray(listOf(p.alphaY.toDouble(), p.betaY.toDouble())))
            put("sameKey", JSONArray(listOf(p.sameKey.mean.toDouble(), p.sameKey.std.toDouble())))
            put("diffKey", JSONArray(listOf(p.diffKey.mean.toDouble(), p.diffKey.std.toDouble())))
            b.report?.let { put("report", it.toJson()) }
        }

        /** (alpha, beta) calibration pair; identity when absent (older models). */
        private fun cal(o: JSONObject, key: String): Pair<Float, Float> =
            o.optJSONArray(key)?.let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } ?: (1f to 0f)

        fun fromJson(o: JSONObject): TapModelBundle {
            require(o.optString("kind") == KIND) { "Not a tap-spotter model — retrain" }
            require(o.getInt("featureVersion") == TapStream.FEATURE_VERSION) { "Tap feature version changed — retrain" }
            val wx = o.floats("wx")
            require(wx.size == TapPositionModel.EXPANDED_DIM || wx.size == TapStream.FEATURE_DIM) { "Feature dim mismatch — retrain" }
            fun gauss(key: String) = o.getJSONArray(key).let {
                TapPositionModel.Gauss(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())
            }
            val position = TapPositionModel(
                scaler = TapScaler().also { it.load(o.floats("scalerMu"), o.floats("scalerSigma")) },
                wx = wx,
                bx = o.getDouble("bx").toFloat(),
                wy = o.floats("wy"),
                by = o.getDouble("by").toFloat(),
                sigmaX = o.getDouble("sigmaX").toFloat(),
                sigmaY = o.getDouble("sigmaY").toFloat(),
                sameKey = gauss("sameKey"),
                diffKey = gauss("diffKey"),
                wt = if (o.has("wt")) o.floats("wt") else FloatArray(0),
                bt = o.optDouble("bt", 0.0).toFloat(),
                alphaX = cal(o, "calX").first,
                betaX = cal(o, "calX").second,
                alphaY = cal(o, "calY").first,
                betaY = cal(o, "calY").second,
                move = o.optJSONObject("move")?.let { m ->
                    TapPositionModel.MoveModel(
                        m.floats("wdx"), m.getDouble("bdx").toFloat(),
                        m.floats("wdy"), m.getDouble("bdy").toFloat(),
                        m.getDouble("sigmaDx").toFloat(), m.getDouble("sigmaDy").toFloat(),
                        cal(m, "calDx").first, cal(m, "calDx").second,
                        cal(m, "calDy").first, cal(m, "calDy").second,
                    )
                },
            )
            return TapModelBundle(
                position = position,
                sensitivity = o.getDouble("sensitivity").toFloat(),
                threshold = o.getDouble("threshold").toFloat(),
                trainedAtEpochMs = o.getLong("trainedAtEpochMs"),
                trainTaps = o.getInt("trainTaps"),
                report = o.optJSONObject("report")?.let { TapEvalReport.fromJson(it) },
                weights = WordSpotter.Weights(
                    thumb = o.optDouble("thumbWeight", 0.0).toFloat(),
                    doubleKeyTiming = o.optDouble("timingWeight", 0.0).toFloat(),
                    movement = o.optDouble("movementWeight", 0.0).toFloat(),
                ),
                featureMean = if (o.has("featureMean")) o.floats("featureMean") else null,
                detector = o.optJSONObject("detector")?.let { d ->
                    TapDetector(d.floats("w"), d.getDouble("b").toFloat(), d.getDouble("threshold").toFloat())
                },
            )
        }
    }
}
