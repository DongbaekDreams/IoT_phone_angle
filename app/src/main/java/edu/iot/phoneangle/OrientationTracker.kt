package edu.iot.phoneangle

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Screen-relative orientation with ground (gravity) as down.
 *
 * Holding the phone upright, screen toward you → pitch ≈ 0°, roll ≈ 0°.
 * - **Pitch**: tip top toward you (−) / away from you (+) — about screen X
 * - **Roll**:  tip left (−) / right (+) — about the vertical in the screen plane
 * - **Yaw**:   twist of the phone around vertical (ground normal); calibrate to zero
 *
 * Pitch/roll are from gravity (stable when upright — no getOrientation gimbal lock).
 * Yaw is from the fused rotation matrix projected onto the ground plane.
 */
class OrientationTracker(
    context: Context,
    private val onAngles: (PhoneAngles) -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationVector: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val gravitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val magnetometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false
    private var hasRotation = false

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var offsetPitch = 0f
    private var offsetRoll = 0f
    private var offsetYaw = 0f
    private var calibrated = false

    private var rawPitch = 0f
    private var rawRoll = 0f
    private var rawYaw = 0f

    val isAvailable: Boolean
        get() = gravitySensor != null || accelerometer != null || rotationVector != null

    val isCalibrated: Boolean
        get() = calibrated

    fun start() {
        val delay = SensorManager.SENSOR_DELAY_GAME
        rotationVector?.let { sensorManager.registerListener(this, it, delay) }
        when {
            gravitySensor != null ->
                sensorManager.registerListener(this, gravitySensor, delay)
            accelerometer != null ->
                sensorManager.registerListener(this, accelerometer, delay)
        }
        if (rotationVector == null && magnetometer != null && accelerometer != null) {
            sensorManager.registerListener(this, magnetometer, delay)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun calibrate() {
        offsetPitch = rawPitch
        offsetRoll = rawRoll
        offsetYaw = rawYaw
        calibrated = true
        publish()
    }

    fun resetCalibration() {
        offsetPitch = 0f
        offsetRoll = 0f
        offsetYaw = 0f
        calibrated = false
        publish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                hasRotation = true
                updateYawFromMatrix()
                if (!hasGravity) updatePitchRollFromMatrixUp()
                publish()
            }
            Sensor.TYPE_GRAVITY -> {
                gravity[0] = event.values[0]
                gravity[1] = event.values[1]
                gravity[2] = event.values[2]
                hasGravity = true
                updatePitchRollFromGravity()
                publish()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(event.values, gravity)
                hasGravity = true
                updatePitchRollFromGravity()
                if (!hasRotation && hasGeomagnetic) tryBuildMatrixFromAccelMag()
                publish()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lowPass(event.values, geomagnetic)
                hasGeomagnetic = true
                if (!hasRotation && hasGravity) {
                    tryBuildMatrixFromAccelMag()
                    publish()
                }
            }
        }
    }

    private fun tryBuildMatrixFromAccelMag() {
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
            hasRotation = true
            updateYawFromMatrix()
        }
    }

    /**
     * Gravity in device frame (X right, Y up the screen, Z toward user).
     * Upright ≈ (0, +g, 0); flat face-up ≈ (0, 0, +g).
     */
    private fun updatePitchRollFromGravity() {
        val gx = gravity[0].toDouble()
        val gy = gravity[1].toDouble()
        val gz = gravity[2].toDouble()

        // Pitch about X: 0 upright, −90° flat face-up, +90° flat-ish face toward you
        rawPitch = Math.toDegrees(atan2(-gz, gy)).toFloat()

        // Roll about the screen's vertical plane: 0 upright/flat, + when right edge down
        rawRoll = Math.toDegrees(
            atan2(gx, sqrt(gy * gy + gz * gz))
        ).toFloat()
    }

    /** world-up expressed in device axes = third column of R (device→world). */
    private fun updatePitchRollFromMatrixUp() {
        gravity[0] = rotationMatrix[2] * SensorManager.GRAVITY_EARTH
        gravity[1] = rotationMatrix[5] * SensorManager.GRAVITY_EARTH
        gravity[2] = rotationMatrix[8] * SensorManager.GRAVITY_EARTH
        hasGravity = true
        updatePitchRollFromGravity()
    }

    /**
     * Twist about vertical (ground normal), continuous across upright ↔ flat.
     *
     * Upright: Android azimuth (device Y projected on horizontal).
     * Flat: heading of the top edge (+Y) on the ground plane.
     * Between: blend those horizontal unit vectors — never hard-switch axes (that caused 0↔180 jumps).
     */
    private fun updateYawFromMatrix() {
        if (!SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Y,
                remappedMatrix,
            )
        ) {
            return
        }
        SensorManager.getOrientation(remappedMatrix, orientation)
        val az = orientation[0].toDouble()
        var ux = sin(az).toFloat()
        var uy = cos(az).toFloat()

        // Top of phone (+Y) on the ground plane — best when nearly flat
        val topEast = rotationMatrix[1]
        val topNorth = rotationMatrix[4]
        val topLen = sqrt(topEast * topEast + topNorth * topNorth)

        // 0 when upright, 1 when flat (|pitch| grows toward 90°)
        val flatness = ((kotlin.math.abs(rawPitch) - 45f) / 45f).coerceIn(0f, 1f)

        if (topLen > 0.05f && flatness > 0.001f) {
            val tx = topEast / topLen
            val ty = topNorth / topLen
            val bx = (1f - flatness) * ux + flatness * tx
            val by = (1f - flatness) * uy + flatness * ty
            val bLen = sqrt(bx * bx + by * by)
            if (bLen > 0.01f) {
                rawYaw = Math.toDegrees(
                    atan2((bx / bLen).toDouble(), (by / bLen).toDouble())
                ).toFloat()
                return
            }
        }
        rawYaw = Math.toDegrees(az).toFloat()
    }

    private fun publish() {
        onAngles(
            PhoneAngles(
                pitchDeg = wrap180(rawPitch - offsetPitch),
                rollDeg = wrap180(rawRoll - offsetRoll),
                yawDeg = wrap180(rawYaw - offsetYaw),
                gravityX = gravity[0],
                gravityY = gravity[1],
                gravityZ = gravity[2],
                isCalibrated = calibrated,
            )
        )
    }

    private fun wrap180(deg: Float): Float {
        var d = ((deg + 180f) % 360f + 360f) % 360f - 180f
        if (d <= -180f) d += 360f
        return d
    }

    private fun lowPass(input: FloatArray, output: FloatArray, alpha: Float = 0.18f) {
        for (i in output.indices) {
            output[i] = output[i] + alpha * (input[i] - output[i])
        }
    }
}
