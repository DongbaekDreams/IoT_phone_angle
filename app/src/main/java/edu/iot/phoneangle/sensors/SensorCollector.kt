package edu.iot.phoneangle.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import edu.iot.phoneangle.data.FloatArray3
import edu.iot.phoneangle.data.FloatArray4
import edu.iot.phoneangle.data.SensorSample

/**
 * Subscribes to motion/orientation sensors and emits timestamped samples.
 * Shared by pose collection and (later) typed-word trials.
 */
class SensorCollector(
    context: Context,
    private val onSample: (SensorSample) -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val rotationVector =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var latestAccel: FloatArray3? = null
    private var latestGyro: FloatArray3? = null
    private var latestGravity: FloatArray3? = null
    private var latestRotation: FloatArray4? = null
    private var running = false

    val availableSensors: AvailableSensors
        get() = AvailableSensors(
            accelerometer = accelerometer != null,
            gyroscope = gyroscope != null,
            gravity = gravitySensor != null,
            rotationVector = rotationVector != null,
        )

    /**
     * @param handler deliver callbacks on this thread (null = main thread). Typed-word
     * screens pass a background [android.os.HandlerThread] so 200 Hz sampling never
     * competes with the keyboard for the UI thread.
     */
    fun start(samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME, handler: Handler? = null) {
        if (running) return
        running = true
        accelerometer?.let { sensorManager.registerListener(this, it, samplingPeriodUs, handler) }
        gyroscope?.let { sensorManager.registerListener(this, it, samplingPeriodUs, handler) }
        gravitySensor?.let { sensorManager.registerListener(this, it, samplingPeriodUs, handler) }
        rotationVector?.let { sensorManager.registerListener(this, it, samplingPeriodUs, handler) }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> latestAccel = FloatArray3.from(event.values)
            Sensor.TYPE_GYROSCOPE -> latestGyro = FloatArray3.from(event.values)
            Sensor.TYPE_GRAVITY -> latestGravity = FloatArray3.from(event.values)
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> latestRotation = FloatArray4.from(event.values)
        }
        // Publish on one clock only — firing on every sensor was ~4× sample spam and UI jank.
        val clock = if (accelerometer != null) {
            event.sensor.type == Sensor.TYPE_ACCELEROMETER
        } else {
            event.sensor.type == Sensor.TYPE_GYROSCOPE
        }
        if (!clock) return
        onSample(
            SensorSample(
                timestampNs = event.timestamp,
                accel = latestAccel,
                gyro = latestGyro,
                gravity = latestGravity,
                rotationVector = latestRotation,
            )
        )
    }

    companion object {
        /** 200 Hz — keystroke taps last 20–60 ms, so 50 Hz (SENSOR_DELAY_GAME) aliases them. */
        const val TAP_SAMPLING_US = 5_000
    }

    data class AvailableSensors(
        val accelerometer: Boolean,
        val gyroscope: Boolean,
        val gravity: Boolean,
        val rotationVector: Boolean,
    )
}
