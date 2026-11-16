package edu.iot.phoneangle.ml.typed

import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Learned per-frame "a tap starts here" detector. On real two-thumb data a hand-tuned energy
 * threshold reached F1 ≈ 0.44–0.62; this reached ≈ 0.92 (5-fold CV by query), because it
 * learns the phone's own tap shape (screen-normal accel dip + rebound, gyro swing) and
 * ignores finger-lift and hand-motion bumps.
 *
 * Input: a [WINDOW_FRAMES]-frame window (5 ms grid, −40…+70 ms around the frame) of 6
 * high-passed channels (ax, ay, az, gx, gy, gz), plus their absolute values — [DIM] numbers.
 * Logistic regression; standardization is folded into [w] and [b]. ~264 multiply-adds a frame.
 */
class TapDetector(
    val w: FloatArray,
    val b: Float,
    /** Probability a frame must exceed to become a tap candidate; picked on validation. */
    val threshold: Float = DEFAULT_THRESHOLD,
) {
    init {
        require(w.size == DIM)
    }

    fun probability(window: FloatArray): Float {
        var s = b
        for (i in 0 until DIM) s += w[i] * window[i]
        return (1.0 / (1.0 + exp(-s.toDouble()))).toFloat()
    }

    fun withThreshold(t: Float) = TapDetector(w, b, t)

    companion object {
        const val CHANNELS = 6
        const val PRE_FRAMES = 8
        const val POST_FRAMES = 14
        const val WINDOW_FRAMES = PRE_FRAMES + POST_FRAMES
        const val DIM = WINDOW_FRAMES * CHANNELS * 2
        const val FRAME_NS = 5_000_000L
        const val HP_TAU_S = 0.15f
        /** Two candidates closer than this are one tap (keeps the stronger). */
        const val MIN_GAP_FRAMES = 12
        const val DEFAULT_THRESHOLD = 0.7f
        /** Low values matter: real-keyboard taps are softer than training-keyboard taps. */
        val THRESHOLD_GRID = floatArrayOf(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f)
        /** A frame within ±this of a touch-down is a positive example. */
        const val LABEL_TOLERANCE_NS = 7_500_000L
        const val MIN_TRAIN_TOUCHES = 60

        /**
         * Class-balanced, L2-regularized logistic regression by batch gradient descent with
         * Nesterov momentum (converges in far fewer passes — training runs on the phone).
         * Rows are standardized internally and the scaling folded into the returned weights.
         */
        fun fit(rows: List<FloatArray>, labels: BooleanArray, iters: Int = 60): TapDetector {
            val n = rows.size
            val mu = DoubleArray(DIM)
            val sd = DoubleArray(DIM)
            for (r in rows) for (i in 0 until DIM) mu[i] += r[i].toDouble()
            for (i in 0 until DIM) mu[i] /= n
            for (r in rows) for (i in 0 until DIM) { val d = r[i] - mu[i]; sd[i] += d * d }
            for (i in 0 until DIM) sd[i] = sqrt(sd[i] / n) + 1e-6
            val z = Array(n) { r -> FloatArray(DIM) { i -> ((rows[r][i] - mu[i]) / sd[i]).toFloat() } }

            val pos = labels.count { it }.coerceAtLeast(1)
            val wPos = 0.5 / pos * n
            val wNeg = 0.5 / (n - pos).coerceAtLeast(1) * n
            val w = DoubleArray(DIM)
            var b = 0.0
            val vw = DoubleArray(DIM)
            var vb = 0.0
            val look = DoubleArray(DIM)
            val grad = DoubleArray(DIM)
            val lr = 0.5
            val mom = 0.9
            val l2 = 1e-2
            repeat(iters) {
                for (i in 0 until DIM) look[i] = w[i] + mom * vw[i]
                val lookB = b + mom * vb
                grad.fill(0.0)
                var gb = 0.0
                for (r in 0 until n) {
                    val row = z[r]
                    var s = lookB
                    for (i in 0 until DIM) s += look[i] * row[i]
                    val p = 1.0 / (1.0 + exp(-s))
                    val e = (p - if (labels[r]) 1.0 else 0.0) * if (labels[r]) wPos else wNeg
                    for (i in 0 until DIM) grad[i] += e * row[i]
                    gb += e
                }
                for (i in 0 until DIM) {
                    vw[i] = mom * vw[i] - lr * (grad[i] / n + l2 * look[i])
                    w[i] += vw[i]
                }
                vb = mom * vb - lr * gb / n
                b += vb
            }
            // Fold standardization: w·(x−μ)/σ + b = (w/σ)·x + (b − Σ wμ/σ).
            var bias = b
            val wf = FloatArray(DIM) { i ->
                bias -= w[i] * mu[i] / sd[i]
                (w[i] / sd[i]).toFloat()
            }
            return TapDetector(wf, bias.toFloat())
        }

        /** Deterministic negative subsampling so training stays small on the phone. */
        fun keepNegative(rng: Random): Boolean = rng.nextFloat() < NEGATIVE_KEEP

        private const val NEGATIVE_KEEP = 0.15f
    }
}
