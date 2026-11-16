package edu.iot.phoneangle.ml.typed

/**
 * Label-free adaptation to whoever is typing and however they hold the phone.
 *
 * Each hand / hold adds a roughly constant offset to every tap's motion features. Subtracting
 * the running average of the last [WINDOW] taps removes it and keeps what differs between keys.
 * The gravity direction (features 18–20) is left absolute so the model still sees the hold angle.
 *
 * Off by default: it raised one-typist-to-another position R² (0.21 / 0.02 → 0.27 / 0.24) in a
 * quick test, but leave-one-session-out query results did not improve (6 vs 9 of 37 caught).
 *
 * The center is fixed when a query starts (from taps before it), identically in training and live.
 */
object TapCentering {
    /** Switch for evaluation (centering on vs off). */
    @Volatile
    var enabled = false
    const val WINDOW = 40
    const val MIN_TAPS = 10
    /** A pause this long starts a fresh session (new person / new hold possible). */
    const val SESSION_GAP_NS = 120_000_000_000L

    private fun keepsAbsolute(i: Int) = i in 18..20

    fun apply(features: FloatArray, center: FloatArray): FloatArray =
        FloatArray(features.size) { i -> if (keepsAbsolute(i)) features[i] else features[i] - center[i] }

    /** Rolling buffer of raw tap features. */
    class Rolling {
        private val buf = ArrayDeque<FloatArray>()
        private var lastNs = Long.MIN_VALUE / 2

        /** Center for a query starting at [startNs]; [fallback] (training mean) until enough taps. */
        fun center(startNs: Long, fallback: FloatArray): FloatArray {
            if (startNs - lastNs > SESSION_GAP_NS) buf.clear()
            if (buf.size < MIN_TAPS) return fallback
            val c = FloatArray(fallback.size)
            for (f in buf) for (i in c.indices) c[i] += f[i]
            for (i in c.indices) c[i] /= buf.size
            return c
        }

        fun add(features: FloatArray, tNs: Long) {
            buf.addLast(features)
            while (buf.size > WINDOW) buf.removeFirst()
            lastNs = tNs
        }

        fun clear() = buf.clear()
    }
}
