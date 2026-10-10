package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.data.SensorSample
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Stage A: cheap IMU-only typing-activity proposer.
 *
 * Maintains a short energy history (gyro magnitude + high-pass accel) and emits
 * candidate typing bursts when motion exceeds a local adaptive threshold long enough,
 * then falls quiet. Much cheaper than MiniRocket — intended to gate Stage B.
 *
 * Tuned for high proposal recall (prefer extra candidates over missed typing).
 * Hysteresis + smoothing avoid fragmenting a real typing burst when instantaneous
 * energy dips between taps.
 */
class TypingActivityDetector(
    private val gapMs: Long = GAP_MS,
    private val minActiveMs: Long = MIN_ACTIVE_MS,
    private val maxActiveMs: Long = MAX_ACTIVE_MS,
    private val historyMs: Long = HISTORY_MS,
) {
    private data class EnergyPoint(val tNs: Long, val e: Float)

    private val history = ArrayDeque<EnergyPoint>()
    private var ex = 0f
    private var ey = 0f
    private var ez = 9.8f
    private var primed = false
    private var ePrev1 = 0f
    private var ePrev2 = 0f
    private var smoothE = 0f

    private var activeStartNs: Long = -1L
    private var lastActiveNs: Long = -1L
    private var peakEnergy = 0f
    private var sumEnergy = 0f
    private var activeSamples = 0
    private var inBurst = false
    /** Enter/exit thresholds frozen at burst start so typing energy does not raise the bar mid-word. */
    private var frozenEnter = 0f

    private var completed: Candidate? = null

    data class Candidate(
        val startNs: Long,
        val endNs: Long,
        val peakEnergy: Float,
        val meanEnergy: Float,
        val sampleCount: Int,
    ) {
        val durationMs: Long get() = (endNs - startNs) / 1_000_000L
    }

    /** Ongoing burst, if any (for progressive scoring). */
    data class ActiveBurst(
        val startNs: Long,
        val lastNs: Long,
        val durationMs: Long,
        val peakEnergy: Float,
    )

    fun reset() {
        history.clear()
        primed = false
        ePrev1 = 0f
        ePrev2 = 0f
        smoothE = 0f
        activeStartNs = -1L
        lastActiveNs = -1L
        peakEnergy = 0f
        sumEnergy = 0f
        activeSamples = 0
        inBurst = false
        frozenEnter = 0f
        completed = null
    }

    fun offer(sample: SensorSample) {
        val raw = energy(sample)
        val e = (ePrev2 + ePrev1 + raw) / 3f
        ePrev2 = ePrev1
        ePrev1 = raw
        smoothE = if (smoothE == 0f) e else 0.25f * e + 0.75f * smoothE
        val t = sample.timestampNs
        history.addLast(EnergyPoint(t, e))
        val cutoff = t - historyMs * 1_000_000L
        while (history.isNotEmpty() && history.first().tNs < cutoff) history.removeFirst()

        val enter = if (inBurst && frozenEnter > 0f) frozenEnter else thresholdEnter()
        // Exit uses short-window energy (e), not the slow EMA — otherwise quiet gaps never close
        // and slow/noisy holds swallow the next word.
        val exit = max(enter * EXIT_RATIO, QUIET_ENERGY)
        val activeNow = if (inBurst) e >= exit else e >= enter
        val gapNs = gapMs * 1_000_000L

        if (activeNow) {
            if (!inBurst) {
                inBurst = true
                frozenEnter = enter
                activeStartNs = t
                peakEnergy = e
                sumEnergy = e
                activeSamples = 1
            } else {
                peakEnergy = max(peakEnergy, e)
                sumEnergy += e
                activeSamples++
            }
            lastActiveNs = t
            val dur = (t - activeStartNs) / 1_000_000L
            if (dur >= maxActiveMs) finish(t)
        } else if (inBurst && t - lastActiveNs >= gapNs) {
            finish(lastActiveNs)
        }
    }

    /** Pop a completed candidate, if one is waiting. */
    fun pollCompleted(): Candidate? {
        val c = completed
        completed = null
        return c
    }

    fun peekActive(): ActiveBurst? {
        if (!inBurst || activeStartNs < 0L || lastActiveNs < activeStartNs) return null
        return ActiveBurst(
            startNs = activeStartNs,
            lastNs = lastActiveNs,
            durationMs = (lastActiveNs - activeStartNs) / 1_000_000L,
            peakEnergy = peakEnergy,
        )
    }

    private fun finish(endNs: Long) {
        val start = activeStartNs
        if (inBurst && start >= 0L) {
            val dur = (endNs - start) / 1_000_000L
            val mean = if (activeSamples > 0) sumEnergy / activeSamples else 0f
            val strongEnough = peakEnergy >= MIN_PEAK_ENERGY && mean >= MIN_MEAN_ENERGY
            if (dur >= minActiveMs && activeSamples >= 3 && strongEnough) {
                completed = Candidate(
                    startNs = start,
                    endNs = endNs,
                    peakEnergy = peakEnergy,
                    meanEnergy = mean,
                    sampleCount = activeSamples,
                )
            }
        }
        inBurst = false
        frozenEnter = 0f
        activeStartNs = -1L
        lastActiveNs = -1L
        peakEnergy = 0f
        sumEnergy = 0f
        activeSamples = 0
    }

    private fun energy(sample: SensorSample): Float {
        val a = sample.accel
        val g = sample.gyro
        val ax = a?.x ?: 0f
        val ay = a?.y ?: 0f
        val az = a?.z ?: 0f
        if (!primed) {
            ex = ax; ey = ay; ez = az
            primed = true
        } else {
            ex += 0.1f * (ax - ex)
            ey += 0.1f * (ay - ey)
            ez += 0.1f * (az - ez)
        }
        val dyn = sqrt((ax - ex) * (ax - ex) + (ay - ey) * (ay - ey) + (az - ez) * (az - ez))
        val gm = if (g == null) 0f else sqrt(g.x * g.x + g.y * g.y + g.z * g.z)
        return dyn + gm
    }

    /** Adaptive enter threshold — tolerant of weak touchscreen motion. */
    private fun thresholdEnter(): Float {
        val n = history.size
        if (n < 8) return 0.04f
        val sorted = FloatArray(n) { history.elementAt(it).e }.also { it.sort() }
        val median = sorted[n / 2]
        val p85 = sorted[(n * 85) / 100]
        return max(max(median * 1.8f, p85 * 0.45f), 0.025f)
    }

    companion object {
        /** Long enough for slow typing + mid-word hesitations / backspaces. */
        const val GAP_MS = 850L
        const val MIN_ACTIVE_MS = 350L
        const val MAX_ACTIVE_MS = 4_500L
        const val HISTORY_MS = 4_000L
        /** Once in a burst, allow energy to drop to this fraction of enter before counting quiet. */
        private const val EXIT_RATIO = 0.50f
        /** Absolute quiet floor — synthetic/real hold noise sits below this. */
        private const val QUIET_ENERGY = 0.07f
        /** Reject low-energy “always slightly noisy” holds; still low enough for weak taps. */
        const val MIN_PEAK_ENERGY = 0.10f
        const val MIN_MEAN_ENERGY = 0.04f
    }
}
