package edu.iot.phoneangle

import edu.iot.phoneangle.data.ClientRole

/**
 * Named phones for the data campaign. Flags identify the site; Class / Personal is the
 * non-IID role (course Client A / Client B). File paths use the ASCII [clientId] only.
 *
 * Peru may only have one phone; leave the unused Peru slot unset.
 */
enum class PhonePreset(
    val flag: String,
    val country: String,
    val role: ClientRole,
    val clientId: String,
    /** Suggested Collect location tag for both phones at this site. */
    val locationTag: String,
    /** Older ids still recognized so an already-set phone keeps its preset. */
    private val legacyIds: Set<String> = emptySet(),
) {
    CANADA_CLASS("🇨🇦", "Canada", ClientRole.CLIENT_A, "canada-class", "canada", setOf("canada-a")),
    CANADA_PERSONAL("🇨🇦", "Canada", ClientRole.CLIENT_B, "canada-personal", "canada", setOf("canada-b")),
    PERU_CLASS("🇵🇪", "Peru", ClientRole.CLIENT_A, "peru-class", "peru", setOf("peru-a")),
    PERU_PERSONAL("🇵🇪", "Peru", ClientRole.CLIENT_B, "peru-personal", "peru", setOf("peru-b"));

    /** Chip / dialog label, e.g. "🇨🇦 Canada Class". */
    val displayName: String get() = "$flag $country ${role.label}"

    companion object {
        fun fromClientId(id: String): PhonePreset? {
            val clean = ProjectSettings.sanitizeId(id)
            return entries.find { it.clientId == clean || clean in it.legacyIds }
        }
    }
}
