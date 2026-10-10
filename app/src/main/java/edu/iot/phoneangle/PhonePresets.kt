package edu.iot.phoneangle

import edu.iot.phoneangle.data.ClientRole

/**
 * Nationality labels for distinguishing who owns which phone, without putting real names
 * in the UI or federation files. Not a physical location — use Collect's room tag for that.
 */
enum class Nationality(
    val id: String,
    val flag: String,
    val displayName: String,
) {
    CANADA("canada", "🇨🇦", "Canada"),
    PERU("peru", "🇵🇪", "Peru");

    companion object {
        fun fromId(id: String): Nationality? =
            entries.find { it.id == ProjectSettings.sanitizeId(id) }
    }
}

/** Maps a nationality + Class/Personal role to the stable federation client id. */
object PhoneIdentity {
    fun clientId(nationality: Nationality, role: ClientRole): String =
        "${nationality.id}-${if (role == ClientRole.CLIENT_A) "class" else "personal"}"

    fun displayLabel(nationality: Nationality, role: ClientRole): String =
        "${nationality.flag} ${role.label}"

    /** Older client ids from earlier naming schemes. */
    fun fromLegacyClientId(id: String): Pair<Nationality, ClientRole>? {
        val clean = ProjectSettings.sanitizeId(id)
        val map = mapOf(
            "canada-class" to (Nationality.CANADA to ClientRole.CLIENT_A),
            "canada-personal" to (Nationality.CANADA to ClientRole.CLIENT_B),
            "peru-class" to (Nationality.PERU to ClientRole.CLIENT_A),
            "peru-personal" to (Nationality.PERU to ClientRole.CLIENT_B),
            "canada-a" to (Nationality.CANADA to ClientRole.CLIENT_A),
            "canada-b" to (Nationality.CANADA to ClientRole.CLIENT_B),
            "peru-a" to (Nationality.PERU to ClientRole.CLIENT_A),
            "peru-b" to (Nationality.PERU to ClientRole.CLIENT_B),
        )
        return map[clean]
    }
}
