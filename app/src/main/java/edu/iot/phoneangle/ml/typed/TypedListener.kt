package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.data.SensorSample

/**
 * Event-driven IMU-only target-word flagger.
 *
 * Stage A proposes typing bursts (long quiet gap so slow typing / typos stay one burst).
 * Stage B prefers the **snipped / Play model** on the burst (same features that work
 * statically), with optional live model as fallback. Several time-warps are scored at
 * inference so slow/fast typing still match.
 */
class TypedListener(
    private var wordBundle: TypedModelBundle? = null,
    private var liveBundle: TypedModelBundle? = null,
    private val maxWindowMs: Long = TypedWindowing.LIVE_WINDOW_MS,
    private val flagThreshold: Float = MIN_CONFIDENCE,
    /** Default >1 disables progressive early flags — wait for a completed burst. */
    private val earlyThreshold: Float = EARLY_CONFIDENCE,
    private val minMargin: Float = 0.10f,
    private val earlyMargin: Float = 0.15f,
) {
    private val buffer = ArrayDeque<SensorSample>()
    private val detector = TypingActivityDetector()
    private val preprocessor = TypedPreprocessor()

    private val channelScratch = Array(MiniRocketTransform.N_CHANNELS) {
        FloatArray(MiniRocketTransform.INPUT_LENGTH)
    }
    private val featureScratch = FloatArray(TypedPreprocessor.FEATURE_DIM)
    private val scaledScratch = FloatArray(TypedPreprocessor.FEATURE_DIM)

    private var lastOfferNs: Long = Long.MIN_VALUE / 2
    private var scoredCheckpoint = 0
    private var burstFlagged = false
    private var earlyHitLabel: TypedLabel? = null
    private var earlyHitCount = 0
    private var lastInferMs: Long = -1L
    private var inferCount = 0L
    private var inferTotalNs = 0L
    private var proposalCount = 0L

    val hasModel: Boolean get() = wordBundle != null || liveBundle != null
    val usingSnipped: Boolean get() = wordBundle != null
    val lastInferenceMs: Long get() = lastInferMs
    val inferenceCount: Long get() = inferCount
    val meanInferenceMs: Float
        get() = if (inferCount == 0L) 0f else (inferTotalNs / inferCount) / 1_000_000f
    val proposals: Long get() = proposalCount

    private val effectiveMaxMs: Long
        get() = liveBundle?.windowMs?.takeIf { it > 0 }
            ?: wordBundle?.windowMs?.takeIf { it > 0 }
            ?: maxWindowMs

    fun setModels(word: TypedModelBundle?, live: TypedModelBundle?) {
        wordBundle = word
        liveBundle = live
        reset()
    }

    /** @deprecated Prefer [setModels]. */
    fun setModel(bundle: TypedModelBundle?) {
        liveBundle = bundle
        reset()
    }

    fun reset() {
        synchronized(buffer) { buffer.clear() }
        detector.reset()
        lastOfferNs = Long.MIN_VALUE / 2
        scoredCheckpoint = 0
        burstFlagged = false
        earlyHitLabel = null
        earlyHitCount = 0
        lastInferMs = -1L
        inferCount = 0L
        inferTotalNs = 0L
        proposalCount = 0L
    }

    fun offer(sample: SensorSample) {
        if (sample.timestampNs - lastOfferNs < MIN_SPACING_NS) return
        lastOfferNs = sample.timestampNs
        detector.offer(sample)
        synchronized(buffer) {
            buffer.addLast(sample)
            val cutoff = sample.timestampNs - (effectiveMaxMs + 2_000L) * 1_000_000L
            while (buffer.isNotEmpty() && buffer.first().timestampNs < cutoff) buffer.removeFirst()
        }
    }

    fun step(): TypedListenTick {
        if (!hasModel) {
            return TypedListenTick(null, null, null, ListenPhase.WARMING, lastInferMs)
        }
        val samples = synchronized(buffer) { buffer.toList() }
        if (samples.size < TypedPreprocessor.MIN_SAMPLES) {
            return TypedListenTick(null, null, null, ListenPhase.WARMING, lastInferMs)
        }

        val done = detector.pollCompleted()
        if (done != null) {
            val already = burstFlagged
            scoredCheckpoint = 0
            earlyHitLabel = null
            earlyHitCount = 0
            burstFlagged = false
            if (already) {
                return TypedListenTick(null, null, null, ListenPhase.IDLE, lastInferMs)
            }
            proposalCount++
            return classifyBurst(samples, done.startNs, done.endNs, early = false)
        }

        val active = detector.peekActive()
        if (active == null) {
            return TypedListenTick(null, null, null, ListenPhase.IDLE, lastInferMs)
        }

        val dur = active.durationMs
        val cp = nextCheckpoint(dur)
        if (cp != null && cp > scoredCheckpoint && !burstFlagged) {
            scoredCheckpoint = cp
            proposalCount++
            val endNs = active.startNs + cp * 1_000_000L
            return classifyBurst(samples, active.startNs, minOf(endNs, active.lastNs), early = true)
        }

        return TypedListenTick(null, null, null, ListenPhase.ACTIVE, lastInferMs)
    }

    private fun nextCheckpoint(durationMs: Long): Int? {
        for (cp in CHECKPOINTS_MS) {
            if (durationMs >= cp && cp > scoredCheckpoint) return cp
        }
        return null
    }

    private fun classifyBurst(
        samples: List<SensorSample>,
        startNs: Long,
        endNs: Long,
        early: Boolean,
    ): TypedListenTick {
        val t0 = System.nanoTime()
        if (!preprocessor.channelsFromSpanInto(samples, startNs, endNs, channelScratch)) {
            val durMs = ((endNs - startNs) / 1_000_000L)
                .coerceIn(TypedPreprocessor.MIN_BURST_MS, effectiveMaxMs)
            if (!preprocessor.channelsFromWindowInto(samples, endNs, durMs, channelScratch)) {
                return TypedListenTick(null, null, null, ListenPhase.ACTIVE, lastInferMs)
            }
        }

        val baseChannels = Array(MiniRocketTransform.N_CHANNELS) { c -> channelScratch[c].copyOf() }
        val bundle = wordBundle ?: liveBundle
            ?: return TypedListenTick(null, null, null, ListenPhase.WARMING, lastInferMs)

        // Average class probabilities across time-warps (slow↔fast) — more stable than max.
        val sum = linkedMapOf<TypedLabel, Float>()
        var nWarp = 0
        for (rate in INFER_WARPS) {
            val ch = if (rate == 1f) baseChannels else TypedAugment.timeWarp(baseChannels, rate)
            preprocessor.featuresFromChannelsInto(ch, bundle.rocketBiases, featureScratch)
            bundle.scaler.transformInto(featureScratch, scaledScratch)
            val pred = bundle.model.predict(scaledScratch)
            for ((lab, p) in pred.probabilities) {
                sum[lab] = (sum[lab] ?: 0f) + p
            }
            nWarp++
        }
        val proba = sum.mapValues { it.value / nWarp }
        var bestLab = TypedLabel.OTHER
        var bestP = -1f
        for ((lab, p) in proba) {
            if (p > bestP) {
                bestP = p
                bestLab = lab
            }
        }
        val raw = TypedPrediction(bestLab, bestP, proba)

        val elapsed = System.nanoTime() - t0
        lastInferMs = elapsed / 1_000_000L
        inferCount++
        inferTotalNs += elapsed

        val phase = when (raw.label) {
            TypedLabel.IDLE -> ListenPhase.IDLE
            else -> ListenPhase.ACTIVE
        }
        val alert = decideFlag(raw, early)
        if (alert != null) burstFlagged = true
        return TypedListenTick(raw, alert, proba, phase, lastInferMs)
    }

    private fun decideFlag(raw: TypedPrediction, early: Boolean): TypedLabel? {
        val g = raw.probabilities[TypedLabel.GOOGLE] ?: 0f
        val y = raw.probabilities[TypedLabel.YAHOO] ?: 0f
        val label = when {
            g >= y && g > 0f -> TypedLabel.GOOGLE
            y > g -> TypedLabel.YAHOO
            else -> return null
        }
        val conf = maxOf(g, y)
        val other = raw.probabilities.filterKeys { it != label }.values.maxOrNull() ?: 0f
        val margin = conf - other
        if (early) {
            val strong = conf >= earlyThreshold && margin >= earlyMargin
            if (!strong) {
                earlyHitLabel = null
                earlyHitCount = 0
                return null
            }
            if (label == earlyHitLabel) earlyHitCount++ else {
                earlyHitLabel = label
                earlyHitCount = 1
            }
            return if (earlyHitCount >= 2) label else null
        }
        return if (conf >= flagThreshold && margin >= minMargin) label else null
    }

    companion object {
        const val MIN_CONFIDENCE = 0.55f
        /** Disabled by default (>1): only completed bursts flag until the model is stronger. */
        const val EARLY_CONFIDENCE = 1.01f

        private const val MIN_SPACING_NS = 15_000_000L
        private val CHECKPOINTS_MS = intArrayOf(800, 1200, 1800)
        /** Inference time-warps — covers slow → fast typing without keyboard timing. */
        private val INFER_WARPS = floatArrayOf(0.75f, 1.0f, 1.30f)

        fun forLiveListen(): TypedListener = TypedListener()
    }
}

enum class ListenPhase {
    WARMING,
    IDLE,
    ACTIVE,
}

data class TypedListenTick(
    val live: TypedPrediction?,
    val alert: TypedLabel?,
    val probabilities: Map<TypedLabel, Float>?,
    val phase: ListenPhase = ListenPhase.WARMING,
    val inferenceMs: Long = -1L,
)
