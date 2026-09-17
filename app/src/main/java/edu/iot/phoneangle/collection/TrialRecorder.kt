package edu.iot.phoneangle.collection

import edu.iot.phoneangle.data.ClientRole
import edu.iot.phoneangle.data.SensorSample
import edu.iot.phoneangle.data.Trial
import edu.iot.phoneangle.data.TrialTask
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Starts/stops a labeled recording session and accumulates sensor samples.
 */
class TrialRecorder {

    @Volatile
    private var active: ActiveTrial? = null

    val isRecording: Boolean
        get() = active != null

    val sampleCount: Int
        get() = active?.samples?.size ?: 0

    fun start(
        label: String,
        task: TrialTask,
        clientRole: ClientRole,
        notes: String = "",
    ) {
        check(active == null) { "Already recording a trial" }
        active = ActiveTrial(
            id = UUID.randomUUID().toString(),
            label = label,
            task = task,
            clientRole = clientRole,
            startedAtEpochMs = System.currentTimeMillis(),
            notes = notes,
            samples = CopyOnWriteArrayList(),
        )
    }

    fun offer(sample: SensorSample) {
        active?.samples?.add(sample)
    }

    fun stop(): Trial {
        val current = checkNotNull(active) { "No active trial" }
        active = null
        return Trial(
            id = current.id,
            label = current.label,
            task = current.task,
            clientRole = current.clientRole,
            startedAtEpochMs = current.startedAtEpochMs,
            endedAtEpochMs = System.currentTimeMillis(),
            samples = current.samples.toList(),
            notes = current.notes,
        )
    }

    fun cancel() {
        active = null
    }

    private data class ActiveTrial(
        val id: String,
        val label: String,
        val task: TrialTask,
        val clientRole: ClientRole,
        val startedAtEpochMs: Long,
        val notes: String,
        val samples: CopyOnWriteArrayList<SensorSample>,
    )
}
