package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.data.SensorSample

/**
 * Live search-bar flagger, IMU only. Call [offer] for every sensor sample (on the sensor thread).
 *
 * Taps from [TapStream] accumulate into the current query. When no tap has arrived for the
 * end-of-query pause, the query is scored once with [WordSpotter] and [onEvent] fires.
 * - The pause adapts to the typist: ~3× their median gap between taps, clamped to
 *   [MIN_QUERY_GAP_MS]..[MAX_QUERY_GAP_MS].
 * - A hesitation can still split a word ("goo… gle"), so taps from recent unflagged queries
 *   (within [CARRY_MS]) are kept and the combined sequence is scored too. Unrelated earlier
 *   taps only cost extra-tap penalties, so carrying them over is conservative.
 * - An early check runs after each tap from the 4th on, with a stricter margin, so "yaho…" can
 *   flag before the user finishes. One flag per query.
 *
 * Keyboard events are never used; the query boundary is the typing pause.
 */
class QueryListener(
    bundle: TapModelBundle,
    private val onEvent: (Event) -> Unit,
) {
    sealed interface Event {
        /**
         * A tap was detected: count so far in this query, and the current leaning (the query so
         * far scored as a possible prefix) — what the live screen shows while you type.
         */
        data class Typing(val taps: Int, val guess: WordSpotter.Result) : Event
        data class Scored(
            val result: WordSpotter.Result,
            val early: Boolean,
            val flagged: Boolean,
            val scoreMs: Float,
            /** True when the flag came from joining this query with taps before a pause. */
            val carried: Boolean = false,
        ) : Event
        data object Idle : Event
    }

    private val spotter = WordSpotter(bundle.position, bundle.threshold, bundle.weights)
    private val taps = ArrayList<WordSpotter.Obs>(WordSpotter.MAX_TAPS)
    private val carried = ArrayList<WordSpotter.Obs>(WordSpotter.MAX_TAPS)
    private var lastTapNs = Long.MIN_VALUE / 2
    private var flagged = false
    private val stream = TapStream(bundle.sensitivity, detector = bundle.detector) { tap -> onTap(tap) }
    private val featureMean = bundle.featureMean
    private val rolling = TapCentering.Rolling()
    /** Center for the current query, fixed at its first tap (as in training). */
    private var center: FloatArray? = null
    private val queryRaw = ArrayList<Pair<FloatArray, Long>>()

    // Recent within-query gaps between taps (ring buffer) for the adaptive pause.
    private val gaps = LongArray(GAP_HISTORY)
    private var gapCount = 0
    private val gapScratch = LongArray(GAP_HISTORY)

    /** Current end-of-query pause in ms (recomputed per tap, not per sample). */
    var queryGapMs: Long = MIN_QUERY_GAP_MS
        private set

    private fun updateQueryGap() {
        val n = minOf(gapCount, GAP_HISTORY)
        if (n < MIN_GAPS_FOR_ADAPT) return
        System.arraycopy(gaps, 0, gapScratch, 0, n)
        gapScratch.sort(0, n)
        val median = gapScratch[n / 2] / 1_000_000L
        queryGapMs = (median * GAP_MULTIPLIER).coerceIn(MIN_QUERY_GAP_MS, MAX_QUERY_GAP_MS)
    }

    fun offer(sample: SensorSample) {
        stream.offer(sample)
        if (taps.isNotEmpty() && sample.timestampNs - lastTapNs > queryGapMs * 1_000_000L) {
            endQuery()
        }
    }

    fun reset() {
        stream.reset()
        taps.clear()
        carried.clear()
        queryRaw.clear()
        center = null
        flagged = false
        gapCount = 0
        queryGapMs = MIN_QUERY_GAP_MS
    }

    private fun onTap(tap: TapStream.Tap) {
        if (taps.isEmpty()) {
            // New query: drop carried taps if the user stopped for a while.
            if (tap.onsetNs - lastTapNs > CARRY_MS * 1_000_000L) carried.clear()
        } else {
            val gap = tap.onsetNs - lastTapNs
            if (gap < MAX_QUERY_GAP_MS * 1_000_000L) {
                gaps[gapCount++ % GAP_HISTORY] = gap
                updateQueryGap()
            }
        }
        lastTapNs = tap.onsetNs
        if (taps.size >= WordSpotter.MAX_TAPS) taps.removeAt(0)
        val mean = featureMean
        if (taps.isEmpty() && mean != null) center = rolling.center(tap.onsetNs, mean)
        queryRaw += tap.features to tap.onsetNs
        val c = center
        taps += if (c == null) spotter.observe(tap) else spotter.observe(TapCentering.apply(tap.features, c), tap.onsetNs)
        val t0 = System.nanoTime()
        val r = spotter.score(taps, prefixOk = true)
        val ms = (System.nanoTime() - t0) / 1e6f
        onEvent(Event.Typing(taps.size, r))
        if (!flagged && taps.size >= WordSpotter.MIN_PREFIX) {
            if (r.label != TypedLabel.OTHER && r.margin >= spotter.threshold + EARLY_EXTRA_MARGIN) {
                flagged = true
                onEvent(Event.Scored(r, early = true, flagged = true, scoreMs = ms))
            }
        }
    }

    private fun endQuery() {
        if (!flagged) {
            val t0 = System.nanoTime()
            var r = spotter.score(taps)
            var fromCarry = false
            if (r.label == TypedLabel.OTHER && carried.isNotEmpty()) {
                val joined = (carried + taps).takeLast(WordSpotter.MAX_TAPS)
                val rj = spotter.score(joined)
                if (rj.label != TypedLabel.OTHER) {
                    r = rj
                    fromCarry = true
                }
            }
            val ms = (System.nanoTime() - t0) / 1e6f
            val hit = r.label != TypedLabel.OTHER
            onEvent(Event.Scored(r, early = false, flagged = hit, scoreMs = ms, carried = fromCarry))
            flagged = hit
        }
        // Keep unflagged taps so a word split by a hesitation can still be joined.
        if (flagged) {
            carried.clear()
        } else {
            carried += taps
            while (carried.size > WordSpotter.MAX_TAPS) carried.removeAt(0)
        }
        for ((f, t) in queryRaw) rolling.add(f, t)
        queryRaw.clear()
        center = null
        taps.clear()
        flagged = false
        onEvent(Event.Idle)
    }

    companion object {
        /** End-of-query pause bounds (Enter is just one more tap). */
        const val MIN_QUERY_GAP_MS = 450L
        const val MAX_QUERY_GAP_MS = 1_000L
        private const val GAP_MULTIPLIER = 3L
        private const val GAP_HISTORY = 32
        private const val MIN_GAPS_FOR_ADAPT = 6
        /** Taps from an unflagged query this recent are joined with the next one. */
        const val CARRY_MS = 2_000L
        /** Early (mid-word) flags need this much more margin than end-of-query flags. */
        const val EARLY_EXTRA_MARGIN = 3f
    }
}
