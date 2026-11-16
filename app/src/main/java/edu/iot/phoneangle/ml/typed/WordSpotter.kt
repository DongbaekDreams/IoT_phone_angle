package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.data.TouchLabel
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Decides whether one search query's taps spell a target word — keyword–filler scoring.
 *
 * Each tap carries a predicted keyboard position. For every candidate word we find the best
 * alignment of taps to that word's key path (Viterbi over match / extra tap / missed tap), and
 * compare it with a background "any other query" model (letter-frequency mixture over all
 * keys). The log-likelihood ratio says how much better the word explains the taps.
 *
 * - Neighbour-key confusion (o/p, g/h) is built in: emissions are Gaussians around key centres,
 *   so "goohle"-looking taps still score well for google.
 * - Repeated letters (the oo in both targets, g…g in google) add a device-independent bonus:
 *   taps aligned to the same key should have similar raw motion.
 * - Double letters typed back-to-back (oo) get a one-sided timing bonus when the gap is short
 *   relative to this query's typical gap. Slow or hesitant gaps score zero, never a penalty.
 * - Thumb side (two-thumb typing): each tap's P(right thumb) is compared with the side the key
 *   is normally typed with, so google ≈ L R R L R L and yahoo ≈ R L R R R add evidence.
 * - Movement (Δx, Δy) between consecutive taps is compared with the step between consecutive
 *   letters (g→o: up-right, o→o: none). Grip offsets cancel in the difference and gaps between
 *   taps do not matter.
 *   The extras are weighted by [weights], fitted on validation data (0 = off).
 * - Real lookalike words ([COMPETITORS]) are scored too; a target must beat them as well.
 *
 * Text is never reconstructed — only fixed word hypotheses are scored.
 */
class WordSpotter(
    private val model: TapPositionModel,
    /** Required margin (nats) over background and competitors; calibrated in training. */
    val threshold: Float = DEFAULT_THRESHOLD,
    val weights: Weights = Weights(),
) {
    /** Strength of the optional evidence terms (nats at full effect). */
    data class Weights(val thumb: Float = 0f, val doubleKeyTiming: Float = 0f, val movement: Float = 0f)

    /**
     * One detected tap: predicted position, scaled features (repeat-letter check), onset time
     * (double-key timing) and P(right thumb) (NaN when there is no thumb classifier).
     */
    data class Obs(
        val x: Float,
        val y: Float,
        val scaled: FloatArray,
        val tNs: Long = 0L,
        val pRight: Float = Float.NaN,
    )

    data class Result(
        val label: TypedLabel,
        /**
         * Log-odds of "a target" vs "anything else": best target LLR minus
         * log(1 + Σ ρ·exp(competitor LLR)), with ρ = [COMPETITOR_PRIOR].
         */
        val margin: Float,
        val target: TypedLabel,
        val scores: Map<String, Float>,
        val tapCount: Int,
        /**
         * Display leaning over google / yahoo / other: a softmax with the targets shifted by
         * [threshold], so a target passes 50% roughly where it would be flagged.
         */
        val probabilities: Map<TypedLabel, Float> = emptyMap(),
    )

    private val keys = KeyboardLayout.keys
    private val keyIndex = keys.withIndex().associate { it.value.label to it.index }
    private val logPrior = FloatArray(keys.size) { i ->
        ln((KeyboardLayout.backgroundPrior[keys[i].label] ?: 0.001f).coerceAtLeast(1e-4f))
    }
    private val keyRight = FloatArray(keys.size) { TapPositionModel.keyRightThumb(keys[it].cx) }

    fun observe(features: FloatArray, onsetNs: Long = 0L): Obs {
        val (x, y) = model.predict(features)
        val scaled = model.scaled(features)
        return Obs(x, y, scaled, onsetNs, model.rightThumb(scaled))
    }

    fun observe(tap: TapStream.Tap): Obs = observe(tap.features, tap.onsetNs)

    /** Score a complete query (taps until the typing pause). */
    fun score(taps: List<Obs>, prefixOk: Boolean = false): Result {
        val obs = if (taps.size > MAX_TAPS) taps.takeLast(MAX_TAPS) else taps
        val emit = emissions(obs)
        val bg = FloatArray(obs.size) { i -> logSumExp(emit[i], logPrior) }
        val bgTotal = bg.sum()
        val medianGapNs = medianGap(obs)
        val moves = moves(obs)

        val scores = linkedMapOf<String, Float>()
        for (word in TARGET_WORDS + COMPETITORS) {
            var best = Float.NEGATIVE_INFINITY
            for (variant in variants(word)) {
                val path = variant.mapNotNull { keyIndex[it] }.toIntArray()
                // Competitors get the same prefix allowance, so "gogg…" cannot early-flag as google.
                best = max(best, align(obs, emit, bg, path, prefixOk, medianGapNs, moves))
            }
            scores[word] = best - bgTotal
        }
        val g = scores.getValue(TypedWordBank.GOOGLE)
        val y = scores.getValue(TypedWordBank.YAHOO)
        val target = if (g >= y) TypedLabel.GOOGLE else TypedLabel.YAHOO
        val bestTarget = max(g, y)
        // "Other" = any query (background, LLR 0) plus lookalike words weighted by how likely
        // they are as searches relative to a blocked word. When the motion cannot separate
        // google from googly, the rarer word no longer vetoes the flag.
        var otherMax = 0f
        for (c in COMPETITORS) otherMax = max(otherMax, scores.getValue(c) + LOG_COMPETITOR_PRIOR)
        var otherSum = exp(-otherMax.toDouble())
        for (c in COMPETITORS) otherSum += exp((scores.getValue(c) + LOG_COMPETITOR_PRIOR - otherMax).toDouble())
        val other = otherMax + ln(otherSum).toFloat()
        // Either target is a blocked word, so google-vs-yahoo ambiguity does not block a flag.
        val margin = bestTarget - other
        val label = if (obs.size >= MIN_TAPS && margin >= threshold) target else TypedLabel.OTHER
        return Result(label, margin, target, scores, obs.size, leaning(g - threshold, y - threshold, other))
    }

    /**
     * emit[i][k] = log p(tap i | key k): a position Gaussian (key width folded into the x spread)
     * plus, when enabled, the thumb-side agreement term.
     */
    private fun emissions(obs: List<Obs>): Array<FloatArray> {
        val sy2 = model.sigmaY * model.sigmaY
        val ax = model.alphaX
        return Array(obs.size) { i ->
            val o = obs[i]
            val useThumb = weights.thumb > 0f && !o.pRight.isNaN()
            val p = o.pRight.coerceIn(0.05f, 0.95f)
            FloatArray(keys.size) { k ->
                val key = keys[k]
                // Calibrated: predictions for this key centre on alpha·cx + beta, not on cx.
                val sx2 = model.sigmaX * model.sigmaX + ax * ax * key.width * key.width / 12f
                val dx = o.x - (ax * key.cx + model.betaX)
                val dy = o.y - (model.alphaY * key.cy + model.betaY)
                var e = -0.5f * (dx * dx / sx2 + dy * dy / sy2) - 0.5f * ln(sx2 * sy2)
                if (useThumb) {
                    val q = keyRight[k]
                    e += weights.thumb * ln(p * q + (1f - p) * (1f - q))
                }
                e
            }
        }
    }

    /** Predicted (Δx, Δy) for each consecutive tap pair i→i+1, or null when movement is off. */
    private fun moves(obs: List<Obs>): Array<FloatArray>? {
        val mv = model.move ?: return null
        if (weights.movement <= 0f || obs.size < 2) return null
        return Array(obs.size - 1) { i ->
            val (dx, dy) = mv.predict(obs[i].scaled, obs[i + 1].scaled)
            floatArrayOf(dx, dy)
        }
    }

    /**
     * LLR of one predicted movement: "this is the step from key a to key b" vs "a step between
     * two random keys" (background spread from the letter prior).
     */
    private fun moveLlr(pred: FloatArray, from: KeyboardLayout.Key, to: KeyboardLayout.Key): Float {
        val mv = model.move!!
        val ax = mv.alphaDx
        val ay = mv.alphaDy
        val sx2 = mv.sigmaDx * mv.sigmaDx + ax * ax * (from.width * from.width + to.width * to.width) / 12f
        val sy2 = mv.sigmaDy * mv.sigmaDy
        val ex = pred[0] - (ax * (to.cx - from.cx) + mv.betaDx)
        val ey = pred[1] - (ay * (to.cy - from.cy) + mv.betaDy)
        val word = -0.5f * (ex * ex / sx2 + ey * ey / sy2) - 0.5f * ln(sx2 * sy2)
        val bx2 = sx2 + ax * ax * BG_MOVE_VAR_X
        val by2 = sy2 + ay * ay * BG_MOVE_VAR_Y
        val bxm = pred[0] - mv.betaDx
        val bym = pred[1] - mv.betaDy
        val bg = -0.5f * (bxm * bxm / bx2 + bym * bym / by2) - 0.5f * ln(bx2 * by2)
        return word - bg
    }

    /** Median gap between consecutive taps in this query, or 0 when timing is unavailable. */
    private fun medianGap(obs: List<Obs>): Long {
        if (weights.doubleKeyTiming <= 0f || obs.size < 3 || obs.any { it.tNs <= 0L }) return 0L
        val gaps = LongArray(obs.size - 1) { obs[it + 1].tNs - obs[it].tNs }.also { it.sort() }
        return gaps[gaps.size / 2]
    }

    /**
     * Viterbi alignment of taps to [path]. Returns the best total log-likelihood
     * (emissions + edit penalties + repeated-key bonus).
     */
    private fun align(
        obs: List<Obs>,
        emit: Array<FloatArray>,
        bg: FloatArray,
        path: IntArray,
        prefixOk: Boolean,
        medianGapNs: Long,
        moves: Array<FloatArray>?,
    ): Float {
        val n = obs.size
        val m = path.size
        val neg = Float.NEGATIVE_INFINITY
        val d = Array(n + 1) { FloatArray(m + 1) { neg } }
        // 0 = match, 1 = extra tap, 2 = missed tap
        val move = Array(n + 1) { ByteArray(m + 1) }
        d[0][0] = 0f
        for (i in 0..n) {
            for (j in 0..m) {
                if (i == 0 && j == 0) continue
                var best = neg
                var mv: Byte = 0
                if (i > 0 && j > 0 && d[i - 1][j - 1] > neg) {
                    val v = d[i - 1][j - 1] + emit[i - 1][path[j - 1]] + MATCH_LOGP
                    if (v > best) { best = v; mv = 0 }
                }
                if (i > 0 && d[i - 1][j] > neg) {
                    val v = d[i - 1][j] + bg[i - 1] + EXTRA_TAP_LOGP
                    if (v > best) { best = v; mv = 1 }
                }
                if (j > 0 && d[i][j - 1] > neg) {
                    val v = d[i][j - 1] + MISSED_TAP_LOGP
                    if (v > best) { best = v; mv = 2 }
                }
                d[i][j] = best
                move[i][j] = mv
            }
        }
        var endJ = m
        if (prefixOk) {
            // Early check: the query may stop partway through the word (e.g. "goog").
            for (j in MIN_PREFIX..m) if (d[n][j] > d[n][endJ]) endJ = j
        }
        if (d[n][endJ] == neg) return neg

        // Backtrack to find which tap matched each letter, then score repeated keys.
        val matchedTap = IntArray(m) { -1 }
        var i = n
        var j = endJ
        while (i > 0 || j > 0) {
            when (move[i][j].toInt()) {
                0 -> { matchedTap[j - 1] = i - 1; i--; j-- }
                1 -> i--
                else -> j--
            }
        }
        var bonus = 0f
        for (a in 0 until m) for (b in a + 1 until m) {
            if (path[a] != path[b]) continue
            val ta = matchedTap[a]
            val tb = matchedTap[b]
            if (ta < 0 || tb < 0) continue
            val dist = TapPositionModel.distance(obs[ta].scaled, obs[tb].scaled)
            val llr = model.sameKey.logPdf(dist) - model.diffKey.logPdf(dist)
            bonus += llr.coerceIn(-REPEAT_CLIP, REPEAT_CLIP)
        }
        if (medianGapNs > 0L) {
            // Double letter typed as two back-to-back taps: reward a quick repeat, ignore slow ones.
            for (a in 0 until m - 1) {
                if (path[a] != path[a + 1]) continue
                val ta = matchedTap[a]
                val tb = matchedTap[a + 1]
                if (ta < 0 || tb != ta + 1) continue
                val ratio = (obs[tb].tNs - obs[ta].tNs).toFloat() / medianGapNs
                bonus += weights.doubleKeyTiming * ((1f - ratio) / (1f - FAST_REPEAT_RATIO)).coerceIn(0f, 1f)
            }
        }
        if (moves != null) {
            // Consecutive letters typed as consecutive taps: does the hand movement fit the step?
            for (a in 1 until m) {
                val ta = matchedTap[a - 1]
                val tb = matchedTap[a]
                if (ta < 0 || tb != ta + 1) continue
                val llr = moveLlr(moves[ta], keys[path[a - 1]], keys[path[a]])
                bonus += weights.movement * llr.coerceIn(-MOVE_CLIP, MOVE_CLIP)
            }
        }
        return d[n][endJ] + bonus
    }

    private fun leaning(g: Float, y: Float, other: Float): Map<TypedLabel, Float> {
        val m = max(max(g, y), other)
        val eg = exp((g - m).toDouble())
        val ey = exp((y - m).toDouble())
        val eo = exp((other - m).toDouble())
        val z = eg + ey + eo
        return mapOf(
            TypedLabel.GOOGLE to (eg / z).toFloat(),
            TypedLabel.YAHOO to (ey / z).toFloat(),
            TypedLabel.OTHER to (eo / z).toFloat(),
        )
    }

    private fun logSumExp(e: FloatArray, logW: FloatArray): Float {
        var mx = Float.NEGATIVE_INFINITY
        for (k in e.indices) mx = max(mx, e[k] + logW[k])
        var s = 0.0
        for (k in e.indices) s += exp((e[k] + logW[k] - mx).toDouble())
        return mx + ln(s).toFloat()
    }

    companion object {
        const val DEFAULT_THRESHOLD = 4f
        const val MIN_TAPS = 3
        const val MIN_PREFIX = 4
        const val MAX_TAPS = 24

        private val MATCH_LOGP = ln(0.9f)
        /** Stray tap / typo / backspace inside the query. */
        private val EXTRA_TAP_LOGP = ln(0.06f)
        /**
         * Tap the detector missed. Real-keyboard taps are soft, so one or two of a word's taps
         * are often missed (real data: google flagged when all 7 taps were found, missed at 4–6).
         * 0.2 caught 22/37 real-keyboard blocked words vs 16/37 at 0.08, false flags ≈ unchanged.
         */
        private val MISSED_TAP_LOGP = ln(0.2f)
        private const val REPEAT_CLIP = 2.5f
        /** A repeat gap at or below this fraction of the query's median gap earns the full bonus. */
        private const val FAST_REPEAT_RATIO = 0.5f
        private const val MOVE_CLIP = 3f

        /** Variance of the step between two independent keys drawn from the letter prior. */
        private val BG_MOVE_VAR_X: Float by lazy { 2f * priorVariance { it.cx } }
        private val BG_MOVE_VAR_Y: Float by lazy { 2f * priorVariance { it.cy } }

        private fun priorVariance(coord: (KeyboardLayout.Key) -> Float): Float {
            val prior = KeyboardLayout.backgroundPrior
            var mean = 0f
            for (k in KeyboardLayout.keys) mean += (prior[k.label] ?: 0f) * coord(k)
            var v = 0f
            for (k in KeyboardLayout.keys) {
                val d = coord(k) - mean
                v += (prior[k.label] ?: 0f) * d * d
            }
            return v
        }

        val TARGET_WORDS = listOf(TypedWordBank.GOOGLE, TypedWordBank.YAHOO)

        /** Real queries with key paths close to a target. Also prompted as `other` in Play. */
        val COMPETITORS = listOf("goggle", "googly", "goose", "gogo", "yoga", "yahtzee", "gmail", "youtube")

        /**
         * Prior of one lookalike query relative to a blocked word. Lookalikes are still scored
         * explicitly (a clearly-typed "goggle" will not flag), but a tie goes to the target.
         */
        const val COMPETITOR_PRIOR = 0.1f
        private val LOG_COMPETITOR_PRIOR = ln(COMPETITOR_PRIOR)

        /** Key sequences for a word: bare, `.com`, and either followed by Enter. */
        fun variants(word: String): List<List<String>> {
            val letters = word.map { it.toString() }
            val com = letters + listOf(".", "c", "o", "m")
            return listOf(
                letters,
                letters + TouchLabel.KEY_ENTER,
                com,
                com + TouchLabel.KEY_ENTER,
            )
        }
    }
}
