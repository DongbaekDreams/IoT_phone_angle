package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.data.ClientRole
import edu.iot.phoneangle.data.FloatArray3
import edu.iot.phoneangle.data.SensorSample
import edu.iot.phoneangle.data.TouchLabel
import edu.iot.phoneangle.data.Trial
import edu.iot.phoneangle.data.TrialTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * Plumbing tests on synthetic data. They prove detector / regression / matcher / trainer wiring,
 * not real-world accuracy — that comes from the held-out report on real recordings.
 */
class TypedWordTest {

    @Test
    fun labelMapping() {
        assertEquals(TypedLabel.GOOGLE, TypedLabel.fromCollectLabel("google"))
        assertEquals(TypedLabel.GOOGLE, TypedLabel.fromCollectLabel("Google.com"))
        assertEquals(TypedLabel.YAHOO, TypedLabel.fromCollectLabel("yahoo.com"))
        assertEquals(TypedLabel.OTHER, TypedLabel.fromCollectLabel("goggle"))
        assertEquals(TypedLabel.IDLE, TypedLabel.fromCollectLabel("idle"))
    }

    @Test
    fun wordBankDrawRates() {
        val rng = Random(0)
        val draws = List(4000) { TypedWordBank.next(rng).label }
        val g = draws.count { TypedLabel.fromCollectLabel(it) == TypedLabel.GOOGLE }
        val y = draws.count { TypedLabel.fromCollectLabel(it) == TypedLabel.YAHOO }
        val hard = draws.count { it in TypedWordBank.HARD_NEGATIVES }
        assertTrue("g=$g", g in 650..950)
        assertTrue("y=$y", y in 650..950)
        assertTrue("hard=$hard", hard in 450..750)
        assertTrue(draws.any { it == "google.com" })
    }

    @Test
    fun keyboardGeometry() {
        assertEquals(0.05f, KeyboardLayout.key("q")!!.cx, 1e-6f)
        assertEquals(KeyboardLayout.key("o")!!.cy, KeyboardLayout.key("p")!!.cy, 1e-6f)
        assertEquals("g", KeyboardLayout.keyAt(0.5f, 0.375f).label)
        assertEquals(TouchLabel.KEY_ENTER, KeyboardLayout.keyAt(0.95f, 0.9f).label)
        assertEquals(6, KeyboardLayout.path("google").size)
    }

    @Test
    fun ridgeRecoversLinearMap() {
        val rng = Random(3)
        val xs = List(200) { FloatArray(4) { rng.nextFloat() * 2 - 1 } }
        val y = FloatArray(xs.size) { 0.3f * xs[it][0] - 0.7f * xs[it][2] + 0.5f }
        val (w, b) = TapPositionModel.ridge(xs, y, 1e-3f)
        assertEquals(0.3f, w[0], 0.01f)
        assertEquals(-0.7f, w[2], 0.01f)
        assertEquals(0.5f, b, 0.01f)
    }

    // ---- WordSpotter with an oracle position model ------------------------------------------

    /** Features 0/1 are the true x/y, so the model's prediction is the tap position + noise. */
    private fun oracleModel(sigmaX: Float = 0.06f, sigmaY: Float = 0.10f, withMove: Boolean = false): TapPositionModel {
        val d = TapStream.FEATURE_DIM
        val scaler = TapScaler().also { it.load(FloatArray(d), FloatArray(d) { 1f }) }
        val wx = FloatArray(d).also { it[0] = 1f }
        val wy = FloatArray(d).also { it[1] = 1f }
        return TapPositionModel(
            scaler, wx, 0f, wy, 0f, sigmaX, sigmaY,
            TapPositionModel.Gauss(1f, 0.5f), TapPositionModel.Gauss(3f, 1f),
            move = if (withMove) TapPositionModel.MoveModel(wx.copyOf(), 0f, wy.copyOf(), 0f, 0.03f, 0.05f) else null,
        )
    }

    private fun obsFor(spotter: WordSpotter, keys: List<String>, noise: Float, rng: Random): List<WordSpotter.Obs> =
        keys.map { label ->
            val k = KeyboardLayout.key(label)!!
            val f = FloatArray(TapStream.FEATURE_DIM)
            f[0] = k.cx + (rng.nextFloat() - 0.5f) * 2 * noise
            f[1] = k.cy + (rng.nextFloat() - 0.5f) * 2 * noise
            spotter.observe(f)
        }

    private fun letters(w: String) = w.map { it.toString() }

    @Test
    fun spotterFindsTargetsAndRejectsOthers() {
        val spotter = WordSpotter(oracleModel(), threshold = 3f)
        val rng = Random(5)
        assertEquals(TypedLabel.GOOGLE, spotter.score(obsFor(spotter, letters("google"), 0.03f, rng)).label)
        assertEquals(
            TypedLabel.YAHOO,
            spotter.score(obsFor(spotter, letters("yahoo.com") + TouchLabel.KEY_ENTER, 0.03f, rng)).label,
        )
        assertEquals(TypedLabel.OTHER, spotter.score(obsFor(spotter, letters("weather"), 0.03f, rng)).label)
        assertEquals(TypedLabel.OTHER, spotter.score(obsFor(spotter, letters("netflix"), 0.03f, rng)).label)
    }

    @Test
    fun neighbourKeySlipStillMatches() {
        // h is right next to g: "goohle" taps should still read as google, not as "other".
        val spotter = WordSpotter(oracleModel(), threshold = 3f)
        val r = spotter.score(obsFor(spotter, letters("goohle"), 0.02f, Random(7)))
        assertEquals(TypedLabel.GOOGLE, r.label)
    }

    @Test
    fun missedAndExtraTapsAreTolerated() {
        val spotter = WordSpotter(oracleModel(), threshold = 3f)
        val rng = Random(9)
        // Missed "l" ("gogle" would be genuinely ambiguous with the competitor "goggle").
        assertEquals(TypedLabel.GOOGLE, spotter.score(obsFor(spotter, letters("googe"), 0.02f, rng)).label)
        assertEquals(TypedLabel.YAHOO, spotter.score(obsFor(spotter, letters("yahooo"), 0.02f, rng)).label)
    }

    @Test
    fun lookalikeCompetitorIsNotFlagged() {
        val spotter = WordSpotter(oracleModel(), threshold = 2f)
        val r = spotter.score(obsFor(spotter, letters("goggle"), 0.01f, Random(11)))
        assertEquals(TypedLabel.OTHER, r.label)
    }

    @Test
    fun earlyPrefixScoring() {
        val spotter = WordSpotter(oracleModel(), threshold = 3f)
        val yaho = spotter.score(obsFor(spotter, letters("yaho"), 0.02f, Random(13)), prefixOk = true)
        assertEquals(TypedLabel.YAHOO, yaho.target)
        assertTrue("margin=${yaho.margin}", yaho.margin > 3f)
        // "goog" is also the start of the competitor "googly": a tie, decided only by the
        // lookalike prior (ln 10 ≈ 2.3) — well short of the early-flag bar.
        val goog = spotter.score(obsFor(spotter, letters("goog"), 0.02f, Random(13)), prefixOk = true)
        assertEquals(-kotlin.math.ln(WordSpotter.COMPETITOR_PRIOR), goog.margin, 0.2f)
        assertTrue(goog.margin < spotter.threshold + QueryListener.EARLY_EXTRA_MARGIN)
    }

    private fun timed(obs: List<WordSpotter.Obs>, gapsMs: List<Long>): List<WordSpotter.Obs> {
        var t = 1_000_000_000L
        return obs.mapIndexed { i, o ->
            if (i > 0) t += gapsMs[i - 1] * 1_000_000L
            o.copy(tNs = t)
        }
    }

    @Test
    fun doubleKeyTimingRewardsFastRepeatButNeverPenalizesHesitation() {
        val model = oracleModel()
        val off = WordSpotter(model, 3f)
        val on = WordSpotter(model, 3f, WordSpotter.Weights(doubleKeyTiming = 1f))
        val base = obsFor(off, letters("yahoo"), 0.02f, Random(17))
        val fast = timed(base, listOf(250, 260, 240, 100)) // o→o much quicker than usual
        val slow = timed(base, listOf(250, 260, 240, 900)) // hesitation before the second o
        val gOff = off.score(fast).scores.getValue("yahoo")
        val gFast = on.score(fast).scores.getValue("yahoo")
        val gSlow = on.score(slow).scores.getValue("yahoo")
        assertEquals(gOff + 1f, gFast, 1e-3f) // full one-nat bonus
        assertEquals(off.score(slow).scores.getValue("yahoo"), gSlow, 1e-3f) // zero, not negative
    }

    @Test
    fun thumbSideAgreementAddsEvidence() {
        // Very uncertain x (σx = 3 keys): position barely knows the side; the thumb can tell.
        val spotter = WordSpotter(oracleModel(sigmaX = 0.3f), 3f, WordSpotter.Weights(thumb = 1f))
        val obs = obsFor(spotter, letters("yahoo"), 0.02f, Random(19))
        val expected = obs.map { o -> o.copy(pRight = if (o.x > 0.5f) 0.9f else 0.1f) }
        val flipped = obs.map { o -> o.copy(pRight = if (o.x > 0.5f) 0.1f else 0.9f) }
        val a = spotter.score(expected).scores.getValue("yahoo")
        val b = spotter.score(flipped).scores.getValue("yahoo")
        assertTrue("matching=$a flipped=$b", a > b + 1f)
    }

    @Test
    fun movementTermSurvivesGripOffset() {
        // Every tap lands 1.5 keys right of where the model thinks (grip shift): absolute
        // positions are off, but the steps between taps are still right.
        val model = oracleModel(sigmaX = 0.12f, sigmaY = 0.2f, withMove = true)
        val off = WordSpotter(model, 3f)
        val on = WordSpotter(model, 3f, WordSpotter.Weights(movement = 1f))
        val obs = obsFor(off, letters("yahoo"), 0.01f, Random(23)).map { o ->
            val f = FloatArray(TapStream.FEATURE_DIM).also { it[0] = o.x + 0.15f; it[1] = o.y }
            off.observe(f)
        }
        val without = off.score(obs).scores.getValue("yahoo")
        val with = on.score(obs).scores.getValue("yahoo")
        assertTrue("with=$with without=$without", with > without + 2f)
        // A word with different steps does not get that boost.
        assertTrue(on.score(obs).scores.getValue("youtube") < with)
    }

    // ---- Synthetic IMU → detector → trainer → held-out report --------------------------------

    /**
     * 200 Hz IMU with gravity and noise. Each touch adds an accel spike and a gyro twist whose
     * axis/size depends on where it landed (pitch ∝ y, roll ∝ x) — the physical effect the
     * real model learns. Two-thumb grip: yaw flips sign with the thumb (keyboard half), and
     * double letters are typed faster than other pairs.
     */
    private fun synthTrial(id: String, text: String, rng: Random, pauseAfter: Int = -1, pauseMs: Long = 0L): Trial {
        val keys = text.map { if (it == ' ') TouchLabel.KEY_SPACE else it.toString() } + TouchLabel.KEY_ENTER
        val t0 = 1_000_000_000L
        val touches = ArrayList<TouchLabel>()
        var t = t0 + 400_000_000L
        for (k in keys) {
            val key = KeyboardLayout.key(k)!!
            val x = key.cx + (rng.nextFloat() - 0.5f) * 0.04f
            val y = key.cy + (rng.nextFloat() - 0.5f) * 0.06f
            touches += TouchLabel(t, x, y, k)
            t += 180_000_000L + rng.nextLong(140_000_000L)
        }
        // Double letters: the second press comes quickly.
        for (i in 1 until touches.size) {
            if (touches[i].key == touches[i - 1].key) {
                val shift = touches[i].timestampNs - (touches[i - 1].timestampNs + 110_000_000L)
                for (j in i until touches.size) touches[j] = touches[j].copy(timestampNs = touches[j].timestampNs - shift)
            }
        }
        if (pauseAfter in 0 until touches.size - 1) {
            for (j in pauseAfter + 1 until touches.size) {
                touches[j] = touches[j].copy(timestampNs = touches[j].timestampNs + pauseMs * 1_000_000L)
            }
        }
        t = touches.last().timestampNs
        val end = t + 1_500_000_000L // quiet tail longer than any end-of-query pause
        val samples = ArrayList<SensorSample>()
        var ts = t0
        while (ts < end) {
            var ax = 0f; var ay = 6.9f; var az = 6.9f
            var wx = 0f; var wy = 0f; var wz = 0f
            for (touch in touches) {
                val dt = (ts - touch.timestampNs) / 1e6f // ms
                if (dt < 0f || dt > 250f) continue
                val spike = exp(-dt / 12f)
                val twist = (dt / 20f) * exp(1f - dt / 20f)
                az -= 0.8f * spike
                wx += 2.0f * (touch.y - 0.5f) * twist
                wy += 2.5f * (touch.x - 0.5f) * twist
                wz += (if (touch.x > 0.5f) 0.4f else -0.4f) * twist
            }
            fun n() = (rng.nextFloat() - 0.5f) * 0.03f
            samples += SensorSample(ts, FloatArray3(ax + n(), ay + n(), az + n()), FloatArray3(wx + n(), wy + n(), wz + n()))
            ts += 5_000_000L
        }
        return Trial(
            id, text, TrialTask.TYPED_TAP, ClientRole.CLIENT_A, 0L, 0L, samples,
            sessionTag = TapPositionModel.GRIP_TWO_THUMBS, touches = touches,
        )
    }

    @Test
    fun detectorFindsSyntheticTaps() {
        val trial = synthTrial("d1", "yahoo", Random(21))
        val (_, taps) = TapStream.scan(trial.samples)
        val m = TapTrainer.match(trial.touches, taps.map { it.onsetNs })
        assertEquals(trial.touches.size, m.pairs.size)
        assertEquals(trial.touches.size, taps.size)
        assertTrue(abs(m.offsetNs) < 20_000_000L)
        assertEquals(TapStream.FEATURE_DIM, taps.first().features.size)
    }

    @Test
    fun liveListenerFlagsAfterPause() {
        val rng = Random(31)
        val trials = (0 until 90).map { i ->
            val text = when (i % 3) {
                0 -> "google"
                1 -> "yahoo"
                else -> TypedWordBank.SEARCHES[i % TypedWordBank.SEARCHES.size]
            }
            synthTrial("trial-$i", text, rng)
        }
        val bundle = TapTrainer.train(trials)
        val report = requireNotNull(bundle.report)
        assertTrue("tapRecall=${report.tapRecall}", report.tapRecall > 0.9f)
        assertTrue("rowAcc=${report.rowAccuracy}", report.rowAccuracy > 0.8f)
        assertTrue("queryAcc=${report.queryAccuracy}", report.queryAccuracy > 0.8f)
        assertTrue("scoreMs=${report.meanScoreMs}", report.meanScoreMs < 20f)
        assertTrue(bundle.position.hasThumb)
        assertTrue("thumbAcc=${report.thumbAccuracy}", report.thumbAccuracy > 0.85f)
        assertEquals(6, report.ablation.size) // position, timing, thumb, movement, all, selected
        assertNotNull(bundle.position.move)
        assertTrue(report.ablation.all { it.testMacroF1.isFinite() })

        // Same bundle through the streaming listener, as on the phone.
        val events = ArrayList<QueryListener.Event>()
        val listener = QueryListener(bundle) { events += it }
        synthTrial("live", "google", Random(99)).samples.forEach { listener.offer(it) }
        val flagged = events.filterIsInstance<QueryListener.Event.Scored>().filter { it.flagged }
        assertEquals(TypedLabel.GOOGLE, flagged.single().result.label)
        // Pause adapted to this typist (~3× a ~250 ms median gap).
        assertTrue("gap=${listener.queryGapMs}", listener.queryGapMs in 600L..1000L)

        // Hesitation after "goo" splits the query; carry-over still joins the word.
        events.clear()
        synthTrial("live2", "google", Random(98), pauseAfter = 2, pauseMs = 1_200L).samples
            .forEach { listener.offer(it.copy(timestampNs = it.timestampNs + 60_000_000_000L)) } // a minute later
        val scored = events.filterIsInstance<QueryListener.Event.Scored>()
        assertTrue("queries=${scored.size}", scored.size >= 2)
        val hit = scored.single { it.flagged }
        assertEquals(TypedLabel.GOOGLE, hit.result.label)
        assertTrue(hit.carried)

        // Round-trips through JSON unchanged.
        val back = TapModelStore.fromJson(TapModelStore.toJson(bundle))
        assertEquals(bundle.threshold, back.threshold, 1e-6f)
        assertEquals(bundle.position.wx.toList(), back.position.wx.toList())
        assertEquals(bundle.position.wt.toList(), back.position.wt.toList())
        assertEquals(bundle.weights, back.weights)
        assertEquals(bundle.position.move!!.wdx.toList(), back.position.move!!.wdx.toList())
        assertEquals(report.ablation, back.report!!.ablation)
    }
}
