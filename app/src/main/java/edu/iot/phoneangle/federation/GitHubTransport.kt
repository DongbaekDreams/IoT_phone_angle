package edu.iot.phoneangle.federation

import edu.iot.phoneangle.BindingCodec
import edu.iot.phoneangle.SlotBinding
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/**
 * Asynchronous federation through a GitHub repo (Contents API). Phones do not need to be online
 * at the same time or on the same network.
 *
 * Layout (small JSON files, weights/metrics only):
 *   federation/latest.json                       {"round": N}
 *   federation/rounds/NNNN/updates/<client>.json  one file per client per round
 *   federation/rounds/NNNN/global.json            aggregate of that round
 *   results/baselines/<client>-local.json         independent local-only models
 *   results/<client>/<timestamp>-<kind>.json      evaluation metrics
 *
 * Each client writes only its own paths, so concurrent clients do not conflict.
 */
class GitHubTransport(
    private val repo: String,
    private val branch: String,
    private val token: String,
    /** Overridable so tests can point at a fake server. */
    private val apiBase: String = "https://api.github.com",
) : FederationTransport {

    override val label: String get() = "GitHub ($repo@$branch)"

    private val api = "$apiBase/repos/$repo/contents"

    override fun ping(): String {
        requireConfigured()
        val r = Http.request("GET", "$apiBase/repos/$repo", headers())
        when {
            r.code == 401 -> throw TransportException("GitHub rejected the token (401). Check it in Dev tools.")
            r.code == 404 -> throw TransportException("Repo $repo not found, or the token cannot see it.")
            !r.ok -> throw TransportException("GitHub error ${r.code}: ${apiMessage(r)}")
        }
        // permissions.push can be true for the account while a fine-grained token still has
        // Contents: read-only — prove Contents write by touching a tiny probe file.
        try {
            putFile(
                path = "federation/.write-probe.json",
                content = JSONObject()
                    .put("ok", true)
                    .put("at", System.currentTimeMillis())
                    .toString(2),
                message = "federation write probe",
            )
        } catch (e: TransportException) {
            throw TransportException(
                "Token can see $repo@$branch but cannot write files. " +
                    "Recreate the fine-grained token with Contents: Read and write " +
                    "(not read-only), for this exact repo. Detail: ${e.message}",
                e,
            )
        }
        val s = status()
        return "Connected to $repo@$branch (write access). Latest global round ${s.latestGlobalRound}; " +
            "${s.pendingClients.size} update(s) waiting for round ${s.pendingRound}."
    }

    override fun status(): RemoteStatus {
        requireConfigured()
        val latest = latestRound()
        val pending = listNames(updatesDir(latest + 1))
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .sorted()
        return RemoteStatus(latest, latest + 1, pending)
    }

    override fun fetchGlobal(): ModelPayload? {
        requireConfigured()
        val latest = latestRound()
        if (latest == 0) return null
        val text = getFile(globalPath(latest))?.text ?: return null
        return PayloadCodec.modelFromJson(JSONObject(text))
    }

    override fun submitUpdate(update: ModelPayload) {
        requireConfigured()
        val pending = latestRound() + 1
        if (update.round != pending) {
            throw TransportException(
                "Update is for round ${update.round} but the repo is waiting for round $pending. " +
                    "Fetch the latest global and train again.",
            )
        }
        putFile(
            path = "${updatesDir(pending)}/${update.clientId}.json",
            content = PayloadCodec.toJson(update).toString(2),
            message = "round $pending update from ${update.clientId}",
        )
    }

    override fun aggregate(): ModelPayload {
        requireConfigured()
        val round = latestRound() + 1

        // Another client may already have aggregated this round.
        getFile(globalPath(round))?.let { existing ->
            val global = PayloadCodec.modelFromJson(JSONObject(existing.text))
            writeLatest(round)
            return global
        }

        val names = listNames(updatesDir(round)).filter { it.endsWith(".json") }
        if (names.isEmpty()) {
            val hint = if (round > 1) " (round ${round - 1} is already aggregated)" else ""
            throw TransportException("No updates for round $round yet$hint.")
        }
        val updates = names.map { name ->
            val text = getFile("${updatesDir(round)}/$name")?.text
                ?: throw TransportException("Could not read $name")
            PayloadCodec.modelFromJson(JSONObject(text))
        }
        val global = try {
            Aggregator.aggregate(round, updates)
        } catch (e: IllegalArgumentException) {
            throw TransportException(e.message ?: "Aggregation failed", e)
        }
        putFile(globalPath(round), PayloadCodec.toJson(global).toString(2), "round $round global (${updates.size} clients)")
        writeLatest(round)
        return global
    }

    override fun putBaseline(baseline: ModelPayload) {
        requireConfigured()
        putFile(
            path = "results/baselines/${baseline.clientId}-local.json",
            content = PayloadCodec.toJson(baseline).toString(2),
            message = "baseline from ${baseline.clientId}",
        )
    }

    override fun fetchBaselines(): List<ModelPayload> {
        requireConfigured()
        return listNames("results/baselines")
            .filter { it.endsWith("-local.json") }
            .mapNotNull { name ->
                getFile("results/baselines/$name")?.text?.let { PayloadCodec.modelFromJson(JSONObject(it)) }
            }
    }

    override fun putResult(result: ResultPayload) {
        requireConfigured()
        val stamp = result.createdAtEpochMs
        putFile(
            path = "results/${result.clientId}/$stamp-${result.modelKind}.json",
            content = PayloadCodec.toJson(result).toString(2),
            message = "${result.modelKind} results from ${result.clientId}",
        )
    }

    override fun fetchBindings(): List<SlotBinding> {
        requireConfigured()
        return listNames("results/bindings")
            .filter { it.endsWith(".json") }
            .mapNotNull { name ->
                getFile("results/bindings/$name")?.text?.let { BindingCodec.fromJson(JSONObject(it)) }
            }
    }

    override fun putBinding(binding: SlotBinding) {
        requireConfigured()
        val path = "results/bindings/${binding.clientId}.json"
        getFile(path)?.text?.let { text ->
            val existing = BindingCodec.fromJson(JSONObject(text))
            if (existing.deviceKey != binding.deviceKey) {
                throw TransportException("${binding.slot.displayName} is already filled by another phone.")
            }
        }
        putFile(path, BindingCodec.toJson(binding).toString(2), "bind ${binding.clientId}")
    }

    // --- repo layout -------------------------------------------------------------------

    private fun roundDir(round: Int) = "federation/rounds/%04d".format(round)
    private fun updatesDir(round: Int) = "${roundDir(round)}/updates"
    private fun globalPath(round: Int) = "${roundDir(round)}/global.json"

    private fun latestRound(): Int {
        val text = getFile("federation/latest.json")?.text ?: return 0
        return JSONObject(text).optInt("round", 0)
    }

    private fun writeLatest(round: Int) {
        val body = JSONObject().put("round", round).put("updatedAtEpochMs", System.currentTimeMillis())
        putFile("federation/latest.json", body.toString(2), "latest global round = $round")
    }

    // --- Contents API ------------------------------------------------------------------

    private class RemoteFile(val sha: String, val text: String)

    private fun requireConfigured() {
        if (token.isBlank()) throw TransportException("No GitHub token set. Add one in Dev tools.")
        if (!repo.contains('/')) throw TransportException("GitHub repo must look like owner/repo.")
    }

    private fun headers() = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
        "User-Agent" to "phone-angle-fl",
    )

    private fun url(path: String) =
        "$api/${path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }}"

    private fun getFile(path: String): RemoteFile? {
        val r = Http.request("GET", "${url(path)}?ref=${URLEncoder.encode(branch, "UTF-8")}", headers())
        if (r.code == 404) return null
        failIfBad(r, "read $path")
        val json = JSONObject(r.body)
        if (json.has("content")) {
            val bytes = Base64.getMimeDecoder().decode(json.getString("content"))
            return RemoteFile(json.getString("sha"), String(bytes, Charsets.UTF_8))
        }
        throw TransportException("$path is not a file")
    }

    private fun listNames(dir: String): List<String> {
        val r = Http.request("GET", "${url(dir)}?ref=${URLEncoder.encode(branch, "UTF-8")}", headers())
        if (r.code == 404) return emptyList()
        failIfBad(r, "list $dir")
        val arr = runCatching { JSONArray(r.body) }.getOrNull() ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it) }
            .filter { it.optString("type") == "file" }
            .map { it.getString("name") }
    }

    private fun putFile(path: String, content: String, message: String) {
        var attempts = 0
        while (true) {
            attempts++
            val existingSha = getFile(path)?.sha
            val body = JSONObject()
                .put("message", message)
                .put("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
                .put("branch", branch)
            if (existingSha != null) body.put("sha", existingSha)
            val r = Http.request("PUT", url(path), headers(), body.toString())
            if (r.ok) return
            // Only retry sha races. Other 422s (bad branch, validation) are permanent.
            val msg = apiMessage(r).lowercase()
            val shaRace = (r.code == 409 || r.code == 422) &&
                (msg.contains("sha") || msg.contains("conflict"))
            if (shaRace && attempts < 3) continue
            failIfBad(r, "write $path")
            return
        }
    }

    private fun failIfBad(r: HttpResponse, what: String) {
        if (r.ok) return
        val hint = when (r.code) {
            401 -> " (bad token)"
            403 -> " (token lacks Contents: write, or rate limited)"
            404 -> " (repo/branch '$branch' not found or no access)"
            422 -> " (validation failed — often wrong branch '$branch', or repo rules block API commits)"
            else -> ""
        }
        throw TransportException("GitHub: could not $what - ${r.code}$hint: ${apiMessage(r)}")
    }

    private fun apiMessage(r: HttpResponse): String =
        runCatching { JSONObject(r.body).optString("message", r.body) }.getOrDefault(r.body).take(200)
}
