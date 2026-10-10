package edu.iot.phoneangle.store

import android.content.Context
import edu.iot.phoneangle.data.ClientRole
import edu.iot.phoneangle.data.FloatArray3
import edu.iot.phoneangle.data.FloatArray4
import edu.iot.phoneangle.data.PhonePose
import edu.iot.phoneangle.data.SensorSample
import edu.iot.phoneangle.data.Trial
import edu.iot.phoneangle.data.TrialTask
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device trial store. Raw samples never leave the phone.
 */
class LocalDatasetStore(context: Context) {

    private val rootDir = File(context.filesDir, "trials").also { it.mkdirs() }

    fun save(trial: Trial): File {
        val file = File(rootDir, "${trial.id}.json")
        file.writeText(trialToJson(trial).toString(2))
        return file
    }

    fun loadAll(task: TrialTask? = null): List<Trial> {
        return rootDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { trialFromJson(JSONObject(file.readText())) }.getOrNull()
            }
            ?.filter { task == null || it.task == task }
            ?.sortedBy { it.startedAtEpochMs }
            .orEmpty()
    }

    /**
     * Fast label/task counts without parsing sample arrays (trials store samples last).
     * Used by Home / Collect status so tab switches stay snappy.
     */
    fun countByLabel(task: TrialTask): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        val files = rootDir.listFiles { f -> f.extension == "json" } ?: return emptyMap()
        val buf = ByteArray(1024)
        for (file in files) {
            val head = runCatching {
                file.inputStream().use { ins ->
                    val n = ins.read(buf)
                    if (n <= 0) return@use ""
                    String(buf, 0, n)
                }
            }.getOrNull() ?: continue
            val taskId = HEAD_TASK.find(head)?.groupValues?.getOrNull(1) ?: continue
            if (taskId != task.id) continue
            val label = HEAD_LABEL.find(head)?.groupValues?.getOrNull(1) ?: continue
            counts[label] = (counts[label] ?: 0) + 1
        }
        return counts
    }

    fun countByPose(clientRole: ClientRole? = null): Map<PhonePose, Int> {
        val counts = PhonePose.entries.associateWith { 0 }.toMutableMap()
        val files = rootDir.listFiles { f -> f.extension == "json" } ?: return counts
        val buf = ByteArray(1024)
        for (file in files) {
            val head = runCatching {
                file.inputStream().use { ins ->
                    val n = ins.read(buf)
                    if (n <= 0) return@use ""
                    String(buf, 0, n)
                }
            }.getOrNull() ?: continue
            val taskId = HEAD_TASK.find(head)?.groupValues?.getOrNull(1) ?: continue
            if (taskId != TrialTask.POSE.id) continue
            if (clientRole != null) {
                val role = HEAD_ROLE.find(head)?.groupValues?.getOrNull(1) ?: continue
                if (role != clientRole.name) continue
            }
            val label = HEAD_LABEL.find(head)?.groupValues?.getOrNull(1) ?: continue
            val pose = PhonePose.fromShortId(label) ?: continue
            counts[pose] = (counts[pose] ?: 0) + 1
        }
        return counts
    }

    /** Distinct non-empty session tags among pose trials, sorted. */
    fun sessionTags(task: TrialTask = TrialTask.POSE): List<String> =
        loadAll(task).map { it.sessionTag }.filter { it.isNotBlank() }.distinct().sorted()

    fun deleteAll() {
        rootDir.listFiles()?.forEach { it.delete() }
    }

    private fun trialToJson(trial: Trial): JSONObject = JSONObject().apply {
        put("id", trial.id)
        put("label", trial.label)
        put("task", trial.task.id)
        put("clientRole", trial.clientRole.name)
        put("startedAtEpochMs", trial.startedAtEpochMs)
        put("endedAtEpochMs", trial.endedAtEpochMs)
        put("notes", trial.notes)
        put("sessionTag", trial.sessionTag)
        put("samples", JSONArray().also { arr ->
            trial.samples.forEach { sample -> arr.put(sampleToJson(sample)) }
        })
    }

    private fun sampleToJson(sample: SensorSample): JSONObject = JSONObject().apply {
        put("timestampNs", sample.timestampNs)
        sample.accel?.let { put("accel", JSONArray(it.toList())) }
        sample.gyro?.let { put("gyro", JSONArray(it.toList())) }
        sample.gravity?.let { put("gravity", JSONArray(it.toList())) }
        sample.rotationVector?.let { put("rotationVector", JSONArray(it.toList())) }
    }

    private fun trialFromJson(obj: JSONObject): Trial = Trial(
        id = obj.getString("id"),
        label = obj.getString("label"),
        task = TrialTask.fromId(obj.getString("task")),
        clientRole = ClientRole.valueOf(obj.getString("clientRole")),
        startedAtEpochMs = obj.getLong("startedAtEpochMs"),
        endedAtEpochMs = obj.getLong("endedAtEpochMs"),
        samples = obj.getJSONArray("samples").let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    add(sampleFromJson(arr.getJSONObject(i)))
                }
            }
        },
        notes = obj.optString("notes", ""),
        sessionTag = obj.optString("sessionTag", ""),
    )

    companion object {
        private val HEAD_TASK = Regex("\"task\"\\s*:\\s*\"([^\"]+)\"")
        private val HEAD_LABEL = Regex("\"label\"\\s*:\\s*\"([^\"]+)\"")
        private val HEAD_ROLE = Regex("\"clientRole\"\\s*:\\s*\"([^\"]+)\"")
    }

    private fun sampleFromJson(obj: JSONObject): SensorSample = SensorSample(
        timestampNs = obj.getLong("timestampNs"),
        accel = obj.optJSONArray("accel")?.let { FloatArray3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) },
        gyro = obj.optJSONArray("gyro")?.let { FloatArray3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) },
        gravity = obj.optJSONArray("gravity")?.let { FloatArray3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) },
        rotationVector = obj.optJSONArray("rotationVector")?.let {
            FloatArray4(
                it.getDouble(0).toFloat(),
                it.getDouble(1).toFloat(),
                it.getDouble(2).toFloat(),
                if (it.length() > 3) it.getDouble(3).toFloat() else 0f,
            )
        },
    )
}
