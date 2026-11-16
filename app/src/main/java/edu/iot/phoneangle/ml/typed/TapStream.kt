package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.data.SensorSample
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Streaming IMU front end for keystroke taps (live and offline share this exact code).
 *
 * Per sample: remove gravity from the accelerometer (slow EMA), keep a slow gyro baseline, and
 * store both in a ring for tap features. Tap onsets come from one of two detectors:
 * - **Learned** ([detector] set): samples are resampled onto a 5 ms grid and high-passed; each
 *   frame's window is scored by [TapDetector]; candidates closer than 60 ms keep the strongest.
 * - **Energy fallback**: `|accel_hp| + |gyro − baseline|` above an adaptive noise floor ×
 *   [sensitivity] (used before the phone has enough labeled taps to train a detector).
 * Once [POST_MS] of signal after an onset has arrived, its feature vector is emitted.
 *
 * All history lives in preallocated primitive ring buffers — no per-sample allocation.
 */
class TapStream(
    /** Energy-fallback onset threshold as a multiple of the noise floor. */
    private val sensitivity: Float = DEFAULT_SENSITIVITY,
    capacity: Int = LIVE_CAPACITY,
    /** Learned onset detector; null = energy fallback. */
    private val detector: TapDetector? = null,
    /** Training hook: every grid frame's detector window (reused buffer — copy to keep). */
    private val onFrame: ((Long, FloatArray) -> Unit)? = null,
    /** Called (on the offering thread) when a tap's feature window is complete. */
    private val onTap: (Tap) -> Unit,
) {
    /** A detected tap. [score] is the learned detector's probability at the onset (1 for the energy fallback). */
    data class Tap(val onsetNs: Long, val features: FloatArray, val score: Float = 1f)

    private val ring = ImuRing(capacity)

    private var lastNs = Long.MIN_VALUE
    private val grav = FloatArray(3)
    private val gyroBase = FloatArray(3)
    private var primed = false
    private var floor = INITIAL_FLOOR
    private var lastOnsetNs = Long.MIN_VALUE / 2
    /** False after an onset until energy falls back (or [REARM_MS] passes) — one onset per tap. */
    private var armed = true
    /** Lowest energy since the last onset — a sharp rise from it is a new tap (fast double letters). */
    private var valley = Float.MAX_VALUE
    private val pending = LongArray(MAX_PENDING)
    private val pendingScore = FloatArray(MAX_PENDING)
    private var pendingCount = 0

    // Learned-detector frame stage: 5 ms grid of high-passed channels.
    private val useFrames = detector != null || onFrame != null
    private val frames = Array(TapDetector.CHANNELS) { FloatArray(FRAME_CAP) }
    private var frameCount = 0L
    private var nextFrameNs = Long.MIN_VALUE
    private var prevRawNs = Long.MIN_VALUE
    private val prevRaw = FloatArray(TapDetector.CHANNELS)
    private val raw = FloatArray(TapDetector.CHANNELS)
    private val hpBase = FloatArray(TapDetector.CHANNELS)
    private val window = FloatArray(TapDetector.DIM)
    private val kHp = 1f - exp(-(TapDetector.FRAME_NS / 1e9f) / TapDetector.HP_TAU_S)
    private var candFrame = -1L
    private var candNs = 0L
    private var candP = 0f

    fun reset() {
        ring.clear()
        lastNs = Long.MIN_VALUE
        primed = false
        floor = INITIAL_FLOOR
        lastOnsetNs = Long.MIN_VALUE / 2
        armed = true
        valley = Float.MAX_VALUE
        pendingCount = 0
        frameCount = 0L
        nextFrameNs = Long.MIN_VALUE
        prevRawNs = Long.MIN_VALUE
        candFrame = -1L
    }

    fun offer(sample: SensorSample) {
        val a = sample.accel ?: return
        val g = sample.gyro ?: return
        offer(sample.timestampNs, a.x, a.y, a.z, g.x, g.y, g.z)
    }

    fun offer(
        tNs: Long,
        ax: Float, ay: Float, az: Float,
        wx: Float, wy: Float, wz: Float,
    ) {
        if (tNs <= lastNs) return
        val dt = if (lastNs == Long.MIN_VALUE) 0.005f else ((tNs - lastNs) / 1e9f).coerceIn(1e-4f, 0.1f)
        lastNs = tNs
        if (!primed) {
            grav[0] = ax; grav[1] = ay; grav[2] = az
            gyroBase[0] = wx; gyroBase[1] = wy; gyroBase[2] = wz
            primed = true
        }
        val hx = ax - grav[0]
        val hy = ay - grav[1]
        val hz = az - grav[2]
        val bx = wx - gyroBase[0]
        val by = wy - gyroBase[1]
        val bz = wz - gyroBase[2]
        val e = sqrt(hx * hx + hy * hy + hz * hz) + sqrt(bx * bx + by * by + bz * bz)
        ring.add(tNs, hx, hy, hz, wx, wy, wz, grav[0], grav[1], grav[2], e)

        val threshold = max(floor * sensitivity, MIN_THRESHOLD)
        // Baselines track slow hand motion but nearly freeze during a tap; otherwise they soak
        // up the tap's twist and leave a long offset that re-triggers onsets.
        val kSlow = (1f - exp(-dt / GRAVITY_TAU_S)) * if (e < threshold) 1f else ACTIVE_BASELINE_RATE
        grav[0] += kSlow * (ax - grav[0])
        grav[1] += kSlow * (ay - grav[1])
        grav[2] += kSlow * (az - grav[2])
        gyroBase[0] += kSlow * (wx - gyroBase[0])
        gyroBase[1] += kSlow * (wy - gyroBase[1])
        gyroBase[2] += kSlow * (wz - gyroBase[2])
        if (useFrames) {
            raw[0] = ax; raw[1] = ay; raw[2] = az; raw[3] = wx; raw[4] = wy; raw[5] = wz
            frameStage(tNs)
        } else {
            energyOnsets(tNs, e, threshold, dt)
        }

        // Emit taps whose post-onset window is now complete.
        var i = 0
        while (i < pendingCount) {
            val onset = pending[i]
            if (tNs - onset >= POST_MS * 1_000_000L) {
                featuresAt(onset)?.let { onTap(Tap(onset, it, pendingScore[i])) }
                System.arraycopy(pending, i + 1, pending, i, pendingCount - i - 1)
                System.arraycopy(pendingScore, i + 1, pendingScore, i, pendingCount - i - 1)
                pendingCount--
            } else {
                i++
            }
        }
    }

    private fun energyOnsets(tNs: Long, e: Float, threshold: Float, dt: Float) {
        // Noise floor: falls quickly, rises slowly, and always updates. (Updating only below
        // threshold locked up on noisy phones: the floor never rose and noise fired as taps.)
        val tau = if (e < floor) FLOOR_DOWN_TAU_S else FLOOR_UP_TAU_S
        floor += (1f - exp(-dt / tau)) * (e - floor)
        val sinceOnsetNs = tNs - lastOnsetNs
        if (!armed) {
            valley = minOf(valley, e)
            val fellBack = e < threshold * REARM_RATIO || sinceOnsetNs >= REARM_MS * 1_000_000L
            val newRise = sinceOnsetNs >= REFRACTORY_MS * 1_000_000L && e > valley * VALLEY_RISE
            if (fellBack || newRise) armed = true
        }
        if (armed && e > threshold && sinceOnsetNs >= REFRACTORY_MS * 1_000_000L) {
            lastOnsetNs = tNs
            armed = false
            valley = e
            if (pendingCount < MAX_PENDING) {
                pendingScore[pendingCount] = 1f
                pending[pendingCount++] = tNs
            }
        }
    }

    /** Resample onto the 5 ms grid (linear interpolation), high-pass, and score each new frame. */
    private fun frameStage(tNs: Long) {
        if (prevRawNs == Long.MIN_VALUE) {
            System.arraycopy(raw, 0, prevRaw, 0, raw.size)
            System.arraycopy(raw, 0, hpBase, 0, raw.size)
            prevRawNs = tNs
            nextFrameNs = tNs
        }
        while (nextFrameNs <= tNs) {
            val span = tNs - prevRawNs
            val u = if (span <= 0L) 1f else ((nextFrameNs - prevRawNs).toFloat() / span).coerceIn(0f, 1f)
            val slot = (frameCount % FRAME_CAP).toInt()
            for (c in 0 until TapDetector.CHANNELS) {
                val v = prevRaw[c] + u * (raw[c] - prevRaw[c])
                hpBase[c] += kHp * (v - hpBase[c])
                frames[c][slot] = v - hpBase[c]
            }
            frameCount++
            onFrameAdded(nextFrameNs)
            nextFrameNs += TapDetector.FRAME_NS
        }
        System.arraycopy(raw, 0, prevRaw, 0, raw.size)
        prevRawNs = tNs
    }

    private fun onFrameAdded(newestNs: Long) {
        val center = frameCount - 1 - TapDetector.POST_FRAMES
        if (center - TapDetector.PRE_FRAMES < 0) return
        val centerNs = newestNs - TapDetector.POST_FRAMES * TapDetector.FRAME_NS
        val half = TapDetector.WINDOW_FRAMES * TapDetector.CHANNELS
        for (fr in 0 until TapDetector.WINDOW_FRAMES) {
            val slot = ((center - TapDetector.PRE_FRAMES + fr) % FRAME_CAP).toInt()
            for (c in 0 until TapDetector.CHANNELS) {
                val v = frames[c][slot]
                window[fr * TapDetector.CHANNELS + c] = v
                window[half + fr * TapDetector.CHANNELS + c] = abs(v)
            }
        }
        onFrame?.invoke(centerNs, window)
        val det = detector ?: return
        val p = det.probability(window)
        if (p > det.threshold) {
            when {
                candFrame < 0 -> setCandidate(center, centerNs, p)
                center - candFrame <= TapDetector.MIN_GAP_FRAMES -> if (p > candP) setCandidate(center, centerNs, p)
                else -> {
                    finalizeCandidate()
                    setCandidate(center, centerNs, p)
                }
            }
        }
        if (candFrame >= 0 && center - candFrame > TapDetector.MIN_GAP_FRAMES) finalizeCandidate()
    }

    private fun setCandidate(frame: Long, ns: Long, p: Float) {
        candFrame = frame
        candNs = ns
        candP = p
    }

    private fun finalizeCandidate() {
        if (pendingCount < MAX_PENDING) {
            pendingScore[pendingCount] = candP
            pending[pendingCount++] = candNs
        }
        candFrame = -1L
    }

    /**
     * Fixed-length tap descriptor around [anchorNs] (see [FEATURE_NAMES]). Signals are
     * linearly interpolated onto a 5 ms grid, so 100 Hz and 400 Hz phones produce the same
     * features. Returns null if the ring no longer (or does not yet) cover the window.
     */
    fun featuresAt(anchorNs: Long): FloatArray? {
        if (!ring.covers(anchorNs + PRE_MS_NS, anchorNs + POST_MS * 1_000_000L)) return null
        val f = FloatArray(FEATURE_DIM)
        // Ongoing rotation just before the tap (thumb approach); also the baseline for integrals.
        val pre = FloatArray(3)
        for (c in 0 until 3) pre[c] = ring.mean(GYRO + c, anchorNs + PRE_MS_NS, anchorNs - 20_000_000L)
        for (c in 0 until 3) {
            f[c] = ring.integral(GYRO + c, anchorNs, anchorNs + 80_000_000L, pre[c]) * 1000f
            f[3 + c] = ring.integral(GYRO + c, anchorNs + 80_000_000L, anchorNs + 150_000_000L, pre[c]) * 1000f
            f[6 + c] = ring.mean(ACC + c, anchorNs, anchorNs + 30_000_000L)
            f[9 + c] = ring.mean(ACC + c, anchorNs + 30_000_000L, anchorNs + 100_000_000L)
            f[12 + c] = ring.signedPeak(GYRO + c, anchorNs, anchorNs + 100_000_000L, pre[c])
            f[15 + c] = pre[c]
        }
        val gx = ring.valueAt(GRAV, anchorNs)
        val gy = ring.valueAt(GRAV + 1, anchorNs)
        val gz = ring.valueAt(GRAV + 2, anchorNs)
        val gn = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-3f)
        f[18] = gx / gn
        f[19] = gy / gn
        f[20] = gz / gn
        f[21] = ln(1f + 100f * ring.max(ENERGY, anchorNs, anchorNs + 60_000_000L))
        return f
    }

    companion object {
        /** 2: position model uses tilt interactions ([TapPositionModel.EXPANDED_DIM] weights). */
        const val FEATURE_VERSION = 2
        const val FEATURE_DIM = 22
        val FEATURE_NAMES = listOf(
            "rotX_0_80", "rotY_0_80", "rotZ_0_80",
            "rotX_80_150", "rotY_80_150", "rotZ_80_150",
            "accX_0_30", "accY_0_30", "accZ_0_30",
            "accX_30_100", "accY_30_100", "accZ_30_100",
            "gyroPeakX", "gyroPeakY", "gyroPeakZ",
            "gyroPreX", "gyroPreY", "gyroPreZ",
            "gravX", "gravY", "gravZ",
            "logEnergy",
        )

        const val DEFAULT_SENSITIVITY = 4f
        val SENSITIVITY_GRID = floatArrayOf(2.5f, 3f, 4f, 5f, 6.5f, 8f)

        /** Fast two-thumb typing reaches ~8–10 taps/s; 70 ms keeps one onset per tap. */
        const val REFRACTORY_MS = 70L
        /** A tap's ringing must drop below this fraction of threshold before the next onset… */
        private const val REARM_RATIO = 0.6f
        /** …or this much time must pass (fast typing where taps overlap). */
        private const val REARM_MS = 200L
        /** …or energy jumps this many times above its low point since the onset (oo, ee). */
        private const val VALLEY_RISE = 3f
        const val POST_MS = 150L
        private const val PRE_MS_NS = -60_000_000L
        private const val GRAVITY_TAU_S = 0.4f
        private const val ACTIVE_BASELINE_RATE = 0.05f
        private const val FLOOR_DOWN_TAU_S = 0.1f
        private const val FLOOR_UP_TAU_S = 2.0f
        /** ~0.6 s of 5 ms frames — far more than one detector window. */
        private const val FRAME_CAP = 128
        private const val INITIAL_FLOOR = 0.02f
        private const val MIN_THRESHOLD = 0.04f
        private const val MAX_PENDING = 8

        /** ~5 s at 200 Hz (~2.5 s on 400 Hz phones) — far more than one tap window. */
        const val LIVE_CAPACITY = 1024

        // Ring channel layout.
        private const val ACC = 0
        private const val GYRO = 3
        private const val GRAV = 6
        private const val ENERGY = 9

        /** Offline: stream a whole trial and return detected taps plus the ring for touch-anchored features. */
        fun scan(
            samples: List<SensorSample>,
            sensitivity: Float = DEFAULT_SENSITIVITY,
            detector: TapDetector? = null,
        ): Pair<TapStream, List<Tap>> {
            val taps = ArrayList<Tap>()
            val stream = TapStream(sensitivity, capacity = samples.size + 8, detector = detector) { taps += it }
            for (s in samples) stream.offer(s)
            return stream to taps
        }
    }
}

/** Preallocated ring of timestamped channels with interpolation helpers. */
internal class ImuRing(private val capacity: Int) {
    private val t = LongArray(capacity)
    private val ch = Array(10) { FloatArray(capacity) }
    private var n = 0L

    fun clear() {
        n = 0L
    }

    /** Explicit parameters (not vararg) so the 200 Hz path allocates nothing. */
    fun add(
        tNs: Long,
        a0: Float, a1: Float, a2: Float,
        w0: Float, w1: Float, w2: Float,
        g0: Float, g1: Float, g2: Float,
        e: Float,
    ) {
        val i = (n % capacity).toInt()
        t[i] = tNs
        ch[0][i] = a0; ch[1][i] = a1; ch[2][i] = a2
        ch[3][i] = w0; ch[4][i] = w1; ch[5][i] = w2
        ch[6][i] = g0; ch[7][i] = g1; ch[8][i] = g2
        ch[9][i] = e
        n++
    }

    private val first: Long get() = max(0L, n - capacity)
    private fun ts(k: Long): Long = t[(k % capacity).toInt()]
    private fun v(c: Int, k: Long): Float = ch[c][(k % capacity).toInt()]

    fun covers(fromNs: Long, toNs: Long): Boolean =
        n >= 2 && ts(first) <= fromNs && ts(n - 1) >= toNs

    /** Largest logical index with t <= tNs (clamped to the buffer). */
    private fun floorIndex(tNs: Long): Long {
        var lo = first
        var hi = n - 1
        if (tNs <= ts(lo)) return lo
        if (tNs >= ts(hi)) return hi
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (ts(mid) <= tNs) lo = mid else hi = mid
        }
        return lo
    }

    fun valueAt(c: Int, tNs: Long): Float {
        val k = floorIndex(tNs)
        if (k >= n - 1) return v(c, k)
        val t0 = ts(k)
        val t1 = ts(k + 1)
        if (t1 <= t0 || tNs <= t0) return v(c, k)
        val u = (tNs - t0).toFloat() / (t1 - t0)
        return v(c, k) + u * (v(c, k + 1) - v(c, k))
    }

    private inline fun grid(fromNs: Long, toNs: Long, body: (Long) -> Unit): Int {
        var count = 0
        var tt = fromNs
        while (tt <= toNs) {
            body(tt)
            count++
            tt += GRID_NS
        }
        return count
    }

    fun mean(c: Int, fromNs: Long, toNs: Long): Float {
        var sum = 0f
        val count = grid(fromNs, toNs) { sum += valueAt(c, it) }
        return if (count == 0) 0f else sum / count
    }

    /** ∫ (x - baseline) dt in seconds. */
    fun integral(c: Int, fromNs: Long, toNs: Long, baseline: Float): Float {
        var sum = 0f
        grid(fromNs, toNs) { sum += valueAt(c, it) - baseline }
        return sum * (GRID_NS / 1e9f)
    }

    fun signedPeak(c: Int, fromNs: Long, toNs: Long, baseline: Float): Float {
        var best = 0f
        grid(fromNs, toNs) {
            val x = valueAt(c, it) - baseline
            if (abs(x) > abs(best)) best = x
        }
        return best
    }

    fun max(c: Int, fromNs: Long, toNs: Long): Float {
        var best = Float.NEGATIVE_INFINITY
        grid(fromNs, toNs) { best = max(best, valueAt(c, it)) }
        return best
    }

    companion object {
        private const val GRID_NS = 5_000_000L
    }
}
