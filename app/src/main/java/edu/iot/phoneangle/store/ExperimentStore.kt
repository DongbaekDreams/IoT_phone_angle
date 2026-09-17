package edu.iot.phoneangle.store

import android.content.Context
import edu.iot.phoneangle.ml.EvaluationReport
import edu.iot.phoneangle.ml.SensorConfig
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Saves repeatable local experiment metadata (ablations, metrics).
 */
class ExperimentStore(context: Context) {

    private val rootDir = File(context.filesDir, "experiments").also { it.mkdirs() }

    fun save(record: ExperimentRecord): File {
        val file = File(rootDir, "${record.id}.json")
        file.writeText(toJson(record).toString(2))
        return file
    }

    fun loadAll(): List<ExperimentRecord> =
        rootDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedByDescending { it.createdAtEpochMs }
            .orEmpty()

    fun deleteAll() {
        rootDir.listFiles()?.forEach { it.delete() }
    }

    private fun toJson(r: ExperimentRecord): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("createdAtEpochMs", r.createdAtEpochMs)
        put("sensorConfig", r.sensorConfig.id)
        put("trainCount", r.trainCount)
        put("testCount", r.testCount)
        put("trainAccuracy", r.trainAccuracy.toDouble())
        put("testAccuracy", r.testAccuracy.toDouble())
        put("macroF1", r.macroF1.toDouble())
        put("epochs", r.epochs)
        put("notes", r.notes)
        put("perClassSummary", r.perClassSummary)
        put("confusionText", r.confusionText)
    }

    private fun fromJson(obj: JSONObject): ExperimentRecord = ExperimentRecord(
        id = obj.getString("id"),
        createdAtEpochMs = obj.getLong("createdAtEpochMs"),
        sensorConfig = SensorConfig.fromId(obj.getString("sensorConfig")),
        trainCount = obj.getInt("trainCount"),
        testCount = obj.getInt("testCount"),
        trainAccuracy = obj.getDouble("trainAccuracy").toFloat(),
        testAccuracy = obj.getDouble("testAccuracy").toFloat(),
        macroF1 = obj.getDouble("macroF1").toFloat(),
        epochs = obj.getInt("epochs"),
        notes = obj.optString("notes", ""),
        perClassSummary = obj.optString("perClassSummary", ""),
        confusionText = obj.optString("confusionText", ""),
    )

    companion object {
        fun fromEval(
            sensorConfig: SensorConfig,
            trainCount: Int,
            testCount: Int,
            trainAccuracy: Float,
            report: EvaluationReport,
            epochs: Int,
            notes: String = "",
        ): ExperimentRecord = ExperimentRecord(
            id = UUID.randomUUID().toString(),
            createdAtEpochMs = System.currentTimeMillis(),
            sensorConfig = sensorConfig,
            trainCount = trainCount,
            testCount = testCount,
            trainAccuracy = trainAccuracy,
            testAccuracy = report.accuracy,
            macroF1 = report.macroF1,
            epochs = epochs,
            notes = notes,
            perClassSummary = report.perClass.joinToString("\n") {
                "${it.pose.shortId}: F1=${"%.2f".format(it.f1)} n=${it.support}"
            },
            confusionText = report.formatConfusion(),
        )
    }
}

data class ExperimentRecord(
    val id: String,
    val createdAtEpochMs: Long,
    val sensorConfig: SensorConfig,
    val trainCount: Int,
    val testCount: Int,
    val trainAccuracy: Float,
    val testAccuracy: Float,
    val macroF1: Float,
    val epochs: Int,
    val notes: String = "",
    val perClassSummary: String = "",
    val confusionText: String = "",
) {
    fun titleLine(): String {
        val whenStr = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(createdAtEpochMs))
        return "$whenStr · ${sensorConfig.displayName} · test=${"%.0f".format(testAccuracy * 100)}%"
    }
}
