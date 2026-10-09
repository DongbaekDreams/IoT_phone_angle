package edu.iot.phoneangle

import android.content.Context
import edu.iot.phoneangle.data.ClientRole

/** How this phone reaches the federation. */
enum class TransportMode(val label: String) {
    GIT("Git"),
    LAN("LAN"),
}

/**
 * App-private prefs: client identity, transport settings, collection session tag.
 * The GitHub token lives here (never in the repo); backups are disabled in the manifest.
 */
class ProjectSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var clientRole: ClientRole
        get() = prefs.getString(KEY_CLIENT_ROLE, ClientRole.CLIENT_A.name)
            ?.let { runCatching { ClientRole.valueOf(it) }.getOrNull() }
            ?: ClientRole.CLIENT_A
        set(value) {
            prefs.edit().putString(KEY_CLIENT_ROLE, value.name).apply()
        }

    /**
     * Stable, unique id used in file names/payloads. Several phones can share a role (A or B),
     * so the default is `client-a-xxxx` with a random suffix generated once per install.
     * The bare legacy defaults (`client-a`, `client-b`) are replaced because two phones would collide.
     */
    var clientId: String
        get() {
            val stored = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
            if (stored != null && stored !in LEGACY_IDS) return stored
            val fresh = defaultClientId(clientRole)
            prefs.edit().putString(KEY_CLIENT_ID, fresh).apply()
            return fresh
        }
        set(value) {
            val clean = sanitizeId(value)
            prefs.edit().putString(KEY_CLIENT_ID, clean.ifBlank { defaultClientId(clientRole) }).apply()
        }

    /** Human label for the top chip: preset flag name, or a plain fallback. */
    fun displayLabel(): String {
        val preset = PhonePreset.fromClientId(clientId)
        if (preset != null && clientId != preset.clientId) clientId = preset.clientId
        return preset?.displayName ?: "${clientRole.label} · $clientId"
    }

    /** Assign this phone a named slot (🇨🇦/🇵🇪 × Class/Personal). Also suggests the location tag. */
    fun applyPreset(preset: PhonePreset) {
        clientRole = preset.role
        clientId = preset.clientId
        if (sessionTag.isBlank() || sessionTag in PhonePreset.entries.map { it.locationTag }) {
            sessionTag = preset.locationTag
        }
    }

    /**
     * Switch role only. Named presets flip A↔B within the same country; auto-generated ids
     * keep their random suffix; custom ids are left alone.
     */
    fun switchRole(role: ClientRole) {
        val current = clientId
        val preset = PhonePreset.fromClientId(current)
        if (preset != null) {
            val match = PhonePreset.entries.find { it.country == preset.country && it.role == role }
            if (match != null) {
                applyPreset(match)
                return
            }
        }
        val auto = AUTO_ID.matchEntire(current)
        clientRole = role
        if (auto != null) clientId = "${rolePrefix(role)}-${auto.groupValues[2]}"
    }

    var transportMode: TransportMode
        get() = prefs.getString(KEY_MODE, TransportMode.GIT.name)
            ?.let { runCatching { TransportMode.valueOf(it) }.getOrNull() }
            ?: TransportMode.GIT
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    /** Base URL for the FedAvg server, e.g. http://192.168.1.10:8080 */
    var serverBaseUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value.trim().trimEnd('/')).apply()
        }

    /** `owner/repo` that stores federation/ and results/. */
    var githubRepo: String
        get() = prefs.getString(KEY_GH_REPO, DEFAULT_GITHUB_REPO) ?: DEFAULT_GITHUB_REPO
        set(value) {
            prefs.edit().putString(KEY_GH_REPO, value.trim().trim('/')).apply()
        }

    var githubBranch: String
        get() = prefs.getString(KEY_GH_BRANCH, "main") ?: "main"
        set(value) {
            prefs.edit().putString(KEY_GH_BRANCH, value.trim().ifBlank { "main" }).apply()
        }

    var githubToken: String
        get() = prefs.getString(KEY_GH_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_GH_TOKEN, value.trim()).apply()
        }

    /** Person/room label stamped onto newly recorded trials. */
    var sessionTag: String
        get() = prefs.getString(KEY_SESSION_TAG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SESSION_TAG, value.trim()).apply()
        }

    companion object {
        private const val PREFS = "project_settings"
        private const val KEY_CLIENT_ROLE = "client_role"
        private const val KEY_CLIENT_ID = "client_id_v2"
        private const val KEY_MODE = "transport_mode"
        private const val KEY_SERVER_URL = "server_base_url"
        private const val KEY_GH_REPO = "github_repo"
        private const val KEY_GH_BRANCH = "github_branch"
        private const val KEY_GH_TOKEN = "github_token"
        private const val KEY_SESSION_TAG = "session_tag"

        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080" // emulator -> host loopback
        const val DEFAULT_GITHUB_REPO = "DongbaekDreams/IoT_phone_angle"

        private val LEGACY_IDS = setOf("client-a", "client-b")
        private val AUTO_ID = Regex("^(client-[ab])-([a-z0-9]{4})$")

        private fun rolePrefix(role: ClientRole) = if (role == ClientRole.CLIENT_A) "client-a" else "client-b"

        /** Fresh unique id for [role], e.g. `client-a-x7k2`. */
        fun defaultClientId(role: ClientRole): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val suffix = (1..4).map { alphabet.random() }.joinToString("")
            return "${rolePrefix(role)}-$suffix"
        }

        fun sanitizeId(raw: String): String =
            raw.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
    }
}
