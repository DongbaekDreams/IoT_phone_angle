package edu.iot.phoneangle.federation

import edu.iot.phoneangle.ProjectSettings
import edu.iot.phoneangle.TransportMode
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What the remote side currently looks like. */
data class RemoteStatus(
    /** Latest aggregated round (0 = no global model yet). */
    val latestGlobalRound: Int,
    /** The round clients are currently submitting updates for (latest + 1). */
    val pendingRound: Int,
    /** Client ids that have already submitted an update for [pendingRound]. */
    val pendingClients: List<String>,
)

/**
 * One federation protocol, two carriers (laptop server on the LAN, or a GitHub repo).
 * Implementations are blocking; call from a background thread.
 */
interface FederationTransport {
    val label: String

    /** Verify connectivity/credentials. Returns a short human-readable summary. */
    fun ping(): String

    fun status(): RemoteStatus

    /** Latest aggregated global model, or null if none exists yet. */
    fun fetchGlobal(): ModelPayload?

    /** Submit this client's round-local model. [ModelPayload.round] must equal `pendingRound`. */
    fun submitUpdate(update: ModelPayload)

    /** Aggregate all updates for the pending round into a new global model. Idempotent. */
    fun aggregate(): ModelPayload

    fun putBaseline(baseline: ModelPayload)

    fun fetchBaselines(): List<ModelPayload>

    fun putResult(result: ResultPayload)

    companion object {
        fun create(settings: ProjectSettings): FederationTransport = when (settings.transportMode) {
            TransportMode.GIT -> GitHubTransport(
                repo = settings.githubRepo,
                branch = settings.githubBranch,
                token = settings.githubToken,
            )
            TransportMode.LAN -> LanServerTransport(settings.serverBaseUrl)
        }
    }
}

internal class HttpResponse(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

internal object Http {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): HttpResponse {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 25_000
                useCaches = false
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
        } catch (e: Exception) {
            throw TransportException("Bad URL: $url", e)
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } }.orEmpty()
            return HttpResponse(code, text)
        } catch (e: java.io.IOException) {
            throw TransportException("Network error: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            conn.disconnect()
        }
    }
}
