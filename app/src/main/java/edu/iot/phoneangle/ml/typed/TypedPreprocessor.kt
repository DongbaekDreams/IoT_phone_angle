package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.data.SensorSample
import edu.iot.phoneangle.data.Trial
import edu.iot.phoneangle.data.TrialTask

/**
 * Typed-word features via multivariate MiniRocket on accel/gyro axes
 * (ax,ay,az,gx,gy,gz). Biases + FeatureScaler are fit in [TypedWorkflow].
 */
class TypedPreprocessor(
    private val rocket: MiniRocketTransform = MiniRocketTransform.Default,
) {

    fun featureDim(): Int = FEATURE_DIM

    fun channelExampleFromTrial(trial: Trial): TypedChannelExample? {
        if (trial.task != TrialTask.TYPED_WORD) return null
        val channels = channelsFromSamples(trial.samples) ?: return null
        return TypedChannelExample(
            channels = channels,
            label = TypedLabel.fromCollectLabel(trial.label),
            trialId = trial.id,
        )
    }

    /**
     * Snipped training examples: prefer the IMU typing burst (cleaner, matches live),
     * and also keep the whole-trial resample when it differs (lead/tail context).
     */
    fun channelExamplesFromTrial(trial: Trial): List<TypedChannelExample> {
        if (trial.task != TrialTask.TYPED_WORD) return emptyList()
        val label = TypedLabel.fromCollectLabel(trial.label)
        if (label == TypedLabel.IDLE) return emptyList()
        val samples = trial.samples
        if (samples.size < MIN_SAMPLES) return emptyList()
        val out = ArrayList<TypedChannelExample>(2)
        val span = ActivitySegmenter.span(samples)
        if (span != null) {
            channelsFromSpan(samples, span.first, span.last)?.let {
                out += TypedChannelExample(it, label, trial.id)
            }
        }
        channelsFromSamples(samples)?.let { full ->
            val dup = out.any { existing -> channelsRoughlyEqual(existing.channels, full) }
            if (!dup) out += TypedChannelExample(full, label, trial.id)
        }
        return out
    }

    fun channelExamplesFromTrials(trials: List<Trial>): List<TypedChannelExample> =
        trials.flatMap { channelExamplesFromTrial(it) }

    private fun channelsRoughlyEqual(a: Array<FloatArray>, b: Array<FloatArray>): Boolean {
        var err = 0f
        var n = 0
        for (c in a.indices) {
            for (i in a[c].indices) {
                val d = a[c][i] - b[c][i]
                err += d * d
                n++
            }
        }
        return err / n.coerceAtLeast(1) < 1e-6f
    }

    fun channelsFromSamples(samples: List<SensorSample>): Array<FloatArray>? {
        if (samples.size < MIN_SAMPLES) return null
        val window = resample(samples, MiniRocketTransform.INPUT_LENGTH)
        return Array(MiniRocketTransform.N_CHANNELS) { c ->
            FloatArray(MiniRocketTransform.INPUT_LENGTH) { i ->
                val s = window[i]
                when (c) {
                    0 -> s.accel?.x ?: 0f
                    1 -> s.accel?.y ?: 0f
                    2 -> s.accel?.z ?: 0f
                    3 -> s.gyro?.x ?: 0f
                    4 -> s.gyro?.y ?: 0f
                    5 -> s.gyro?.z ?: 0f
                    else -> 0f
                }
            }
        }
    }

    /**
     * Snipped-style features for a burst span: index-resample the samples in
     * [startNs, endNs] to 64 steps (same path as the static / Play model).
     */
    fun channelsFromSpan(samples: List<SensorSample>, startNs: Long, endNs: Long): Array<FloatArray>? {
        if (endNs <= startNs) return null
        val slice = samples.filter { it.timestampNs in startNs..endNs }
        return channelsFromSamples(slice)
    }

    fun channelsFromSpanInto(
        samples: List<SensorSample>,
        startNs: Long,
        endNs: Long,
        out: Array<FloatArray>,
    ): Boolean {
        val ch = channelsFromSpan(samples, startNs, endNs) ?: return false
        for (c in ch.indices) {
            System.arraycopy(ch[c], 0, out[c], 0, MiniRocketTransform.INPUT_LENGTH)
        }
        return true
    }

    /**
     * Time-uniform channels over [endNs - windowMs, endNs]. The window length is the
     * **actual** segment duration (partial bursts use shorter windows — never pad a tiny
     * snippet out to a full word length). Samples outside the recorded range are held at
     * the nearest edge sample so train and live share the same time scale.
     */
    fun channelsFromWindow(samples: List<SensorSample>, endNs: Long, windowMs: Long): Array<FloatArray>? {
        val out = Array(MiniRocketTransform.N_CHANNELS) { FloatArray(MiniRocketTransform.INPUT_LENGTH) }
        return if (channelsFromWindowInto(samples, endNs, windowMs, out)) out else null
    }

    /** Fill a caller-owned channel buffer; returns false if too few samples. */
    fun channelsFromWindowInto(
        samples: List<SensorSample>,
        endNs: Long,
        windowMs: Long,
        out: Array<FloatArray>,
    ): Boolean {
        if (samples.size < MIN_SAMPLES) return false
        require(out.size == MiniRocketTransform.N_CHANNELS)
        val n = MiniRocketTransform.INPUT_LENGTH
        val startNs = endNs - windowMs * 1_000_000L
        val span = (endNs - startNs).coerceAtLeast(1L)
        var j = 0
        for (i in 0 until n) {
            val t = startNs + span * i / (n - 1)
            while (j + 1 < samples.size && samples[j + 1].timestampNs <= t) j++
            val s = samples[j]
            out[0][i] = s.accel?.x ?: 0f
            out[1][i] = s.accel?.y ?: 0f
            out[2][i] = s.accel?.z ?: 0f
            out[3][i] = s.gyro?.x ?: 0f
            out[4][i] = s.gyro?.y ?: 0f
            out[5][i] = s.gyro?.z ?: 0f
        }
        return true
    }

    fun featuresFromChannels(channels: Array<FloatArray>, biases: FloatArray): FloatArray =
        rocket.transform(channels, biases)

    fun featuresFromChannelsInto(
        channels: Array<FloatArray>,
        biases: FloatArray,
        out: FloatArray,
    ) {
        rocket.transformInto(channels, biases, out)
    }

    fun featuresFromSamples(samples: List<SensorSample>, biases: FloatArray): FloatArray? {
        val channels = channelsFromSamples(samples) ?: return null
        return featuresFromChannels(channels, biases)
    }

    fun fitBiases(trainChannels: List<Array<FloatArray>>): FloatArray =
        rocket.fitBiases(trainChannels)

    private fun resample(samples: List<SensorSample>, target: Int): List<SensorSample> {
        if (samples.size == target) return samples
        if (samples.size < target) {
            val last = samples.last()
            return samples + List(target - samples.size) { last }
        }
        val step = samples.size.toFloat() / target
        return List(target) { idx -> samples[(idx * step).toInt().coerceIn(0, samples.lastIndex)] }
    }

    companion object {
        /** Bump when Rocket layout / channel / window contract changes. */
        const val VERSION = 7
        val FEATURE_DIM: Int = MiniRocketTransform.Default.featureDim
        const val MIN_SAMPLES = 12
        /** Shortest / longest live candidate segments (ms). */
        const val MIN_BURST_MS = 400L
        const val MAX_BURST_MS = 1_600L
    }
}

data class TypedChannelExample(
    val channels: Array<FloatArray>,
    val label: TypedLabel,
    val trialId: String = "",
)

data class TypedExample(
    val features: FloatArray,
    val label: TypedLabel,
    val trialId: String = "",
)
