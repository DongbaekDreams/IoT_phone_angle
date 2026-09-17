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

    fun countByPose(clientRole: ClientRole? = null): Map<PhonePose, Int> {
        val counts = PhonePose.entries.associateWith { 0 }.toMutableMap()
        loadAll(TrialTask.POSE).forEach { trial ->
            if (clientRole != null && trial.clientRole != clientRole) return@forEach
            val pose = PhonePose.fromShortId(trial.label) ?: return@forEach
            counts[pose] = (counts[pose] ?: 0) + 1
        }
        return counts
    }

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
    )

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
