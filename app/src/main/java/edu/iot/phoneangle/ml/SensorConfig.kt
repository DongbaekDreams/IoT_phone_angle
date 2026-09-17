package edu.iot.phoneangle.ml

/**
 * Which sensor channels feed the feature vector.
 * Layout stays fixed (zeros for unused channels) so model shape stays stable for FedAvg.
 */
enum class SensorConfig(val id: String, val displayName: String) {
    ALL("all", "All motion sensors"),
    GRAVITY_ONLY("gravity", "Gravity only"),
    ACCEL_ONLY("accel", "Accelerometer only"),
    GYRO_ONLY("gyro", "Gyroscope only"),
    ACCEL_GYRO("accel_gyro", "Accel + gyro"),
    GRAVITY_ORIENTATION("gravity_orientation", "Gravity + rotation");

    companion object {
        fun fromId(id: String): SensorConfig =
            entries.find { it.id == id } ?: ALL
    }

    fun useGravity(): Boolean = this == ALL || this == GRAVITY_ONLY || this == GRAVITY_ORIENTATION
    fun useAccel(): Boolean = this == ALL || this == ACCEL_ONLY || this == ACCEL_GYRO
    fun useGyro(): Boolean = this == ALL || this == GYRO_ONLY || this == ACCEL_GYRO
    fun useRotation(): Boolean = this == ALL || this == GRAVITY_ORIENTATION
}
