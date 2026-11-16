package edu.iot.phoneangle.collection

import edu.iot.phoneangle.data.ClientRole
import edu.iot.phoneangle.data.SensorSample
import edu.iot.phoneangle.data.TouchLabel
import edu.iot.phoneangle.data.Trial
import edu.iot.phoneangle.data.TrialTask
import java.util.UUID
import java.util.Collections

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
        sessionTag: String = "",
    ) {
        check(active == null) { "Already recording a trial" }
        active = ActiveTrial(
            id = UUID.randomUUID().toString(),
            label = label,
            task = task,
            clientRole = clientRole,
            startedAtEpochMs = System.currentTimeMillis(),
            notes = notes,
            sessionTag = sessionTag,
            // Synchronized lists, not copy-on-write: at 200 Hz COW would copy the whole trial per sample.
            samples = Collections.synchronizedList(ArrayList()),
            touches = Collections.synchronizedList(ArrayList()),
        )
    }

    fun offer(sample: SensorSample) {
        active?.samples?.add(sample)
    }

    fun offerTouch(touch: TouchLabel) {
        active?.touches?.add(touch)
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
            samples = synchronized(current.samples) { current.samples.toList() },
            notes = current.notes,
            sessionTag = current.sessionTag,
            touches = synchronized(current.touches) { current.touches.toList() },
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
        val sessionTag: String,
        val samples: MutableList<SensorSample>,
        val touches: MutableList<TouchLabel>,
    )
}
