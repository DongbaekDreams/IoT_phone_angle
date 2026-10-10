package edu.iot.phoneangle

import android.content.Context
import edu.iot.phoneangle.data.ClientRole

/** How this phone reaches the federation. */
enum class TransportMode(val label: String) {
    GIT("Git"),
    LAN("LAN"),
}

/** Which experiment the Collect / Model / Results tabs are aimed at. */
enum class TaskKind(val label: String) {
    POSE("Poses"),
    TYPED_WORD("Typed words");

    companion object {
        fun fromId(id: String): TaskKind =
            entries.find { it.name == id } ?: POSE
    }
}

/**
 * App-private prefs: nationality + Class/Personal identity, transport, room tag.
 * The GitHub token lives here (never in the repo); backups are disabled in the manifest.
 */
class ProjectSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 🇨🇦 / 🇵🇪 nationality label for this device (who owns the phone). Saved on-device.
     * Not a room/location — that is [sessionTag].
     */
    var nationality: Nationality
        get() {
            val stored = prefs.getString(KEY_NATIONALITY, null)?.let { Nationality.fromId(it) }
            if (stored != null) return stored
            // Migrate from older client ids / default to Canada.
            val fromId = PhoneIdentity.fromLegacyClientId(prefs.getString(KEY_CLIENT_ID, "") ?: "")
            return fromId?.first ?: Nationality.CANADA
        }
        set(value) {
            prefs.edit().putString(KEY_NATIONALITY, value.id).apply()
            syncClientId()
        }

    /** Class (= Client A) or Personal (= Client B). Saved on-device. */
    var clientRole: ClientRole
        get() = prefs.getString(KEY_CLIENT_ROLE, ClientRole.CLIENT_A.name)
            ?.let { runCatching { ClientRole.valueOf(it) }.getOrNull() }
            ?: ClientRole.CLIENT_A
        set(value) {
            prefs.edit().putString(KEY_CLIENT_ROLE, value.name).apply()
            syncClientId()
        }

    /**
     * Stable id used in federation file names. Derived from nationality + role, e.g. `canada-class`.
     * Writable for Dev tools overrides; prefer setting [nationality] / [clientRole].
     */
    var clientId: String
        get() {
            val derived = PhoneIdentity.clientId(nationality, clientRole)
            val stored = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() && it !in LEGACY_IDS }
            if (stored == null || PhoneIdentity.fromLegacyClientId(stored) != null) {
                if (stored != derived) prefs.edit().putString(KEY_CLIENT_ID, derived).apply()
                return derived
            }
            return stored
        }
        set(value) {
            val clean = sanitizeId(value)
            prefs.edit().putString(KEY_CLIENT_ID, clean.ifBlank {
                PhoneIdentity.clientId(nationality, clientRole)
            }).apply()
        }

    fun displayLabel(): String = PhoneIdentity.displayLabel(nationality, clientRole)

    /** Set both identity toggles and refresh the derived client id. Does not touch the room tag. */
    fun applyIdentity(nationality: Nationality, role: ClientRole) {
        prefs.edit()
            .putString(KEY_NATIONALITY, nationality.id)
            .putString(KEY_CLIENT_ROLE, role.name)
            .putString(KEY_CLIENT_ID, PhoneIdentity.clientId(nationality, role))
            .apply()
    }

    private fun syncClientId() {
        prefs.edit().putString(KEY_CLIENT_ID, PhoneIdentity.clientId(nationality, clientRole)).apply()
    }

    var transportMode: TransportMode
        get() = prefs.getString(KEY_MODE, TransportMode.GIT.name)
            ?.let { runCatching { TransportMode.valueOf(it) }.getOrNull() }
            ?: TransportMode.GIT
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    var serverBaseUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value.trim().trimEnd('/')).apply()
        }

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

    /** Room / session label stamped onto newly recorded trials (independent of nationality). */
    var sessionTag: String
        get() = prefs.getString(KEY_SESSION_TAG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SESSION_TAG, value.trim()).apply()
        }

    /** Active experiment for Collect / Model / Results. Typed-word ML is not ready yet. */
    var taskKind: TaskKind
        get() = TaskKind.fromId(prefs.getString(KEY_TASK, TaskKind.POSE.name) ?: TaskKind.POSE.name)
        set(value) {
            prefs.edit().putString(KEY_TASK, value.name).apply()
        }

    companion object {
        private const val PREFS = "project_settings"
        private const val KEY_NATIONALITY = "nationality"
        private const val KEY_CLIENT_ROLE = "client_role"
        private const val KEY_CLIENT_ID = "client_id_v2"
        private const val KEY_MODE = "transport_mode"
        private const val KEY_SERVER_URL = "server_base_url"
        private const val KEY_GH_REPO = "github_repo"
        private const val KEY_GH_BRANCH = "github_branch"
        private const val KEY_GH_TOKEN = "github_token"
        private const val KEY_SESSION_TAG = "session_tag"
        private const val KEY_TASK = "task_kind"

        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080"
        const val DEFAULT_GITHUB_REPO = "DongbaekDreams/IoT_phone_angle"

        private val LEGACY_IDS = setOf("client-a", "client-b")

        fun defaultClientId(role: ClientRole): String =
            PhoneIdentity.clientId(Nationality.CANADA, role)

        fun sanitizeId(raw: String): String =
            raw.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
    }
}
