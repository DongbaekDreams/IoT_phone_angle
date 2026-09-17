package edu.iot.phoneangle.ml

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Persists local/global model weights + preprocessing metadata on-device.
 */
class ModelStore(context: Context) {

    private val rootDir = File(context.filesDir, "models").also { it.mkdirs() }

    fun saveLocalPose(bundle: PoseModelBundle) {
        File(rootDir, LOCAL_POSE_FILE).writeText(ModelCodec.bundleToJson(bundle).toString(2))
    }

    fun loadLocalPose(): PoseModelBundle? = load(LOCAL_POSE_FILE)

    fun saveGlobalPose(bundle: PoseModelBundle) {
        File(rootDir, GLOBAL_POSE_FILE).writeText(ModelCodec.bundleToJson(bundle).toString(2))
    }

    fun loadGlobalPose(): PoseModelBundle? = load(GLOBAL_POSE_FILE)

    fun savePeerPose(bundle: PoseModelBundle) {
        File(rootDir, PEER_POSE_FILE).writeText(ModelCodec.bundleToJson(bundle).toString(2))
    }

    fun loadPeerPose(): PoseModelBundle? = load(PEER_POSE_FILE)

    fun hasLocalPose(): Boolean = File(rootDir, LOCAL_POSE_FILE).exists()

    fun deleteLocalPose() {
        File(rootDir, LOCAL_POSE_FILE).delete()
    }

    private fun load(name: String): PoseModelBundle? {
        val file = File(rootDir, name)
        if (!file.exists()) return null
        return runCatching { ModelCodec.bundleFromJson(JSONObject(file.readText())) }.getOrNull()
    }

    companion object {
        const val LOCAL_POSE_FILE = "local_pose_model.json"
        const val GLOBAL_POSE_FILE = "global_pose_model.json"
        const val PEER_POSE_FILE = "peer_pose_model.json"
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
)
