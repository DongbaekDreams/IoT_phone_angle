package edu.iot.phoneangle

/**
 * Device orientation in a screen-relative frame with gravity defining "down".
 *
 * Android device axes (phone upright in portrait):
 * - +X: right edge of the screen
 * - +Y: top edge of the screen
 * - +Z: out of the screen toward the user
 *
 * Angles (degrees):
 * - [pitch]: rotation about screen X — tip top of phone toward / away from you
 * - [roll]:  rotation about screen Y — tip left / right edge toward ground
 * - [yaw]:   rotation about screen Z — twist while keeping the screen facing you
 */
data class PhoneAngles(
    val pitchDeg: Float,
    val rollDeg: Float,
    val yawDeg: Float,
    val gravityX: Float,
    val gravityY: Float,
    val gravityZ: Float,
    val isCalibrated: Boolean = false,
)
