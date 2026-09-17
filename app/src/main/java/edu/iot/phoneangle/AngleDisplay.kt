package edu.iot.phoneangle

import kotlin.math.abs

class AngleDisplay(private val smoothFactor: Float = 0.22f) {
    private var dispPitch = 0f
    private var dispRoll = 0f
    private var dispYaw = 0f
    private var initialized = false

    fun reset() {
        initialized = false
    }

    fun process(
        pitch: Float,
        roll: Float,
        yaw: Float,
        smooth: Boolean,
        deadZone: Boolean,
    ): Triple<Float, Float, Float> {
        var p = pitch
        var r = roll
        var y = yaw
        if (deadZone) {
            p = applyDeadZone(p)
            r = applyDeadZone(r)
            y = applyDeadZone(y)
        }
        if (!initialized) {
            dispPitch = p
            dispRoll = r
            dispYaw = y
            initialized = true
            return Triple(p, r, y)
        }
        if (smooth) {
            dispPitch = lerp(dispPitch, p)
            dispRoll = lerp(dispRoll, r)
            dispYaw = lerpAngle(dispYaw, y)
            return Triple(dispPitch, dispRoll, dispYaw)
        }
        dispPitch = p
        dispRoll = r
        dispYaw = y
        return Triple(p, r, y)
    }

    private fun lerp(current: Float, target: Float): Float =
        current + (target - current) * smoothFactor

    private fun lerpAngle(current: Float, target: Float): Float {
        var delta = target - current
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return current + delta * smoothFactor
    }

    private fun applyDeadZone(v: Float): Float =
        if (abs(v) < AppSettings.DEAD_ZONE_DEG) 0f else v
}
