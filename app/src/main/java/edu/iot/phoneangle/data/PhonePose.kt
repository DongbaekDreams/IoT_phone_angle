package edu.iot.phoneangle.data

/**
 * Six phone poses from the CSC 8223 brief.
 * [shortId] is stable for storage/models; [displayName] / [howToHold] are for humans.
 */
enum class PhonePose(
    val displayName: String,
    val howToHold: String,
    val shortId: String,
) {
    SCREEN_UP(
        displayName = "Flat, face up",
        howToHold = "Lay the phone flat on a table with the screen facing the ceiling.",
        shortId = "screen_up",
    ),
    SCREEN_DOWN(
        displayName = "Flat, face down",
        howToHold = "Lay the phone flat on a table with the screen facing the table.",
        shortId = "screen_down",
    ),
    PORTRAIT_UP(
        displayName = "Upright (normal)",
        howToHold = "Hold the phone vertically like reading — top edge toward the ceiling.",
        shortId = "portrait_up",
    ),
    PORTRAIT_DOWN(
        displayName = "Upright, upside-down",
        howToHold = "Hold the phone vertically but flipped — top edge (speaker/camera) toward the floor.",
        shortId = "portrait_down",
    ),
    LANDSCAPE_LEFT(
        displayName = "On left side",
        howToHold = "Stand the phone on its long edge so the left side points at the ceiling.",
        shortId = "landscape_left",
    ),
    LANDSCAPE_RIGHT(
        displayName = "On right side",
        howToHold = "Stand the phone on its long edge so the right side points at the ceiling.",
        shortId = "landscape_right",
    );

    /**
     * Expected gravity-sensor reading in the device frame (X right, Y up the screen, Z out of the screen).
     * The sensor reports the direction of "up", so a phone lying face up reads +Z.
     */
    fun expectedGravity(): FloatArray3 = when (this) {
        SCREEN_UP -> FloatArray3(0f, 0f, 1f)
        SCREEN_DOWN -> FloatArray3(0f, 0f, -1f)
        PORTRAIT_UP -> FloatArray3(0f, 1f, 0f)
        PORTRAIT_DOWN -> FloatArray3(0f, -1f, 0f)
        LANDSCAPE_LEFT -> FloatArray3(-1f, 0f, 0f) // left edge up => up is -X
        LANDSCAPE_RIGHT -> FloatArray3(1f, 0f, 0f)
    }

    /** Cosine similarity between a live gravity reading and this pose's expected direction. */
    fun matchScore(gravity: FloatArray3): Float {
        val len = kotlin.math.sqrt(gravity.x * gravity.x + gravity.y * gravity.y + gravity.z * gravity.z)
        if (len < 1e-3f) return 0f
        val e = expectedGravity()
        return (gravity.x * e.x + gravity.y * e.y + gravity.z * e.z) / len
    }

    companion object {
        const val MATCH_THRESHOLD = 0.9f

        fun fromShortId(id: String): PhonePose? = entries.find { it.shortId == id }
    }
}

/**
 * Non-IID split from the brief (Client A / Client B).
 * In the app we call them Class phone and Personal phone.
 * Each primarily trains on three poses; both still collect a little of all six for eval.
 */
enum class ClientRole(val label: String) {
    CLIENT_A("Class"),
    CLIENT_B("Personal");

    fun primaryPoses(): Set<PhonePose> = when (this) {
        CLIENT_A -> setOf(
            PhonePose.SCREEN_UP,
            PhonePose.SCREEN_DOWN,
            PhonePose.PORTRAIT_UP,
        )
        CLIENT_B -> setOf(
            PhonePose.PORTRAIT_DOWN,
            PhonePose.LANDSCAPE_LEFT,
            PhonePose.LANDSCAPE_RIGHT,
        )
    }

    fun isPrimary(pose: PhonePose): Boolean = pose in primaryPoses()
}
