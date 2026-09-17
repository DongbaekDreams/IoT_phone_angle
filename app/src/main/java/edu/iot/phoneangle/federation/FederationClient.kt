package edu.iot.phoneangle.federation

import edu.iot.phoneangle.ml.ModelCodec
import edu.iot.phoneangle.ml.SensorConfig
import edu.iot.phoneangle.ml.SoftmaxClassifier
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the FedAvg server. Sends model updates only — never raw sensor trials.
 */
class FederationClient(
    private val baseUrl: String,
) {
    fun health(): String {
        val conn = open("GET", "/health")
        return readBody(conn).also { conn.disconnect() }
    }

    fun fetchGlobal(): SoftmaxClassifier? {
        val conn = open("GET", "/global")
        val code = conn.responseCode
        val body = readBody(conn)
        conn.disconnect()
        if (code == 404) return null
        if (code !in 200..299) error("GET /global failed ($code): $body")
        val obj = JSONObject(body)
        if (obj.optBoolean("empty", false)) return null
        return ModelCodec.modelFromUpdateJson(obj)
    }

    fun submitUpdate(
        clientId: String,
        sampleCount: Int,
        model: SoftmaxClassifier,
        sensorConfig: SensorConfig,
    ): SubmitResult {
        val payload = ModelCodec.updateToJson(clientId, sampleCount, model, sensorConfig)
        val conn = open("POST", "/update")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
        val code = conn.responseCode
        val body = readBody(conn)
        conn.disconnect()
        if (code !in 200..299) error("POST /update failed ($code): $body")
        val obj = JSONObject(body)
        return SubmitResult(
            pendingClients = obj.optInt("pendingClients", 0),
            message = obj.optString("message", "ok"),
        )
    }

    fun aggregate(): SoftmaxClassifier {
        val conn = open("POST", "/aggregate")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(conn.outputStream).use { it.write("{}") }
        val code = conn.responseCode
        val body = readBody(conn)
        conn.disconnect()
        if (code !in 200..299) error("POST /aggregate failed ($code): $body")
        return ModelCodec.modelFromUpdateJson(JSONObject(body))
    }

    fun reset() {
        val conn = open("POST", "/reset")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write("{}") }
        conn.responseCode
        conn.disconnect()
    }

    private fun open(method: String, path: String): HttpURLConnection {
        val url = URL(baseUrl.trimEnd('/') + path)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 15_000
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            conn.inputStream
        } catch (_: Exception) {
            conn.errorStream
        } ?: return ""
        return BufferedReader(InputStreamReader(stream)).use { it.readText() }
    }

    data class SubmitResult(val pendingClients: Int, val message: String)
}
