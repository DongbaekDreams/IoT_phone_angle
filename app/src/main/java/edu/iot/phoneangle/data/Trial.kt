package edu.iot.phoneangle.data

/**
 * One timestamped multi-sensor snapshot.
 * Missing channels stay null so ablations and device differences are explicit.
 */
data class SensorSample(
    val timestampNs: Long,
    val accel: FloatArray3? = null,
    val gyro: FloatArray3? = null,
    val gravity: FloatArray3? = null,
    val rotationVector: FloatArray4? = null,
)

data class FloatArray3(val x: Float, val y: Float, val z: Float) {
    fun toList(): List<Float> = listOf(x, y, z)

    companion object {
        fun from(values: FloatArray): FloatArray3 =
            FloatArray3(values[0], values[1], values.getOrElse(2) { 0f })
    }
}

data class FloatArray4(val x: Float, val y: Float, val z: Float, val w: Float) {
    fun toList(): List<Float> = listOf(x, y, z, w)

    companion object {
        fun from(values: FloatArray): FloatArray4 =
            FloatArray4(
                values[0],
                values[1],
                values.getOrElse(2) { 0f },
                values.getOrElse(3) { 0f },
            )
    }
}

/**
 * One labeled recording session (pose hold or typed-word trial).
 * Raw samples stay on-device; never sent to the federated server.
 */
data class Trial(
    val id: String,
    val label: String,
    val task: TrialTask,
    val clientRole: ClientRole,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val samples: List<SensorSample>,
    val notes: String = "",
    /** Legacy field for poses; typed-tap trials store the grip (e.g. "two_thumbs"). */
    val sessionTag: String = "",
    /** Ground-truth key presses from the in-app training keyboard (labels only, never model input). */
    val touches: List<TouchLabel> = emptyList(),
)

/**
 * One key press on the training keyboard.
 * @param timestampNs touch-down on the sensor clock (elapsedRealtimeNanos), so it lines up with samples.
 * @param x,y touch position normalized to the keyboard area: 0..1 left→right, 0..1 top→bottom.
 * @param key the key's label (letter, '.', or [KEY_SPACE] / [KEY_BACKSPACE] / [KEY_ENTER]).
 */
data class TouchLabel(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val key: String,
) {
    companion object {
        const val KEY_SPACE = "space"
        const val KEY_BACKSPACE = "back"
        const val KEY_ENTER = "enter"
    }
}

enum class TrialTask(val id: String) {
    POSE("pose"),
    TYPED_WORD("typed_word"),
    /** One continuous scrolling-feed typing session; per-word timeline lives in [Trial.notes]. */
    TYPED_FEED("typed_feed"),
    /** Search-bar query typed on the in-app keyboard at 200 Hz, with per-key [TouchLabel]s. */
    TYPED_TAP("typed_tap");

    companion object {
        fun fromId(id: String): TrialTask =
            entries.find { it.id == id } ?: POSE
    }
}
