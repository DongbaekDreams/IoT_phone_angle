package edu.iot.phoneangle.ml.typed

/**
 * Classes for search-query spotting.
 * - [SNIPPED] = google / yahoo / other: the per-query decision and its confusion matrix.
 * - [IDLE] labels idle recordings (handling the phone, no typing) used to count false taps.
 */
enum class TypedLabel(val id: String, val displayName: String) {
    GOOGLE("google", "google"),
    YAHOO("yahoo", "yahoo"),
    OTHER("other", "other"),
    IDLE("idle", "idle");

    companion object {
        val SNIPPED: List<TypedLabel> = listOf(GOOGLE, YAHOO, OTHER)
        val LIVE: List<TypedLabel> = listOf(GOOGLE, YAHOO, OTHER, IDLE)

        fun fromCollectLabel(raw: String): TypedLabel {
            val key = raw.trim().lowercase()
            return when (key) {
                "google", "google.com" -> GOOGLE
                "yahoo", "yahoo.com" -> YAHOO
                "idle" -> IDLE
                else -> OTHER
            }
        }

        fun fromId(id: String): TypedLabel =
            entries.find { it.id == id.lowercase() } ?: OTHER
    }
}
