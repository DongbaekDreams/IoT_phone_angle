package edu.iot.phoneangle.data

import edu.iot.phoneangle.store.LocalDatasetStore
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import java.util.UUID

/**
 * Synthetic pose trials for emulator / CI before real collection.
 * Gravity directions match the six required poses (device frame).
 */
object SyntheticPoseData {

    private const val G = 9.80665f

    fun gravityFor(pose: PhonePose): FloatArray3 = when (pose) {
        PhonePose.SCREEN_UP -> FloatArray3(0f, 0f, G)
        PhonePose.SCREEN_DOWN -> FloatArray3(0f, 0f, -G)
        PhonePose.PORTRAIT_UP -> FloatArray3(0f, G, 0f)
        PhonePose.PORTRAIT_DOWN -> FloatArray3(0f, -G, 0f)
        PhonePose.LANDSCAPE_LEFT -> FloatArray3(G, 0f, 0f)
        PhonePose.LANDSCAPE_RIGHT -> FloatArray3(-G, 0f, 0f)
    }

    fun generateTrial(
        pose: PhonePose,
        clientRole: ClientRole,
        sampleCount: Int = 80,
        seed: Long = Random.nextLong(),
        notes: String = "synthetic",
    ): Trial {
        val rng = Random(seed)
        val baseG = gravityFor(pose)
        val started = System.currentTimeMillis()
        val samples = List(sampleCount) { i ->
            val t = i * 10_000_000L
            val jitter = { rng.nextFloat() * 0.35f - 0.175f }
            val gx = baseG.x + jitter()
            val gy = baseG.y + jitter()
            val gz = baseG.z + jitter()
            SensorSample(
                timestampNs = t,
                accel = FloatArray3(gx + jitter() * 0.2f, gy + jitter() * 0.2f, gz + jitter() * 0.2f),
                gyro = FloatArray3(jitter() * 0.05f, jitter() * 0.05f, jitter() * 0.05f),
                gravity = FloatArray3(gx, gy, gz),
                rotationVector = FloatArray4(
                    sin(i * 0.01f) * 0.02f,
                    cos(i * 0.01f) * 0.02f,
                    0.01f,
                    0.99f,
                ),
            )
        }
        return Trial(
            id = UUID.randomUUID().toString(),
            label = pose.shortId,
            task = TrialTask.POSE,
            clientRole = clientRole,
            startedAtEpochMs = started,
            endedAtEpochMs = started + 2_000,
            samples = samples,
            notes = notes,
        )
    }

    fun seedStore(
        store: LocalDatasetStore,
        clientRole: ClientRole,
        primaryPerPose: Int = 8,
        coverPerPose: Int = 2,
        seed: Long = 42L,
    ): Int {
        val rng = Random(seed)
        var count = 0
        for (pose in PhonePose.entries) {
            val n = if (clientRole.isPrimary(pose)) primaryPerPose else coverPerPose
            repeat(n) {
                store.save(
                    generateTrial(
                        pose = pose,
                        clientRole = clientRole,
                        seed = rng.nextLong(),
                        notes = if (clientRole.isPrimary(pose)) "synthetic_primary" else "synthetic_cover",
                    ),
                )
                count++
            }
        }
        return count
    }
}
