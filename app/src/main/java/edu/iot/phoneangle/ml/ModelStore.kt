package edu.iot.phoneangle.ml

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Where a saved model lives. Slots are kept strictly separate so local-only baselines
 * are never contaminated by the federated global model.
 */
enum class ModelSlot(val fileName: String, val label: String) {
    /** Trained only on this phone's data, from scratch. Never touches the global model. */
    LOCAL_BASELINE("local_pose_model.json", "Local baseline"),

    /** Warm-started from the global model, trained on local data, uploaded as a round update. */
    ROUND_LOCAL("round_local_pose_model.json", "Round-local update"),

    /** Latest aggregated global model pulled from the transport. */
    GLOBAL("global_pose_model.json", "Federated global"),

    /** The other client's baseline, pulled from the transport (for comparison only). */
    PEER_BASELINE("peer_baseline_pose_model.json", "Peer baseline"),
}

/**
 * Persists local/global model weights + preprocessing metadata on-device.
 */
class ModelStore(context: Context) {

    private val rootDir = File(context.filesDir, "models").also { it.mkdirs() }

    fun save(slot: ModelSlot, bundle: PoseModelBundle) {
        File(rootDir, slot.fileName).writeText(ModelCodec.bundleToJson(bundle).toString(2))
    }

    fun load(slot: ModelSlot): PoseModelBundle? {
        val file = File(rootDir, slot.fileName)
        if (!file.exists()) return null
        return runCatching { ModelCodec.bundleFromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun has(slot: ModelSlot): Boolean = File(rootDir, slot.fileName).exists()

    fun delete(slot: ModelSlot) {
        File(rootDir, slot.fileName).delete()
    }

    fun clearAll() {
        ModelSlot.entries.forEach { delete(it) }
    }
}

data class PoseModelBundle(
    val version: Int = 1,
    val sensorConfig: SensorConfig,
    val model: SoftmaxClassifier,
    val trainedAtEpochMs: Long,
    val trainAccuracy: Float,
    val trainSampleCount: Int,
    val testAccuracy: Float? = null,
    val epochs: Int = 0,
    /** Federated round this model belongs to (0 = local-only baseline). */
    val round: Int = 0,
    /** Client that produced this model (peer baseline / global contributors use this). */
    val sourceClientId: String = "",
    val contributors: List<String> = emptyList(),
)
