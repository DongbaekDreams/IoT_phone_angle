package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.data.SensorSample
import edu.iot.phoneangle.data.Trial
import edu.iot.phoneangle.data.TrialTask
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * IMU-only locator for the typing burst inside a trial (no keyboard info).
 * Returns the most energetic cluster of motion, padded a little.
 */
object ActivitySegmenter {
    fun span(
        samples: List<SensorSample>,
        gapMs: Long = 500L,
        padMs: Long = 120L,
    ): LongRange? {
        val n = samples.size
        if (n < TypedPreprocessor.MIN_SAMPLES) return null
        val energy = FloatArray(n)
        var ex = samples[0].accel?.x ?: 0f
        var ey = samples[0].accel?.y ?: 0f
        var ez = samples[0].accel?.z ?: 0f
        for (i in 0 until n) {
            val a = samples[i].accel
            val g = samples[i].gyro
            val ax = a?.x ?: 0f
            val ay = a?.y ?: 0f
            val az = a?.z ?: 0f
            ex += 0.1f * (ax - ex)
            ey += 0.1f * (ay - ey)
            ez += 0.1f * (az - ez)
            val dyn = sqrt((ax - ex) * (ax - ex) + (ay - ey) * (ay - ey) + (az - ez) * (az - ez))
            val gm = if (g == null) 0f else sqrt(g.x * g.x + g.y * g.y + g.z * g.z)
            energy[i] = dyn + gm
        }
        val smooth = FloatArray(n) { i ->
            val lo = max(0, i - 1)
            val hi = min(n - 1, i + 1)
            var s = 0f
            for (k in lo..hi) s += energy[k]
            s / (hi - lo + 1)
        }
        val sorted = smooth.sortedArray()
        val median = sorted[n / 2]
        val p90 = sorted[(n * 9) / 10]
        val thr = max(max(median * 2.5f, p90 * 0.35f), 0.03f)
        val gapNs = gapMs * 1_000_000L

        var bestScore = 0f
        var bestFirst = -1
        var bestLast = -1
        var curFirst = -1
        var curLast = -1
        var curScore = 0f
        fun flush() {
            if (curFirst >= 0 && curScore > bestScore) {
                bestScore = curScore
                bestFirst = curFirst
                bestLast = curLast
            }
        }
        for (i in 0 until n) {
            if (smooth[i] <= thr) continue
            if (curFirst >= 0 && samples[i].timestampNs - samples[curLast].timestampNs > gapNs) {
                flush()
                curFirst = -1
                curScore = 0f
            }
            if (curFirst < 0) curFirst = i
            curLast = i
            curScore += smooth[i]
        }
        flush()
        if (bestFirst < 0) return null
        val pad = padMs * 1_000_000L
        return (samples[bestFirst].timestampNs - pad)..(samples[bestLast].timestampNs + pad)
    }
}

/**
 * Live training examples aligned with the snipped / Play feature path:
 * index-resample a burst (or prefix) to 64 steps — same as [TypedPreprocessor.channelsFromSamples].
 * Idle windows use quiet spans. Augmentation (time-warp / hesitation) is applied in [TypedWorkflow].
 */
class TypedWindowing(
    private val preprocessor: TypedPreprocessor = TypedPreprocessor(),
    val windowMs: Long = LIVE_WINDOW_MS,
    private val idleStrideMs: Long = 250L,
) {
    fun windowsFromTrial(trial: Trial): List<TypedChannelExample> {
        if (trial.task != TrialTask.TYPED_WORD) return emptyList()
        val samples = trial.samples
        if (samples.size < TypedPreprocessor.MIN_SAMPLES) return emptyList()
        val label = TypedLabel.fromCollectLabel(trial.label)
        val w = windowMs * 1_000_000L
        val t0 = samples.first().timestampNs
        val t1 = samples.last().timestampNs
        val out = mutableListOf<TypedChannelExample>()
        val sid = trial.id

        fun addIdle(endNs: Long, durMs: Long) {
            val ch = preprocessor.channelsFromWindow(samples, endNs, durMs) ?: return
            out += TypedChannelExample(ch, TypedLabel.IDLE, sid)
        }

        if (label == TypedLabel.IDLE) {
            if (t1 - t0 < w) {
                addIdle(t1, ((t1 - t0) / 1_000_000L).coerceIn(TypedPreprocessor.MIN_BURST_MS, windowMs))
            } else {
                var e = t0 + w
                while (e <= t1) {
                    addIdle(e, windowMs)
                    e += idleStrideMs * 1_000_000L
                }
            }
            return out
        }

        val span = ActivitySegmenter.span(samples) ?: return emptyList()
        val s0 = span.first.coerceAtLeast(t0)
        val s1 = span.last.coerceAtMost(t1)
        if (s1 <= s0) return emptyList()

        // Full burst — same feature contract as the static snipped model.
        preprocessor.channelsFromSpan(samples, s0, s1)?.let {
            out += TypedChannelExample(it, label, sid)
        }
        // Whole-trial snipped path (includes a little lead/tail like Play recordings).
        preprocessor.channelsFromSamples(samples)?.let {
            out += TypedChannelExample(it, label, sid)
        }
        // Prefixes for progressive scoring (index-resample of [s0, s0+dur]).
        val burstMs = ((s1 - s0) / 1_000_000L).coerceAtLeast(1L)
        for (durMs in CHECKPOINT_MS) {
            if (durMs > burstMs + 80L) continue
            val endNs = s0 + durMs * 1_000_000L
            preprocessor.channelsFromSpan(samples, s0, min(endNs, s1))?.let {
                out += TypedChannelExample(it, label, sid)
            }
        }

        // Quiet surroundings → idle.
        val idleStep = idleStrideMs * 1_000_000L
        var ie = t0 + w
        while (ie <= t1) {
            val overlap = max(0L, min(ie, s1) - max(ie - w, s0))
            if (overlap == 0L) addIdle(ie, windowMs)
            ie += idleStep
        }
        return out
    }

    /** Legacy feed sessions (if any remain on disk). */
    fun windowsFromFeed(trial: Trial): List<TypedChannelExample> {
        if (trial.task != TrialTask.TYPED_FEED) return emptyList()
        val words = FeedTimeline.decode(trial.notes)
        val samples = trial.samples
        if (words.isEmpty() || samples.size < TypedPreprocessor.MIN_SAMPLES) return emptyList()
        val sid = trial.id
        val out = mutableListOf<TypedChannelExample>()
        for (fw in words) {
            if (!fw.usable) continue
            val lab = fw.label
            preprocessor.channelsFromSpan(samples, fw.startNs, fw.endNs)?.let {
                out += TypedChannelExample(it, lab, sid)
            }
        }
        return out
    }

    fun windowsFromTrials(trials: List<Trial>): List<TypedChannelExample> =
        trials.flatMap { if (it.task == TrialTask.TYPED_FEED) windowsFromFeed(it) else windowsFromTrial(it) }

    companion object {
        const val LIVE_WINDOW_MS = 1_600L
        const val LIVE_STRIDE_MS = 100L
        private val CHECKPOINT_MS = longArrayOf(500L, 750L, 1000L, 1300L)
    }
}
