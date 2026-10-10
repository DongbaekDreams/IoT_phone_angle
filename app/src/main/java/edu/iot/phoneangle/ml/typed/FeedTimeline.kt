package edu.iot.phoneangle.ml.typed

import kotlin.math.min

/**
 * Per-word ground truth for a continuous feed session. Used only to LABEL training windows
 * (never a model input). Times are in the sensor clock (same as SensorSample.timestampNs).
 */
data class FeedWord(
    val word: String,
    val startNs: Long,
    val endNs: Long,
    val status: Status,
) {
    enum class Status(val code: Char) {
        /** Typed exactly as prompted. */
        CLEAN('c'),
        /** Close to the prompt (typo/corrected) — motion is still that word, kept. */
        TYPO('t'),
        /** Far from the prompt (derailed/skipped) — region excluded from training. */
        DISCARD('x');

        companion object {
            fun fromCode(c: Char): Status = entries.find { it.code == c } ?: DISCARD
        }
    }

    val label: TypedLabel get() = TypedLabel.fromCollectLabel(word)
    val usable: Boolean get() = status != Status.DISCARD
}

object FeedTimeline {
    const val NOTES_PREFIX = "feed1:"
    const val CUT_NOTES = "typed_feed_cut"

    fun encode(words: List<FeedWord>): String =
        NOTES_PREFIX + words.joinToString(";") { "${it.word}|${it.startNs}|${it.endNs}|${it.status.code}" }

    fun decode(notes: String): List<FeedWord> {
        if (!notes.startsWith(NOTES_PREFIX)) return emptyList()
        return notes.removePrefix(NOTES_PREFIX).split(';').mapNotNull { part ->
            val f = part.split('|')
            if (f.size != 4) return@mapNotNull null
            val s = f[1].toLongOrNull() ?: return@mapNotNull null
            val e = f[2].toLongOrNull() ?: return@mapNotNull null
            FeedWord(f[0], s, e, FeedWord.Status.fromCode(f[3].firstOrNull() ?: 'x'))
        }
    }

    /**
     * Typed vs prompted. Non-target prompts are "other" no matter what you type, so any
     * non-blank word counts as a real one. For google/yahoo: exact → clean, ≤2 edits → typo
     * (still that word), else discard.
     */
    fun classify(target: String, typed: String): FeedWord.Status {
        val lab = TypedLabel.fromCollectLabel(target)
        if (lab != TypedLabel.GOOGLE && lab != TypedLabel.YAHOO) {
            return if (typed.isBlank()) FeedWord.Status.DISCARD else FeedWord.Status.CLEAN
        }
        val d = levenshtein(target.lowercase(), typed.trim().lowercase())
        return when {
            d == 0 -> FeedWord.Status.CLEAN
            d <= 2 && typed.isNotBlank() -> FeedWord.Status.TYPO
            else -> FeedWord.Status.DISCARD
        }
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}
