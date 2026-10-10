package edu.iot.phoneangle

import android.content.Context
import android.provider.Settings
import edu.iot.phoneangle.data.ClientRole
import edu.iot.phoneangle.federation.FederationTransport
import edu.iot.phoneangle.federation.TransportException
import org.json.JSONObject
import java.security.MessageDigest

/**
 * One of the four campaign slots a phone can claim. Claiming binds this device forever
 * (no manual override). Other phones can still claim the remaining empty slots.
 */
data class IdentitySlot(
    val nationality: Nationality,
    val role: ClientRole,
) {
    val clientId: String get() = PhoneIdentity.clientId(nationality, role)
    val displayName: String get() = PhoneIdentity.displayLabel(nationality, role)

    companion object {
        val ALL: List<IdentitySlot> = Nationality.entries.flatMap { nat ->
            ClientRole.entries.map { role -> IdentitySlot(nat, role) }
        }
    }
}

data class SlotBinding(
    val slot: IdentitySlot,
    val deviceKey: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    val clientId: String get() = slot.clientId
}

enum class SlotStatus {
    /** Nobody has claimed this slot yet (as far as we know). */
    EMPTY,
    /** This phone is locked to this slot. */
    MINE,
    /** Another phone already claimed it. */
    TAKEN,
}

/**
 * Device ↔ slot binding. Local lock is permanent. Optional sync through Git/LAN marks the
 * slot filled for the other phones.
 */
class IdentityRegistry(context: Context) {

    private val app = context.applicationContext
    private val settings = ProjectSettings(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Stable opaque key for this install (not the raw ANDROID_ID). */
    val deviceKey: String by lazy {
        prefs.getString(KEY_DEVICE, null)?.takeIf { it.isNotBlank() } ?: run {
            val androidId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
            val key = sha256("phone-angle|$androidId").take(16)
            prefs.edit().putString(KEY_DEVICE, key).apply()
            key
        }
    }

    val isLocked: Boolean get() = prefs.getBoolean(KEY_LOCKED, false)

    fun myBinding(): SlotBinding? {
        if (!isLocked) return null
        val nat = Nationality.fromId(prefs.getString(KEY_NAT, "") ?: "") ?: return null
        val role = prefs.getString(KEY_ROLE, null)?.let { runCatching { ClientRole.valueOf(it) }.getOrNull() }
            ?: return null
        return SlotBinding(
            slot = IdentitySlot(nat, role),
            deviceKey = deviceKey,
            createdAtEpochMs = prefs.getLong(KEY_CLAIMED_AT, 0L),
        )
    }

    /**
     * Claim [slot] for this phone and lock the identity forever (no override).
     * @return pair of binding + optional note (e.g. offline sync warning)
     * @throws IllegalStateException if this phone is already locked to a different slot
     * @throws TransportException if the remote says another device already owns the slot
     */
    fun claim(slot: IdentitySlot, transport: FederationTransport?): Pair<SlotBinding, String?> {
        val existing = myBinding()
        if (existing != null) {
            if (existing.clientId == slot.clientId) return existing to null
            error("This phone is already bound to ${existing.slot.displayName}")
        }

        val binding = SlotBinding(slot, deviceKey)
        var note: String? = null
        if (transport != null) {
            try {
                remoteClaim(transport, binding)
            } catch (e: TransportException) {
                if (e.message?.contains("already filled") == true) throw e
                note = e.message
            }
        } else {
            note = "offline"
        }

        prefs.edit()
            .putBoolean(KEY_LOCKED, true)
            .putString(KEY_NAT, slot.nationality.id)
            .putString(KEY_ROLE, slot.role.name)
            .putLong(KEY_CLAIMED_AT, binding.createdAtEpochMs)
            .apply()
        settings.applyIdentity(slot.nationality, slot.role)
        return binding to note
    }

    /** Re-publish our lock (e.g. after Git/LAN is configured) so other phones see the slot filled. */
    fun publish(transport: FederationTransport) {
        val mine = myBinding() ?: error("This phone has not claimed a slot yet")
        remoteClaim(transport, mine)
    }

    fun statuses(remote: List<SlotBinding>): Map<IdentitySlot, SlotStatus> {
        val mine = myBinding()
        val byId = remote.associateBy { it.clientId }
        return IdentitySlot.ALL.associateWith { slot ->
            when {
                mine?.clientId == slot.clientId -> SlotStatus.MINE
                byId[slot.clientId]?.deviceKey == deviceKey -> SlotStatus.MINE
                byId.containsKey(slot.clientId) -> SlotStatus.TAKEN
                else -> SlotStatus.EMPTY
            }
        }
    }

    private fun remoteClaim(transport: FederationTransport, binding: SlotBinding) {
        val existing = transport.fetchBindings().find { it.clientId == binding.clientId }
        if (existing != null && existing.deviceKey != binding.deviceKey) {
            throw TransportException(
                "${binding.slot.displayName} is already filled by another phone.",
            )
        }
        transport.putBinding(binding)
    }

    companion object {
        private const val PREFS = "identity_lock"
        private const val KEY_DEVICE = "device_key"
        private const val KEY_LOCKED = "locked"
        private const val KEY_NAT = "nationality"
        private const val KEY_ROLE = "role"
        private const val KEY_CLAIMED_AT = "claimed_at"

        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

object BindingCodec {
    const val SCHEMA = 1
    val KEYS = setOf("schema", "type", "clientId", "nationality", "role", "deviceKey", "createdAtEpochMs")

    fun toJson(b: SlotBinding): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("type", "binding")
        put("clientId", b.clientId)
        put("nationality", b.slot.nationality.id)
        put("role", b.slot.role.name)
        put("deviceKey", b.deviceKey)
        put("createdAtEpochMs", b.createdAtEpochMs)
    }

    fun fromJson(obj: JSONObject): SlotBinding {
        val nat = Nationality.fromId(obj.getString("nationality"))
            ?: error("unknown nationality")
        val role = ClientRole.valueOf(obj.getString("role"))
        return SlotBinding(
            slot = IdentitySlot(nat, role),
            deviceKey = obj.getString("deviceKey"),
            createdAtEpochMs = obj.optLong("createdAtEpochMs", 0L),
        )
    }
}
