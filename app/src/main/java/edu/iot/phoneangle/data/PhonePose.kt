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

    companion object {
        fun fromShortId(id: String): PhonePose? = entries.find { it.shortId == id }
    }
}

/**
 * Non-IID split from the brief.
 * Each client primarily trains on three poses; both still collect a little of all six for eval.
 */
enum class ClientRole(val label: String) {
    CLIENT_A("Client A"),
    CLIENT_B("Client B");

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
