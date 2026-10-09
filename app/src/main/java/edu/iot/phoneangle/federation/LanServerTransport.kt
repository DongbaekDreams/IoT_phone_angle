package edu.iot.phoneangle.federation

import edu.iot.phoneangle.ml.ModelCodec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks to `server/fedavg_server.py` on the same Wi-Fi. The server aggregates;
 * clients only ever send weights, counts, and metrics.
 */
class LanServerTransport(baseUrl: String) : FederationTransport {

    private val base = baseUrl.trim().trimEnd('/')

    override val label: String get() = "LAN server ($base)"

    override fun ping(): String {
        val s = status()
        return "Server reachable. Latest global round ${s.latestGlobalRound}; " +
            "${s.pendingClients.size} update(s) waiting for round ${s.pendingRound}."
    }

    override fun status(): RemoteStatus {
        val obj = getJson("/status") ?: throw TransportException("Server returned no status")
        return RemoteStatus(
            latestGlobalRound = obj.getInt("latestGlobalRound"),
            pendingRound = obj.getInt("pendingRound"),
            pendingClients = ModelCodec.stringList(obj.optJSONArray("pendingClients")),
        )
    }

    override fun fetchGlobal(): ModelPayload? {
        val obj = getJson("/global") ?: return null
        if (obj.optBoolean("empty", false)) return null
        return PayloadCodec.modelFromJson(obj)
    }

    override fun submitUpdate(update: ModelPayload) {
        postJson("/update", PayloadCodec.toJson(update))
    }

    override fun aggregate(): ModelPayload {
        val obj = postJson("/aggregate", JSONObject())
        return PayloadCodec.modelFromJson(obj)
    }

    override fun putBaseline(baseline: ModelPayload) {
        postJson("/baseline", PayloadCodec.toJson(baseline))
    }

    override fun fetchBaselines(): List<ModelPayload> {
        val obj = getJson("/baselines") ?: return emptyList()
        val arr = obj.optJSONArray("baselines") ?: JSONArray()
        return List(arr.length()) { PayloadCodec.modelFromJson(arr.getJSONObject(it)) }
    }

    override fun putResult(result: ResultPayload) {
        postJson("/results", PayloadCodec.toJson(result))
    }

    private fun getJson(path: String): JSONObject? {
        val r = Http.request("GET", base + path)
        if (r.code == 404) return null
        if (!r.ok) throw TransportException("GET $path failed (${r.code}): ${errorText(r)}")
        return JSONObject(r.body)
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val r = Http.request("POST", base + path, body = body.toString())
        if (!r.ok) throw TransportException("POST $path failed (${r.code}): ${errorText(r)}")
        return if (r.body.isBlank()) JSONObject() else JSONObject(r.body)
    }

    private fun errorText(r: HttpResponse): String =
        runCatching { JSONObject(r.body).optString("error", r.body) }.getOrDefault(r.body)
}
