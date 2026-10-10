package edu.iot.phoneangle.ml.typed

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Cheap time-domain augmentations so live training sees speed variation and
 * typo-like hesitations / corrections — without reading the keyboard.
 */
object TypedAugment {

    /**
     * Linear time-warp along the sample axis (rate &lt; 1 = slower typing, &gt; 1 = faster).
     * Output stays length [MiniRocketTransform.INPUT_LENGTH].
     */
    fun timeWarp(channels: Array<FloatArray>, rate: Float): Array<FloatArray> {
        val n = MiniRocketTransform.INPUT_LENGTH
        require(channels.size == MiniRocketTransform.N_CHANNELS)
        require(rate in 0.55f..1.8f)
        val out = Array(MiniRocketTransform.N_CHANNELS) { FloatArray(n) }
        for (i in 0 until n) {
            val src = (i / rate).coerceIn(0f, (n - 1).toFloat())
            val i0 = src.toInt()
            val i1 = min(n - 1, i0 + 1)
            val t = src - i0
            for (c in 0 until MiniRocketTransform.N_CHANNELS) {
                out[c][i] = channels[c][i0] * (1f - t) + channels[c][i1] * t
            }
        }
        return out
    }

    /**
     * Mid-word plateau (hesitation / backspace pause), then resume.
     * IMU-only proxy for typo corrections — no key identities.
     */
    fun hesitation(channels: Array<FloatArray>, atFrac: Float = 0.45f, holdFrac: Float = 0.12f): Array<FloatArray> {
        val n = MiniRocketTransform.INPUT_LENGTH
        val holdAt = (atFrac * (n - 1)).toInt().coerceIn(2, n - 4)
        val holdLen = max(2, (holdFrac * n).toInt())
        val out = Array(MiniRocketTransform.N_CHANNELS) { FloatArray(n) }
        for (c in 0 until MiniRocketTransform.N_CHANNELS) {
            val src = channels[c]
            var si = 0
            for (i in 0 until n) {
                when {
                    i < holdAt -> {
                        out[c][i] = src[si++]
                    }
                    i < holdAt + holdLen -> {
                        out[c][i] = src[holdAt]
                    }
                    else -> {
                        val idx = min(n - 1, si++)
                        out[c][i] = src[idx]
                    }
                }
            }
        }
        return out
    }

    /** Expand a base example into speed / hesitation variants (same label). */
    fun expand(
        base: TypedChannelExample,
        rng: Random = Random.Default,
        rates: FloatArray = floatArrayOf(0.55f, 0.70f, 0.85f, 1.15f, 1.35f, 1.55f),
    ): List<TypedChannelExample> {
        if (base.label == TypedLabel.IDLE) return listOf(base)
        val out = ArrayList<TypedChannelExample>(1 + rates.size + 1)
        out += base
        for (r in rates) out += base.copy(channels = timeWarp(base.channels, r), trialId = base.trialId)
        if (base.label == TypedLabel.GOOGLE || base.label == TypedLabel.YAHOO) {
            val at = 0.35f + rng.nextFloat() * 0.3f
            out += base.copy(channels = hesitation(base.channels, atFrac = at), trialId = base.trialId)
        }
        return out
    }
}
