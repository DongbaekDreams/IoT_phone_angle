package edu.iot.phoneangle

import android.content.Context
import edu.iot.phoneangle.data.ClientRole

/** Shared prefs for FL client identity and server endpoint. */
class ProjectSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var clientRole: ClientRole
        get() = prefs.getString(KEY_CLIENT_ROLE, ClientRole.CLIENT_A.name)
            ?.let { runCatching { ClientRole.valueOf(it) }.getOrDefault(ClientRole.CLIENT_A) }
            ?: ClientRole.CLIENT_A
        set(value) {
            prefs.edit().putString(KEY_CLIENT_ROLE, value.name).apply()
        }

    /** Base URL for the FedAvg server, e.g. http://192.168.1.10:8080 */
    var serverBaseUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value.trim().trimEnd('/')).apply()
        }

    var clientId: String
        get() {
            val existing = prefs.getString(KEY_CLIENT_ID, null)
            if (existing != null) return existing
            val id = "phone-${clientRole.name.lowercase()}-${System.currentTimeMillis() % 100000}"
            prefs.edit().putString(KEY_CLIENT_ID, id).apply()
            return id
        }
        set(value) {
            prefs.edit().putString(KEY_CLIENT_ID, value).apply()
        }

    companion object {
        private const val PREFS = "project_settings"
        private const val KEY_CLIENT_ROLE = "client_role"
        private const val KEY_SERVER_URL = "server_base_url"
        private const val KEY_CLIENT_ID = "client_id"
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080" // emulator → host loopback
    }
}
