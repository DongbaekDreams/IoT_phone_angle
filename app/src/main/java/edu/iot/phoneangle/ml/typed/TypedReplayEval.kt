package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.data.SensorSample
import edu.iot.phoneangle.data.Trial
import edu.iot.phoneangle.data.TrialTask
import kotlin.math.abs

/**
 * Replay recorded IMU through the real [TypedListener] without exposing labels to inference.
 * Reports event-level detection stats (not window accuracy).
 */
object TypedReplayEval {

    data class WordEvent(
        val label: TypedLabel,
        val startNs: Long,
        val endNs: Long,
    )

    data class FlagEvent(
        val label: TypedLabel,
        val atNs: Long,
        val latencyMs: Long?,
    )

    data class Report(
        val targetEvents: Int,
        val truePositives: Int,
        val falsePositives: Int,
        val falseNegatives: Int,
        val duplicateFlags: Int,
        val meanLatencyMs: Float,
        val durationMinutes: Float,
        val falseAlertsPerMinute: Float,
        val precision: Float,
        val recall: Float,
        val inferenceCount: Long,
        val meanInferenceMs: Float,
        val proposals: Long,
    )

    fun eventsFromTrial(trial: Trial): List<WordEvent> {
        return when (trial.task) {
            TrialTask.TYPED_FEED -> FeedTimeline.decode(trial.notes)
                .filter { it.usable }
                .map { WordEvent(it.label, it.startNs, it.endNs) }
            TrialTask.TYPED_WORD -> {
                val label = TypedLabel.fromCollectLabel(trial.label)
                if (label == TypedLabel.IDLE || trial.samples.size < TypedPreprocessor.MIN_SAMPLES) {
                    emptyList()
                } else {
                    val span = ActivitySegmenter.span(trial.samples)
                        ?: return emptyList()
                    listOf(WordEvent(label, span.first, span.last))
                }
            }
            else -> emptyList()
        }
    }

    fun replay(bundle: TypedModelBundle, trials: List<Trial>): Report {
        val listener = TypedListener(bundle)
        val flags = mutableListOf<FlagEvent>()
        val targets = mutableListOf<WordEvent>()
        var tMin = Long.MAX_VALUE
        var tMax = Long.MIN_VALUE

        for (trial in trials) {
            targets += eventsFromTrial(trial).filter {
                it.label == TypedLabel.GOOGLE || it.label == TypedLabel.YAHOO
            }
            val samples = trial.samples
            if (samples.isEmpty()) continue
            tMin = minOf(tMin, samples.first().timestampNs)
            tMax = maxOf(tMax, samples.last().timestampNs)
            listener.reset()
            listener.setModel(bundle)
            var nextStep = samples.first().timestampNs
            for (s in samples) {
                listener.offer(s)
                if (s.timestampNs >= nextStep) {
                    val tick = listener.step()
                    tick.alert?.let { flags += FlagEvent(it, s.timestampNs, null) }
                    nextStep += TypedWindowing.LIVE_STRIDE_MS * 1_000_000L
                }
            }
        }

        return score(targets, flags, tMin, tMax, listener)
    }

    internal fun score(
        targets: List<WordEvent>,
        flags: List<FlagEvent>,
        tMin: Long,
        tMax: Long,
        listener: TypedListener,
    ): Report {
        val matched = BooleanArray(targets.size)
        var tp = 0
        var fp = 0
        var dup = 0
        val latencies = mutableListOf<Long>()
        val matchWindowNs = 2_000_000_000L

        for (flag in flags) {
            var best = -1
            var bestDist = Long.MAX_VALUE
            for (i in targets.indices) {
                if (matched[i]) continue
                if (targets[i].label != flag.label) continue
                val mid = (targets[i].startNs + targets[i].endNs) / 2
                val dist = abs(flag.atNs - mid)
                val inWindow = flag.atNs >= targets[i].startNs - 200_000_000L &&
                    flag.atNs <= targets[i].endNs + matchWindowNs
                if (inWindow && dist < bestDist) {
                    bestDist = dist
                    best = i
                }
            }
            if (best >= 0) {
                matched[best] = true
                tp++
                val lat = ((flag.atNs - targets[best].startNs) / 1_000_000L).coerceAtLeast(0L)
                latencies += lat
            } else {
                // Same-burst duplicate vs true false alert.
                val nearPrior = flags.any { other ->
                    other !== flag && other.label == flag.label &&
                        abs(other.atNs - flag.atNs) < 1_500_000_000L
                }
                if (nearPrior) dup++ else fp++
            }
        }
        val fn = matched.count { !it }
        val minutes = if (tMax > tMin) (tMax - tMin) / 60_000_000_000f else 0f
        val prec = if (tp + fp == 0) 0f else tp.toFloat() / (tp + fp)
        val rec = if (tp + fn == 0) 0f else tp.toFloat() / (tp + fn)
        return Report(
            targetEvents = targets.size,
            truePositives = tp,
            falsePositives = fp,
            falseNegatives = fn,
            duplicateFlags = dup,
            meanLatencyMs = if (latencies.isEmpty()) 0f else latencies.average().toFloat(),
            durationMinutes = minutes,
            falseAlertsPerMinute = if (minutes <= 0f) 0f else fp / minutes,
            precision = prec,
            recall = rec,
            inferenceCount = listener.inferenceCount,
            meanInferenceMs = listener.meanInferenceMs,
            proposals = listener.proposals,
        )
    }

    /** Drive listener with a raw sample list (unit tests / synthetic). */
    fun replaySamples(
        bundle: TypedModelBundle,
        samples: List<SensorSample>,
        offsetMs: Int = 0,
        listener: TypedListener = TypedListener(wordBundle = bundle),
    ): List<TypedLabel> {
        if (samples.isEmpty()) return emptyList()
        val alerts = mutableListOf<TypedLabel>()
        var nextStepNs = samples.first().timestampNs + offsetMs * 1_000_000L + 500_000_000L
        for (s in samples) {
            listener.offer(s)
            if (s.timestampNs >= nextStepNs) {
                listener.step().alert?.let { alerts += it }
                nextStepNs += TypedWindowing.LIVE_STRIDE_MS * 1_000_000L
            }
        }
        return alerts
    }
}
