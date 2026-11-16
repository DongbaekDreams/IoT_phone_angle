package edu.iot.phoneangle.ml.typed

import org.json.JSONArray
import org.json.JSONObject

/**
 * Federated averaging for the typed-word tap model (pure; transport lives in [TapWorkflow]).
 *
 * Phones share their locally trained model JSON (weights and summary statistics only — never
 * recordings, touches or text). The global model is the tap-count-weighted average of every
 * numeric field. A phone with its own data then keeps its local feature scaling, calibration and
 * thresholds (FedBN-style: they absorb that phone's hand / hold), using the averaged weights.
 * A phone with no data uses the averaged model as-is.
 */
object TypedFederation {

    /** Not averaged: identity / bookkeeping. */
    private val SKIP = setOf("kind", "featureVersion", "featureNames", "trainedAtEpochMs", "report")

    /** Kept from the phone's own model when it has one (local adaptation). */
    private val LOCAL_KEYS = listOf(
        "scalerMu", "scalerSigma", "featureMean", "calX", "calY", "sigmaX", "sigmaY",
        "threshold", "sensitivity", "thumbWeight", "timingWeight", "movementWeight",
    )

    /** JSON safe to upload: the model without its evaluation report. */
    fun shareable(bundle: TapModelBundle): JSONObject {
        val o = TapModelStore.toJson(bundle)
        o.remove("report")
        check(!o.toString().contains("\"samples\"") && !o.toString().contains("\"touches\"")) {
            "Refusing to share: payload looks like raw data"
        }
        return o
    }

    /** Weighted (by training taps) average of compatible models. */
    fun average(models: List<JSONObject>): TapModelBundle {
        require(models.isNotEmpty()) { "No models to average" }
        val version = models.first().getInt("featureVersion")
        require(models.all { it.getInt("featureVersion") == version }) { "Phones run different app versions — update both" }
        val weights = models.map { it.optInt("trainTaps", 1).coerceAtLeast(1).toDouble() }
        val out = avgObject(models, weights)
        val first = models.first()
        for (k in SKIP) if (first.has(k) && k != "report") out.put(k, first.get(k))
        out.put("trainTaps", models.sumOf { it.optInt("trainTaps") })
        out.put("trainedAtEpochMs", System.currentTimeMillis())
        return TapModelStore.fromJson(out)
    }

    /** Averaged weights with this phone's own scaling / calibration / thresholds. */
    fun localize(global: TapModelBundle, local: TapModelBundle?): TapModelBundle {
        if (local == null) return global
        val g = TapModelStore.toJson(global)
        val l = TapModelStore.toJson(local)
        for (k in LOCAL_KEYS) if (l.has(k)) g.put(k, l.get(k))
        // Detector and movement weights are shared; their thresholds / calibration stay local.
        l.optJSONObject("detector")?.let { ld -> g.optJSONObject("detector")?.put("threshold", ld.get("threshold")) }
        val lm = l.optJSONObject("move")
        val gm = g.optJSONObject("move")
        if (lm != null && gm != null) for (k in listOf("sigmaDx", "sigmaDy", "calDx", "calDy")) gm.put(k, lm.get(k))
        return TapModelStore.fromJson(g).copy(report = local.report)
    }

    private fun avgObject(objs: List<JSONObject>, w: List<Double>): JSONObject {
        val out = JSONObject()
        val keys = objs.first().keys().asSequence().toList()
        for (k in keys) {
            if (k in SKIP) continue
            val present = objs.indices.filter { objs[it].has(k) && !objs[it].isNull(k) }
            if (present.isEmpty()) continue
            val vals = present.map { objs[it].get(k) }
            val ws = present.map { w[it] }
            out.put(k, avgValue(vals, ws) ?: vals.first())
        }
        return out
    }

    private fun avgValue(vals: List<Any>, w: List<Double>): Any? {
        val first = vals.first()
        val total = w.sum()
        return when {
            vals.all { it is Number } -> vals.indices.sumOf { (vals[it] as Number).toDouble() * w[it] } / total
            vals.all { it is JSONArray } -> {
                val arrs = vals.map { it as JSONArray }
                val n = arrs.first().length()
                if (arrs.any { it.length() != n }) error("Model shapes differ between phones")
                JSONArray().also { out ->
                    for (i in 0 until n) out.put(avgValue(arrs.map { it.get(i) }, w) ?: arrs.first().get(i))
                }
            }
            vals.all { it is JSONObject } -> avgObject(vals.map { it as JSONObject }, w)
            else -> first
        }
    }
}
