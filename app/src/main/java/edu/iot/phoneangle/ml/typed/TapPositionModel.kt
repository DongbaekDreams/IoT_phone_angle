package edu.iot.phoneangle.ml.typed

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Predicts where on the keyboard a tap landed (normalized x, y) from its [TapStream] features.
 *
 * Two ridge regressions on standardized features. Weights ([wx], [bx], [wy], [by]) are the
 * shareable part for FedAvg; the scaler stays per-device (FedBN-style) because it absorbs
 * phone weight / sensor gain / grip differences.
 */
class TapPositionModel(
    val scaler: TapScaler,
    val wx: FloatArray,
    var bx: Float,
    val wy: FloatArray,
    var by: Float,
    /**
     * Calibration of the regression output (see [Calib]): predictions for taps on a key at
     * true x scatter around `alphaX·x + betaX` with spread [sigmaX] (same for y). Ridge output is
     * shrunk toward the keyboard centre, so scoring it against raw key centres would favour
     * central keys (g, h, y) over edge keys (e, o) — this is what made google lose to googly.
     */
    val sigmaX: Float,
    val sigmaY: Float,
    /** Distance between scaled feature vectors of two taps on the same key vs different keys. */
    val sameKey: Gauss,
    val diffKey: Gauss,
    /**
     * Logistic "right thumb pressed" classifier (two-thumb typing). Empty = not trained
     * (too few two-thumb taps), and the matcher then skips the thumb term.
     */
    val wt: FloatArray = FloatArray(0),
    var bt: Float = 0f,
    /** Tap-to-tap movement model; null when there were too few consecutive tap pairs. */
    val move: MoveModel? = null,
    val alphaX: Float = 1f,
    val betaX: Float = 0f,
    val alphaY: Float = 1f,
    val betaY: Float = 0f,
) {
    /** Fitted relation `prediction ≈ alpha·truth + beta`, residual spread [sigma]. */
    data class Calib(val alpha: Float, val beta: Float, val sigma: Float)

    /** Most likely key for a predicted position under the calibration (uniform key prior). */
    fun likelyKey(px: Float, py: Float): KeyboardLayout.Key = KeyboardLayout.keys.maxBy { k ->
        val sx2 = sigmaX * sigmaX + alphaX * alphaX * k.width * k.width / 12f
        val sy2 = sigmaY * sigmaY
        val dx = px - (alphaX * k.cx + betaX)
        val dy = py - (alphaY * k.cy + betaY)
        -0.5f * (dx * dx / sx2 + dy * dy / sy2) - 0.25f * kotlin.math.ln(sx2)
    }

    /**
     * Predicts how far the finger moved between two consecutive taps (Δx, Δy) from the
     * difference of their scaled features. Grip-dependent offsets that bias absolute positions
     * cancel in the difference, and the gap between the taps does not matter.
     */
    class MoveModel(
        val wdx: FloatArray,
        var bdx: Float,
        val wdy: FloatArray,
        var bdy: Float,
        val sigmaDx: Float,
        val sigmaDy: Float,
        /** Calibration: predicted Δ ≈ alpha·true Δ + beta (see [TapPositionModel.alphaX]). */
        val alphaDx: Float = 1f,
        val betaDx: Float = 0f,
        val alphaDy: Float = 1f,
        val betaDy: Float = 0f,
    ) {
        private val diff = FloatArray(wdx.size)

        /** Not thread-safe (reuses a scratch buffer). */
        fun predict(scaledA: FloatArray, scaledB: FloatArray): Pair<Float, Float> {
            for (i in diff.indices) diff[i] = scaledB[i] - scaledA[i]
            return dot(wdx, diff) + bdx to dot(wdy, diff) + bdy
        }
    }

    data class Gauss(val mean: Float, val std: Float) {
        fun logPdf(v: Float): Float {
            val s = std.coerceAtLeast(1e-3f)
            val z = (v - mean) / s
            return -0.5f * z * z - kotlin.math.ln(s)
        }
    }

    val featureDim: Int get() = wx.size

    private val scratch = FloatArray(TapStream.FEATURE_DIM)
    private val expanded = FloatArray(wx.size)

    /**
     * Position for a raw feature vector. Not thread-safe (reuses scratch buffers).
     * Weights sized [EXPANDED_DIM] use tilt interactions; plain [TapStream.FEATURE_DIM] weights don't.
     */
    fun predict(features: FloatArray): Pair<Float, Float> {
        scaler.transformInto(features, scratch)
        val x = if (wx.size == EXPANDED_DIM) expand(scratch, features, expanded) else scratch
        return dot(wx, x) + bx to dot(wy, x) + by
    }

    fun scaled(features: FloatArray): FloatArray = scaler.transform(features)

    val hasThumb: Boolean get() = wt.isNotEmpty()

    /** P(right thumb) for a tap, or NaN without a thumb classifier. */
    fun rightThumb(scaledFeatures: FloatArray): Float =
        if (!hasThumb) Float.NaN else sigmoid(dot(wt, scaledFeatures) + bt)

    companion object {
        /**
         * Tilt interactions: the same tap moves the phone differently when it is held upright vs.
         * leaning back (real data: a model from one posture predicted the other at R² ≈ 0.2 / 0).
         * Each non-gravity feature is also multiplied by the gravity direction's y and z parts,
         * so every slope can change with tilt. Pooled over two postures this raised x / y R²
         * from 0.51 / 0.25 to 0.59 / 0.32.
         */
        private val TILT_FEATURES = (0 until TapStream.FEATURE_DIM).filter { it !in 18..20 }.toIntArray()
        val EXPANDED_DIM = TapStream.FEATURE_DIM + 2 * TILT_FEATURES.size

        fun expand(scaled: FloatArray, raw: FloatArray, out: FloatArray = FloatArray(EXPANDED_DIM)): FloatArray {
            val gy = raw[19]
            val gz = raw[20]
            System.arraycopy(scaled, 0, out, 0, TapStream.FEATURE_DIM)
            var o = TapStream.FEATURE_DIM
            for (i in TILT_FEATURES) out[o++] = scaled[i] * gy
            for (i in TILT_FEATURES) out[o++] = scaled[i] * gz
            return out
        }

        /** Taps this close to the keyboard centre are ambiguous thumb labels — not trained on. */
        const val THUMB_CENTER_BAND = 0.12f
        const val MIN_THUMB_TAPS_PER_SIDE = 20
        const val GRIP_TWO_THUMBS = "two_thumbs"

        fun sigmoid(z: Float): Float = 1f / (1f + exp(-z))

        /**
         * Expected thumb for a key in two-thumb typing: left half → left thumb, right half → right,
         * soft near the middle (g / h / t / y / b vary by person).
         */
        fun keyRightThumb(cx: Float): Float = sigmoid((cx - 0.5f) / 0.07f)

        internal fun dot(w: FloatArray, f: FloatArray): Float {
            var s = 0f
            for (i in w.indices) s += w[i] * f[i]
            return s
        }

        val LAMBDA_GRID = floatArrayOf(0.3f, 1f, 3f, 10f, 30f, 100f)

        /**
         * Fit on labeled taps. Ridge strength is picked by 4-fold CV on these taps only,
         * so held-out trials never influence it.
         */
        fun fit(taps: List<LabeledTap>): TapPositionModel {
            require(taps.size >= 10) { "Need at least 10 labeled taps (have ${taps.size})" }
            val scaler = TapScaler().also { it.fit(taps.map { t -> t.features }) }
            val xs = taps.map { scaler.transform(it.features) }
            val xe = taps.indices.map { expand(xs[it], taps[it].features) }
            val tx = FloatArray(taps.size) { taps[it].x }
            val ty = FloatArray(taps.size) { taps[it].y }
            val lambda = LAMBDA_GRID.minBy { cvError(xe, tx, ty, it) }
            val (wx, bx) = ridge(xe, tx, lambda)
            val (wy, by) = ridge(xe, ty, lambda)

            // Calibrate on out-of-fold predictions (folds by query), so the spread is honest.
            val folds = foldsByTrial(taps.map { it.trialId })
            val cx = calibrate(oofPredict(xe, tx, lambda, folds), tx)
            val cy = calibrate(oofPredict(xe, ty, lambda, folds), ty)

            val (same, diff) = keyDistances(taps.map { it.key }, xs)
            val (wt, bt) = fitThumb(taps, xs) ?: (FloatArray(0) to 0f)
            val move = fitMove(taps, xs)
            return TapPositionModel(
                scaler, wx, bx, wy, by, cx.sigma, cy.sigma, same, diff, wt, bt, move,
                cx.alpha, cx.beta, cy.alpha, cy.beta,
            )
        }

        private const val CAL_FOLDS = 4

        private fun foldsByTrial(trialIds: List<String>): IntArray =
            IntArray(trialIds.size) { abs(trialIds[it].hashCode() / 7) % CAL_FOLDS }

        /** Out-of-fold ridge predictions (each row predicted by a model that never saw its query). */
        private fun oofPredict(xs: List<FloatArray>, t: FloatArray, lambda: Float, folds: IntArray): FloatArray {
            val out = FloatArray(xs.size)
            if (folds.distinct().size < 2) {
                val (w, b) = ridge(xs, t, lambda)
                for (i in xs.indices) out[i] = dot(w, xs[i]) + b
                return out
            }
            for (f in 0 until CAL_FOLDS) {
                val trainIdx = xs.indices.filter { folds[it] != f }
                val testIdx = xs.indices.filter { folds[it] == f }
                if (testIdx.isEmpty() || trainIdx.size < 10) continue
                val (w, b) = ridge(trainIdx.map { xs[it] }, FloatArray(trainIdx.size) { t[trainIdx[it]] }, lambda)
                for (i in testIdx) out[i] = dot(w, xs[i]) + b
            }
            return out
        }

        /** Least-squares `pred ≈ alpha·truth + beta` and the residual spread. */
        fun calibrate(pred: FloatArray, truth: FloatArray): Calib {
            val n = pred.size
            val mt = truth.average().toFloat()
            val mp = pred.average().toFloat()
            var cov = 0f
            var vt = 0f
            for (i in 0 until n) {
                cov += (truth[i] - mt) * (pred[i] - mp)
                vt += (truth[i] - mt) * (truth[i] - mt)
            }
            val alpha = if (vt <= 1e-9f) 1f else (cov / vt).coerceIn(0.05f, 1.5f)
            val beta = mp - alpha * mt
            var r = 0f
            for (i in 0 until n) {
                val e = pred[i] - (alpha * truth[i] + beta)
                r += e * e
            }
            return Calib(alpha, beta, sqrt(r / n.coerceAtLeast(1)).coerceAtLeast(MIN_SIGMA))
        }

        private const val MIN_SIGMA = 0.01f

        const val MIN_MOVE_PAIRS = 20
        /** Consecutive taps further apart than this are not treated as one movement. */
        const val MAX_PAIR_GAP_NS = 1_500_000_000L

        /** Ridge on consecutive-tap feature differences → touch displacement (same trial only). */
        private fun fitMove(taps: List<LabeledTap>, xs: List<FloatArray>): MoveModel? {
            val inputs = ArrayList<FloatArray>()
            val dx = ArrayList<Float>()
            val dy = ArrayList<Float>()
            val pairTrial = ArrayList<String>()
            val byTrial = taps.indices.filter { taps[it].tNs > 0L }.groupBy { taps[it].trialId }
            for (idx in byTrial.values) {
                val ordered = idx.sortedBy { taps[it].tNs }
                for (k in 1 until ordered.size) {
                    val a = ordered[k - 1]
                    val b = ordered[k]
                    if (taps[b].tNs - taps[a].tNs > MAX_PAIR_GAP_NS) continue
                    inputs += FloatArray(xs[a].size) { xs[b][it] - xs[a][it] }
                    dx += taps[b].x - taps[a].x
                    dy += taps[b].y - taps[a].y
                    pairTrial += taps[a].trialId
                }
            }
            if (inputs.size < MIN_MOVE_PAIRS) return null
            val tx = dx.toFloatArray()
            val ty = dy.toFloatArray()
            val lambda = LAMBDA_GRID.minBy { cvError(inputs, tx, ty, it) }
            val (wdx, bdx) = ridge(inputs, tx, lambda)
            val (wdy, bdy) = ridge(inputs, ty, lambda)
            val folds = foldsByTrial(pairTrial)
            val cx = calibrate(oofPredict(inputs, tx, lambda, folds), tx)
            val cy = calibrate(oofPredict(inputs, ty, lambda, folds), ty)
            return MoveModel(wdx, bdx, wdy, bdy, cx.sigma, cy.sigma, cx.alpha, cx.beta, cy.alpha, cy.beta)
        }

        /** Thumb label for a training tap: side of the keyboard, two-thumb sessions only. */
        fun thumbLabel(tap: LabeledTap): Boolean? {
            if (tap.grip != GRIP_TWO_THUMBS || abs(tap.x - 0.5f) < THUMB_CENTER_BAND) return null
            return tap.x > 0.5f
        }

        /** L2-regularized logistic regression by batch gradient descent (22 dims — instant). */
        private fun fitThumb(taps: List<LabeledTap>, xs: List<FloatArray>): Pair<FloatArray, Float>? {
            val idx = taps.indices.filter { thumbLabel(taps[it]) != null }
            val right = idx.count { thumbLabel(taps[it]) == true }
            if (right < MIN_THUMB_TAPS_PER_SIDE || idx.size - right < MIN_THUMB_TAPS_PER_SIDE) return null
            val d = xs[0].size
            val w = FloatArray(d)
            var b = 0f
            val grad = FloatArray(d)
            repeat(300) {
                grad.fill(0f)
                var gb = 0f
                for (i in idx) {
                    val err = sigmoid(dot(w, xs[i]) + b) - if (thumbLabel(taps[i]) == true) 1f else 0f
                    for (f in 0 until d) grad[f] += err * xs[i][f]
                    gb += err
                }
                for (f in 0 until d) w[f] -= 0.5f * (grad[f] / idx.size + 0.01f * w[f])
                b -= 0.5f * gb / idx.size
            }
            return w to b
        }

        fun distance(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            // Gravity / posture features are near-identical for any two taps — skip them.
            for (i in a.indices) {
                if (i in 18..20) continue
                val d = a[i] - b[i]
                s += d * d
            }
            return sqrt(s)
        }

        private fun keyDistances(keys: List<String>, xs: List<FloatArray>): Pair<Gauss, Gauss> {
            val same = ArrayList<Float>()
            val diff = ArrayList<Float>()
            val n = xs.size
            val stride = maxOf(1, n / 300)
            for (i in 0 until n step stride) {
                for (j in i + 1 until n step stride) {
                    val d = distance(xs[i], xs[j])
                    if (keys[i] == keys[j]) same += d else diff += d
                }
            }
            fun g(v: List<Float>, fallback: Gauss): Gauss {
                if (v.size < 5) return fallback
                val m = v.average().toFloat()
                val sd = sqrt(v.map { (it - m) * (it - m) }.average().toFloat())
                return Gauss(m, sd.coerceAtLeast(0.1f))
            }
            val d = g(diff, Gauss(6f, 2f))
            // Without enough same-key pairs, assume "a bit closer than average" so the bonus stays mild.
            return g(same, Gauss(d.mean * 0.8f, d.std)) to d
        }

        private fun cvError(xs: List<FloatArray>, tx: FloatArray, ty: FloatArray, lambda: Float): Float {
            val k = 4
            if (xs.size < k * 5) return abs(lambda - 10f) // too small to CV — prefer a moderate, fixed λ
            var err = 0f
            for (fold in 0 until k) {
                val trainIdx = xs.indices.filter { it % k != fold }
                val testIdx = xs.indices.filter { it % k == fold }
                val trX = trainIdx.map { xs[it] }
                val (wx, bx) = ridge(trX, FloatArray(trainIdx.size) { tx[trainIdx[it]] }, lambda)
                val (wy, by) = ridge(trX, FloatArray(trainIdx.size) { ty[trainIdx[it]] }, lambda)
                for (i in testIdx) {
                    val dx = dot(wx, xs[i]) + bx - tx[i]
                    // y errors count more: a row mistake is worse than a neighbour-key mistake.
                    val dy = (dot(wy, xs[i]) + by - ty[i]) * (KeyboardLayout.KEY_W / KeyboardLayout.KEY_H)
                    err += dx * dx + dy * dy
                }
            }
            return err
        }

        /** Closed-form ridge with an unpenalized intercept (inputs already standardized). */
        fun ridge(xs: List<FloatArray>, y: FloatArray, lambda: Float): Pair<FloatArray, Float> {
            val d = xs[0].size
            val yMean = y.average().toFloat()
            val a = Array(d) { DoubleArray(d) }
            val b = DoubleArray(d)
            val xMean = DoubleArray(d)
            for (row in xs) for (i in 0 until d) xMean[i] += row[i].toDouble()
            for (i in 0 until d) xMean[i] /= xs.size
            for ((r, row) in xs.withIndex()) {
                val yc = (y[r] - yMean).toDouble()
                for (i in 0 until d) {
                    val xi = row[i] - xMean[i]
                    b[i] += xi * yc
                    for (j in i until d) a[i][j] += xi * (row[j] - xMean[j])
                }
            }
            for (i in 0 until d) {
                for (j in 0 until i) a[i][j] = a[j][i]
                a[i][i] += lambda.toDouble()
            }
            val w = solve(a, b)
            var bias = yMean.toDouble()
            for (i in 0 until d) bias -= w[i] * xMean[i]
            return FloatArray(d) { w[it].toFloat() } to bias.toFloat()
        }

        /** Gaussian elimination with partial pivoting (d is ~22, so this is trivial). */
        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val n = b.size
            val m = Array(n) { a[it].copyOf() }
            val v = b.copyOf()
            for (col in 0 until n) {
                var piv = col
                for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
                val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
                val tv = v[col]; v[col] = v[piv]; v[piv] = tv
                val p = m[col][col]
                if (abs(p) < 1e-12) continue
                for (r in col + 1 until n) {
                    val f = m[r][col] / p
                    if (f == 0.0) continue
                    for (c in col until n) m[r][c] -= f * m[col][c]
                    v[r] -= f * v[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = v[r]
                for (c in r + 1 until n) s -= m[r][c] * x[c]
                x[r] = if (abs(m[r][r]) < 1e-12) 0.0 else s / m[r][r]
            }
            return x
        }
    }
}

/** One tap with its ground-truth touch position from the training keyboard. */
data class LabeledTap(
    val features: FloatArray,
    val x: Float,
    val y: Float,
    val key: String,
    val trialId: String,
    /** Trial grip tag (e.g. "two_thumbs"); only two-thumb taps train the thumb classifier. */
    val grip: String = "",
    /** Touch-down time; orders taps within a trial for the movement model (0 = unknown). */
    val tNs: Long = 0L,
)

/** Per-feature mean/std standardization (fit on train taps only). */
class TapScaler {
    var mu: FloatArray = FloatArray(0)
        private set
    var sigma: FloatArray = FloatArray(0)
        private set

    fun fit(rows: List<FloatArray>) {
        val d = rows[0].size
        val m = FloatArray(d)
        for (r in rows) for (i in 0 until d) m[i] += r[i]
        for (i in 0 until d) m[i] /= rows.size
        val s = FloatArray(d)
        for (r in rows) for (i in 0 until d) s[i] += (r[i] - m[i]) * (r[i] - m[i])
        for (i in 0 until d) s[i] = sqrt(s[i] / rows.size) + 1e-6f
        mu = m
        sigma = s
    }

    fun load(mu: FloatArray, sigma: FloatArray) {
        this.mu = mu.copyOf()
        this.sigma = sigma.copyOf()
    }

    fun transformInto(row: FloatArray, out: FloatArray) {
        for (i in row.indices) out[i] = (row[i] - mu[i]) / sigma[i]
    }

    fun transform(row: FloatArray): FloatArray = FloatArray(row.size).also { transformInto(row, it) }
}
