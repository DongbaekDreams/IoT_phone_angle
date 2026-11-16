package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.data.ClientRole
import edu.iot.phoneangle.data.FloatArray3
import edu.iot.phoneangle.data.SensorSample
import edu.iot.phoneangle.data.TouchLabel
import edu.iot.phoneangle.data.Trial
import edu.iot.phoneangle.data.TrialTask
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Offline replay on real recordings pulled from a phone. Skipped unless TYPED_DATA_DIR points
 * at a folder of trial JSON files, e.g. pulled with:
 *
 *   adb exec-out run-as edu.iot.phoneangle tar cf - files/trials > data.tar
 *
 * Prints the same report the app shows, plus per-query margins, so detector / feature changes
 * can be measured on real taps without reinstalling.
 */
class RealDataReplayTest {

    private fun load(dir: File): List<Trial> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            val head = f.bufferedReader().use { r -> CharArray(400).let { String(it, 0, r.read(it)) } }
            if (!head.contains("\"typed_tap\"")) return@mapNotNull null
            val o = JSONObject(f.readText())
            val s = o.getJSONArray("samples")
            val samples = List(s.length()) { i ->
                val x = s.getJSONObject(i)
                fun v3(k: String) = x.optJSONArray(k)?.let {
                    FloatArray3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat())
                }
                SensorSample(x.getLong("timestampNs"), v3("accel"), v3("gyro"))
            }
            val touches = o.optJSONArray("touches")?.let { a ->
                List(a.length()) { i ->
                    val t = a.getJSONObject(i)
                    TouchLabel(t.getLong("t"), t.getDouble("x").toFloat(), t.getDouble("y").toFloat(), t.getString("key"))
                }
            }.orEmpty()
            Trial(
                o.getString("id"), o.getString("label"), TrialTask.TYPED_TAP, ClientRole.CLIENT_A,
                0L, 0L, samples, notes = o.optString("notes"), sessionTag = o.optString("sessionTag"), touches = touches,
            )
        }

    /**
     * Ceiling with perfect tap detection: features anchored at the true touch times, 5-fold CV
     * by query. Shows how much is lost in detection vs. in the position model / spotter.
     */
    private fun oracle(trials: List<Trial>) {
        val typed = trials.filter { it.touches.isNotEmpty() && TapTrainer.labelOf(it) != TypedLabel.IDLE }
        data class Q(val trial: Trial, val taps: List<LabeledTap>)
        val qs = typed.map { t ->
            val (stream, _) = TapStream.scan(t.samples)
            Q(t, t.touches.mapNotNull { tc ->
                stream.featuresAt(tc.timestampNs)?.let { LabeledTap(it, tc.x, tc.y, tc.key, t.id, t.sessionTag, tc.timestampNs) }
            })
        }
        var row = 0; var key = 0; var n = 0; var errX = 0.0; var varX = 0.0; var errY = 0.0; var varY = 0.0
        val margins = ArrayList<Pair<TypedLabel, WordSpotter.Result>>()
        val folds = 5
        for (k in 0 until folds) {
            val train = qs.filterIndexed { i, _ -> i % folds != k }
            val test = qs.filterIndexed { i, _ -> i % folds == k }
            val model = TapPositionModel.fit(train.flatMap { it.taps })
            val mx = train.flatMap { it.taps }.map { it.x }.average()
            val my = train.flatMap { it.taps }.map { it.y }.average()
            for (q in test) for (tap in q.taps) {
                val (px, py) = model.predict(tap.features)
                val truth = KeyboardLayout.keyAt(tap.x, tap.y)
                val guess = model.likelyKey(px, py)
                if (truth == guess) key++
                if (truth.cy == guess.cy) row++
                n++
                errX += (px - tap.x) * (px - tap.x); varX += (tap.x - mx) * (tap.x - mx)
                errY += (py - tap.y) * (py - tap.y); varY += (tap.y - my) * (tap.y - my)
            }
            val sp = WordSpotter(model, 0f)
            for (q in test) margins += TapTrainer.labelOf(q.trial) to sp.score(q.taps.map { sp.observe(it.features, it.tNs) })
        }
        println("REPLAY oracle taps=%d R2x=%.2f R2y=%.2f row=%.2f key=%.2f".format(n, 1 - errX / varX, 1 - errY / varY, row.toFloat() / n, key.toFloat() / n))
        for ((truth, r) in margins.sortedByDescending { it.second.margin }.take(40)) {
            val best = WordSpotter.COMPETITORS.maxBy { r.scores.getValue(it) }
            println("REPLAY oracle %-6s target=%-6s margin=%6.2f g=%6.2f y=%6.2f best=%s %.2f".format(
                truth.id, r.target.id, r.margin, r.scores.getValue("google"), r.scores.getValue("yahoo"), best, r.scores.getValue(best)))
        }
        for (truth in listOf(TypedLabel.GOOGLE, TypedLabel.YAHOO)) {
            val ms = margins.filter { it.first == truth }.map { it.second.margin }.sorted()
            println("REPLAY oracle ${truth.id} margins: " + ms.joinToString { "%.1f".format(it) })
        }
    }

    /** Deployed model on saved live searches: taps detected per word at several thresholds. */
    @Test
    fun liveDiagnostics() {
        val dir = System.getenv("TYPED_DATA_DIR")?.let(::File)
        val modelFile = System.getenv("TYPED_MODEL")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory && modelFile != null && modelFile.isFile)
        val bundle = TapModelStore.fromJson(JSONObject(modelFile!!.readText()))
        val live = load(dir!!).filter { TapTrainer.isLiveKeyboard(it) }
        val det = bundle.detector!!
        val ths = floatArrayOf(det.threshold, 0.4f, 0.25f, 0.15f, 0.08f)
        println("LIVE detector threshold=${det.threshold}")
        for (t in live) {
            val chars = t.touches.size
            val counts = ths.map { th -> TapStream.scan(t.samples, detector = det.withThreshold(th)).second.size }
            val r = TapTrainer.observe(bundle.position, t, TapTrainer.Front(detector = det)).let {
                WordSpotter(bundle.position, bundle.threshold, bundle.weights).score(it)
            }
            println("LIVE %-12s keys=%2d detected@%s = %s  -> %s margin %.1f".format(
                "\"${t.label}\"", chars, ths.joinToString("/") { "%.2f".format(it) }, counts, r.label.id, r.margin))
        }
        // Lag from candidate tap onsets (low threshold) to each text change.
        val lags = ArrayList<Long>()
        for (t in live) {
            val (_, cands) = TapStream.scan(t.samples, detector = det.withThreshold(0.15f))
            val on = cands.map { it.onsetNs }
            for (x in t.touches) {
                val before = on.filter { it <= x.timestampNs + 20_000_000L && it >= x.timestampNs - 300_000_000L }
                if (before.isNotEmpty()) lags += (x.timestampNs - before.max()) / 1_000_000L
            }
        }
        lags.sort()
        val hist = lags.groupBy { (it / 20) * 20 }.toSortedMap()
        println("LIVE lag text-change minus nearest preceding onset (ms), n=${lags.size}:")
        for ((b, v) in hist) println("LIVE   %4d..%4d %s".format(b, b + 19, "#".repeat(v.size)))
        val play = load(dir).filter { it.touches.isNotEmpty() && !TapTrainer.isLiveKeyboard(it) }.take(30)
        for (th in ths) {
            val d = TapTrainer.detection(play, emptyList(), TapTrainer.Front(detector = det.withThreshold(th)))
            println("PLAY th=%.2f P=%.2f R=%.2f".format(th, d.precision, d.recall))
        }
    }

    /** Leave-one-session-out (sessions split at >2 min gaps — no labels), centering on vs off. */
    @Test
    fun sessionHoldout() {
        val dir = System.getenv("TYPED_DATA_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val trials = load(dir!!).sortedBy { it.samples.firstOrNull()?.timestampNs ?: 0L }
        val session = HashMap<String, Int>()
        var s = 0
        var last = Long.MIN_VALUE / 2
        for (t in trials) {
            val start = t.samples.firstOrNull()?.timestampNs ?: 0L
            if (start - last > TapCentering.SESSION_GAP_NS) s++
            session[t.id] = s - 1
            last = t.samples.lastOrNull()?.timestampNs ?: start
        }
        val sizes = trials.groupBy { session.getValue(it.id) }.mapValues { (_, v) -> "${v.size}(${v.count { TapTrainer.isLiveKeyboard(it) }} live)" }
        println("SESSION sessions=$s sizes=$sizes")
        for (on in listOf(false, true)) {
            TapCentering.enabled = on
            val r = TapTrainer.train(trials, foldOf = { session.getValue(it.id) }, foldCount = s).report!!
            println("SESSION centering=%s: real keyboard %d/%d right, %d/%d caught | all %d macroF1 %.2f acc %.2f falseFlag %.2f".format(
                on, r.liveCorrect, r.liveQueries, r.liveTargetsFlagged, r.liveTargets, r.testQueries, r.queryMacroF1, r.queryAccuracy, r.falseFlagRate))
        }
        TapCentering.enabled = false
    }

    @Test
    fun replay() {
        val dir = System.getenv("TYPED_DATA_DIR")?.let(::File)
        assumeTrue("TYPED_DATA_DIR not set", dir != null && dir.isDirectory)
        val trials = load(dir!!)
        println("REPLAY trials=${trials.size}")

        for (s in TapStream.SENSITIVITY_GRID) {
            val d = TapTrainer.detection(trials.filter { it.touches.isNotEmpty() }, emptyList(), TapTrainer.Front(s))
            println("REPLAY detect sens=%.1f P=%.2f R=%.2f F1=%.2f".format(s, d.precision, d.recall, d.f1))
        }

        oracle(trials)

        val t0 = System.nanoTime()
        val bundle = TapTrainer.train(trials)
        val typed = trials.filter { it.touches.isNotEmpty() && !TapTrainer.isIdle(it) }
        val acc = TapTrainer.EvalAccumulator()
        for (k in 0 until TapTrainer.CV_FOLDS) {
            val test = typed.filter { TapTrainer.foldOf(it.id) == k }
            acc.addFold(TapTrainer.fit(typed - test.toSet(), emptyList()), test, emptyList())
        }
        acc.misses.sorted().forEach { println("REPLAY miss $it") }
        // Accuracy by hold angle (gravity share on the long axis), no labels about who typed.
        val byId = typed.associateBy { it.id }
        fun up(t: Trial): Float {
            var x = 0.0; var y = 0.0; var z = 0.0
            for (smp in t.samples) smp.accel?.let { x += it.x; y += it.y; z += it.z }
            val n = kotlin.math.sqrt(x * x + y * y + z * z)
            return if (n == 0.0) 1f else (y / n).toFloat()
        }
        for ((lo, hi) in listOf(0f to 0.7f, 0.7f to 0.8f, 0.8f to 1.01f)) {
            val o = acc.outcomes.filter { up(byId.getValue(it.first)) in lo..hi }
            println("REPLAY angle %.2f-%.2f: %d/%d right".format(lo, hi, o.count { it.second }, o.size))
        }
        println("REPLAY train ${(System.nanoTime() - t0) / 1_000_000} ms")
        val r = bundle.report!!
        println("REPLAY report " + r.toJson().toString())
        println("REPLAY weights=${bundle.weights} thumb=${bundle.position.hasThumb} move=${bundle.position.move != null}")
    }
}
