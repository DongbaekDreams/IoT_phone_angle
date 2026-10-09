package edu.iot.phoneangle.federation

import edu.iot.phoneangle.ml.ModelCodec
import edu.iot.phoneangle.ml.Preprocessor
import edu.iot.phoneangle.ml.SensorConfig
import edu.iot.phoneangle.ml.SoftmaxClassifier
import org.json.JSONArray
import org.json.JSONObject

/*
 * Everything that leaves the phone is one of these two types. They can only hold model
 * weights, counts, and metrics - there is deliberately no field that could carry a Trial
 * or SensorSample. PrivacyGuard re-checks every serialized payload against an allowlist.
 */

enum class PayloadType(val id: String) {
    /** A client's round-local model for round N. */
    UPDATE("update"),

    /** The aggregated model for round N. */
    GLOBAL("global"),

    /** A client's independent local-only model (never warm-started from global). */
    BASELINE("baseline");

    companion object {
        fun fromId(id: String): PayloadType =
            entries.find { it.id == id } ?: error("Unknown payload type: $id")
    }
}

data class ModelPayload(
    val type: PayloadType,
    val clientId: String,
    val clientRole: String,
    val round: Int,
    val sampleCount: Int,
    /** Number of trials per pose id (counts only, no data). */
    val poseCounts: Map<String, Int>,
    val sensorConfig: SensorConfig,
    val preprocessingVersion: Int = Preprocessor.VERSION,
    val model: SoftmaxClassifier,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val contributors: List<String> = emptyList(),
    val testAccuracy: Float? = null,
)

data class ClassScore(
    val pose: String,
    val accuracy: Float,
    val f1: Float,
    val support: Int,
)

/** Evaluation metrics for one model on one phone's held-out trials. */
data class ResultPayload(
    val clientId: String,
    val clientRole: String,
    /** "local_baseline", "round_local", "global", or "peer_baseline". */
    val modelKind: String,
    val round: Int,
    val sensorConfig: SensorConfig,
    val evalCount: Int,
    val accuracy: Float,
    val macroF1: Float,
    val perClass: List<ClassScore>,
    val classes: List<String>,
    val confusion: Array<IntArray>,
    val sessionTags: List<String> = emptyList(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

object PrivacyGuard {
    val MODEL_KEYS = setOf(
        "schema", "type", "clientId", "clientRole", "round", "sampleCount", "poseCounts",
        "sensorConfig", "preprocessingVersion", "featureDim", "classes", "bias", "weights",
        "createdAtEpochMs", "contributors", "testAccuracy",
    )
    val RESULT_KEYS = setOf(
        "schema", "type", "clientId", "clientRole", "modelKind", "round", "sensorConfig",
        "evalCount", "accuracy", "macroF1", "perClass", "classes", "confusion", "sessionTags",
        "createdAtEpochMs",
    )
    val PER_CLASS_KEYS = setOf("pose", "accuracy", "f1", "support")

    /** Keys that identify raw sensor data or recorded trials; never allowed in an upload. */
    val FORBIDDEN_KEYS = setOf(
        "samples", "sample", "trial", "trials", "timestampns",
        "accel", "gyro", "gravity", "rotationvector", "features", "examples",
    )

    fun check(obj: JSONObject, allowed: Set<String>) {
        val keys = obj.keys().asSequence().toList()
        val unexpected = keys.filter { it !in allowed }
        require(unexpected.isEmpty()) { "Payload has non-allowlisted keys: $unexpected" }
        val forbidden = keys.filter { it.lowercase() in FORBIDDEN_KEYS }
        require(forbidden.isEmpty()) { "Payload has keys that look like raw sensor data: $forbidden" }
    }
}

object PayloadCodec {
    const val SCHEMA = 1

    fun toJson(p: ModelPayload): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("type", p.type.id)
        put("clientId", p.clientId)
        put("clientRole", p.clientRole)
        put("round", p.round)
        put("sampleCount", p.sampleCount)
        put("poseCounts", JSONObject().also { o -> p.poseCounts.forEach { (k, v) -> o.put(k, v) } })
        put("sensorConfig", p.sensorConfig.id)
        put("preprocessingVersion", p.preprocessingVersion)
        put("createdAtEpochMs", p.createdAtEpochMs)
        put("contributors", JSONArray(p.contributors))
        p.testAccuracy?.let { put("testAccuracy", it.toDouble()) }
        ModelCodec.run { putModel(p.model) }
        PrivacyGuard.check(this, PrivacyGuard.MODEL_KEYS)
    }

    fun modelFromJson(obj: JSONObject): ModelPayload {
        val counts = buildMap {
            val c = obj.optJSONObject("poseCounts")
            c?.keys()?.forEach { k -> put(k, c.getInt(k)) }
        }
        return ModelPayload(
            type = PayloadType.fromId(obj.getString("type")),
            clientId = obj.getString("clientId"),
            clientRole = obj.optString("clientRole", ""),
            round = obj.getInt("round"),
            sampleCount = obj.getInt("sampleCount"),
            poseCounts = counts,
            sensorConfig = SensorConfig.fromId(obj.optString("sensorConfig", SensorConfig.ALL.id)),
            preprocessingVersion = obj.optInt("preprocessingVersion", 1),
            model = ModelCodec.modelFromJson(obj),
            createdAtEpochMs = obj.optLong("createdAtEpochMs", 0L),
            contributors = ModelCodec.stringList(obj.optJSONArray("contributors")),
            testAccuracy = if (obj.has("testAccuracy") && !obj.isNull("testAccuracy")) {
                obj.getDouble("testAccuracy").toFloat()
            } else {
                null
            },
        )
    }

    fun toJson(r: ResultPayload): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("type", "result")
        put("clientId", r.clientId)
        put("clientRole", r.clientRole)
        put("modelKind", r.modelKind)
        put("round", r.round)
        put("sensorConfig", r.sensorConfig.id)
        put("evalCount", r.evalCount)
        put("accuracy", r.accuracy.toDouble())
        put("macroF1", r.macroF1.toDouble())
        put("classes", JSONArray(r.classes))
        put("sessionTags", JSONArray(r.sessionTags))
        put("createdAtEpochMs", r.createdAtEpochMs)
        put(
            "perClass",
            JSONArray().also { arr ->
                r.perClass.forEach { c ->
                    val o = JSONObject()
                        .put("pose", c.pose)
                        .put("accuracy", c.accuracy.toDouble())
                        .put("f1", c.f1.toDouble())
                        .put("support", c.support)
                    PrivacyGuard.check(o, PrivacyGuard.PER_CLASS_KEYS)
                    arr.put(o)
                }
            },
        )
        put("confusion", JSONArray().also { rows -> r.confusion.forEach { row -> rows.put(JSONArray(row.toList())) } })
        PrivacyGuard.check(this, PrivacyGuard.RESULT_KEYS)
    }
}

/** Updates in the same round must be mutually compatible or averaging is meaningless. */
object Compat {
    fun problem(reference: ModelPayload, other: ModelPayload): String? = when {
        reference.model.featureDim != other.model.featureDim ->
            "featureDim ${other.model.featureDim} != ${reference.model.featureDim}"
        reference.model.classes != other.model.classes -> "class list differs"
        reference.sensorConfig != other.sensorConfig ->
            "sensorConfig ${other.sensorConfig.id} != ${reference.sensorConfig.id}"
        reference.preprocessingVersion != other.preprocessingVersion ->
            "preprocessingVersion ${other.preprocessingVersion} != ${reference.preprocessingVersion}"
        else -> null
    }
}
