package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.data.TouchLabel
import edu.iot.phoneangle.data.Trial
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure training / evaluation for the tap pipeline (no Android dependencies, unit-testable).
 *
 * Evaluation is [CV_FOLDS]-fold cross-validation by query: every query is tested exactly once,
 * by a model that never saw it (with ~100 queries a single 20% test split held only 3–4 targets,
 * so one query swung every number). Inside each fold the training queries split 75/25:
 * - fit: learned tap detector (or energy sensitivity), tap-position / thumb / movement models,
 * - validation: detector threshold, evidence weights, flag threshold.
 * The deployed model runs the same procedure on all queries.
 */
object TapTrainer {

    const val MATCH_TOLERANCE_MS = 60L
    const val MIN_TRAIN_TRIALS = 10
    const val CV_FOLDS = 5

    private fun mod(h: Int, n: Int) = ((h % n) + n) % n

    fun foldOf(trialId: String): Int = mod(trialId.hashCode(), CV_FOLDS)

    /** Validation membership, independent of [foldOf] (different bits of the hash). */
    fun isValidation(trialId: String): Boolean = mod(trialId.hashCode() / CV_FOLDS, 4) == 0

    /**
     * Query class. Play saves the label as typed, so a mistyped blocked word ("gogle" when the
     * prompt was google) is still a google attempt — that is what a filter should catch.
     */
    fun labelOf(trial: Trial): TypedLabel {
        val typed = TypedLabel.fromCollectLabel(trial.label)
        if (typed != TypedLabel.OTHER) return typed
        val prompt = trial.notes.substringAfter("prompt=", "").substringBefore(';')
        val prompted = TypedLabel.fromCollectLabel(prompt)
        return if (prompted == TypedLabel.GOOGLE || prompted == TypedLabel.YAHOO) prompted else typed
    }

    fun isIdle(trial: Trial): Boolean = labelOf(trial) == TypedLabel.IDLE

    const val LIVE_KEYBOARD_NOTE = "live_system_keyboard"

    /** Saved from the live search page (system keyboard, labels from text changes). */
    fun isLiveKeyboard(trial: Trial): Boolean = trial.notes.startsWith(LIVE_KEYBOARD_NOTE)

    /** How taps are found: learned detector when available, else the energy fallback. */
    data class Front(
        val sensitivity: Float = TapStream.DEFAULT_SENSITIVITY,
        val detector: TapDetector? = null,
        /** Per-recording rolling centers ([TapCentering]); null = features used raw. */
        val centers: Map<String, FloatArray>? = null,
        /** Mean raw tap features — the center before a session has enough taps. */
        val featureMean: FloatArray? = null,
    ) {
        fun scan(trial: Trial) = TapStream.scan(trial.samples, sensitivity, detector)

        fun centered(trial: Trial, features: FloatArray): FloatArray {
            val c = centers?.get(trial.id) ?: featureMean ?: return features
            return TapCentering.apply(features, c)
        }

        /**
         * Rolling centers over [history] in time order, from detected (unlabeled) taps only, so
         * each recording is centered on the taps before it — exactly what the live listener does.
         */
        fun withCenters(history: List<Trial>): Front {
            if (!TapCentering.enabled) return this
            val ordered = history.sortedBy { it.samples.firstOrNull()?.timestampNs ?: 0L }
            val taps = ordered.associate { t -> t.id to scan(t).second.filter { it.onsetNs >= queryStartNs(t) } }
            val all = taps.values.flatten()
            if (all.isEmpty()) return this
            val mean = FloatArray(TapStream.FEATURE_DIM)
            for (tp in all) for (i in mean.indices) mean[i] += tp.features[i]
            for (i in mean.indices) mean[i] /= all.size
            val rolling = TapCentering.Rolling()
            val out = HashMap<String, FloatArray>()
            for (t in ordered) {
                out[t.id] = rolling.center(t.samples.firstOrNull()?.timestampNs ?: 0L, mean)
                for (tp in taps.getValue(t.id)) rolling.add(tp.features, tp.onsetNs)
            }
            return copy(centers = out, featureMean = mean)
        }
    }

    /** One recording's labels for detector training. */
    class DetectorExamples(
        val trial: Trial,
        /** Tap onsets (positives). */
        val positives: List<Long>,
        /** Typed characters whose tap could not be located: frames near them are skipped. */
        val ignore: List<Long> = emptyList(),
        /** Row duplication factor (real-keyboard data counts more). */
        val weight: Int = 1,
    )

    fun playExamples(trial: Trial) = DetectorExamples(trial, trial.touches.map { it.timestampNs })

    /**
     * Learn the tap-onset detector: every grid frame within ±7.5 ms of a positive onset is
     * positive, a deterministic 15% of the rest are negatives. Null if too few positives.
     */
    fun fitDetector(examples: List<DetectorExamples>): TapDetector? {
        if (examples.sumOf { it.positives.size } < TapDetector.MIN_TRAIN_TOUCHES) return null
        val rows = ArrayList<FloatArray>()
        val labels = ArrayList<Boolean>()
        val rng = Random(8223)
        for (ex in examples) {
            val stream = TapStream(
                capacity = 64,
                onFrame = { ns, window ->
                    val skip = ex.ignore.any { ns in (it - IGNORE_BEFORE_NS)..(it + IGNORE_AFTER_NS) }
                    if (!skip) {
                        val pos = ex.positives.any { abs(it - ns) <= TapDetector.LABEL_TOLERANCE_NS }
                        if (pos || TapDetector.keepNegative(rng)) {
                            val row = window.copyOf()
                            repeat(ex.weight) {
                                rows += row
                                labels += pos
                            }
                        }
                    }
                },
            ) {}
            for (sample in ex.trial.samples) stream.offer(sample)
        }
        if (labels.count { it } < TapDetector.MIN_TRAIN_TOUCHES) return null
        return TapDetector.fit(rows, labels.toBooleanArray())
    }

    // ---- Real-keyboard (live) searches --------------------------------------------------------

    /** Real-keyboard data is what deployment looks like, so it counts this many times over. */
    const val LIVE_WEIGHT = 3
    /** Real-keyboard validation searches needed before they alone pick the detector threshold. */
    const val MIN_LIVE_FOR_TUNING = 8
    /** The system keyboard commits a letter on finger lift, ~40–150 ms after the tap onset. */
    private const val LAG_MIN_NS = 35_000_000L
    private const val LAG_MAX_NS = 150_000_000L
    private const val ALIGN_THRESHOLD = 0.15f
    private const val IGNORE_BEFORE_NS = 200_000_000L
    private const val IGNORE_AFTER_NS = 30_000_000L

    class Aligned(val trial: Trial, val unaligned: List<Long>)

    /**
     * Turn a live search's text-change labels into tap onsets: for each character (in order), take
     * the most confident candidate tap 35–150 ms before the text changed. Characters with no
     * candidate are dropped from the labels (and their neighbourhood ignored in detector training).
     */
    fun alignLive(trial: Trial, detector: TapDetector): Aligned {
        val cands = TapStream.scan(trial.samples, detector = detector.withThreshold(ALIGN_THRESHOLD)).second
        val used = BooleanArray(cands.size)
        var from = 0
        val touches = ArrayList<TouchLabel>()
        val unaligned = ArrayList<Long>()
        for (t in trial.touches.sortedBy { it.timestampNs }) {
            var best = -1
            for (i in from until cands.size) {
                val lag = t.timestampNs - cands[i].onsetNs
                if (lag > LAG_MAX_NS) continue
                if (lag < LAG_MIN_NS) break
                if (!used[i] && (best < 0 || cands[i].score > cands[best].score)) best = i
            }
            if (best >= 0) {
                used[best] = true
                from = best + 1
                touches += t.copy(timestampNs = cands[best].onsetNs)
            } else {
                unaligned += t.timestampNs
            }
        }
        return Aligned(trial.copy(touches = touches), unaligned)
    }

    /** Live searches include idle time before typing; the query starts 1 s before the first letter. */
    fun queryStartNs(trial: Trial): Long =
        if (isLiveKeyboard(trial)) (trial.touches.minOfOrNull { it.timestampNs } ?: Long.MIN_VALUE) - 1_000_000_000L
        else Long.MIN_VALUE

    /** Detector threshold with the best tap-detection F1 on [valSet] (default without data). */
    private fun pickDetectorThreshold(detector: TapDetector, valSet: List<Trial>, idle: List<Trial>): TapDetector {
        if (valSet.isEmpty()) return detector
        val best = TapDetector.THRESHOLD_GRID.maxBy { th ->
            detection(valSet, idle, Front(detector = detector.withThreshold(th))).f1
        }
        return detector.withThreshold(best)
    }

    /** Everything fitted on one set of training queries. */
    class Fitted(
        val front: Front,
        val position: TapPositionModel,
        val chosen: Calibration,
        /** Ablation configurations, each with its own validation threshold. */
        val configs: List<Pair<String, Calibration>>,
        val fitQueries: Int,
        val valQueries: Int,
        val fitTaps: Int,
        /** Detector trained on the training keyboard only, used to align real-keyboard searches. */
        val alignDetector: TapDetector? = null,
    )

    const val SELECTED = "Selected on validation"

    /** @param history all recordings in time order, for label-free rolling centering (defaults to [typed]). */
    fun fit(typed: List<Trial>, idle: List<Trial>, history: List<Trial> = typed): Fitted {
        val fitSet = typed.filter { !isValidation(it.id) }
        val valSet = typed.filter { isValidation(it.id) }
        check(fitSet.size >= MIN_TRAIN_TRIALS) {
            "Need at least $MIN_TRAIN_TRIALS training queries (have ${fitSet.size} of ${typed.size} total)."
        }
        val fitPlay = fitSet.filter { !isLiveKeyboard(it) }
        val fitLive = fitSet.filter { isLiveKeyboard(it) }

        // Stage 1: detector from the training keyboard's exact touch-downs.
        val stage1 = fitDetector(fitPlay.map { playExamples(it) })
        // Stage 2: locate real-keyboard taps with it, then retrain on both.
        val aligned = if (stage1 != null) fitLive.map { alignLive(it, stage1) } else emptyList()
        val stage2 = if (aligned.isEmpty()) stage1 else fitDetector(
            fitPlay.map { playExamples(it) } + aligned.map {
                DetectorExamples(it.trial, it.trial.touches.map { t -> t.timestampNs }, it.unaligned, LIVE_WEIGHT)
            },
        ) ?: stage1
        val valPlay = valSet.filter { !isLiveKeyboard(it) }
        val learned = stage2?.let { d ->
            val valAligned = if (stage1 != null) valSet.filter { isLiveKeyboard(it) }.map { alignLive(it, stage1).trial } else emptyList()
            // Deployment is the real keyboard: tune the threshold on its searches when there are enough.
            pickDetectorThreshold(d, if (valAligned.size >= MIN_LIVE_FOR_TUNING) valAligned else valPlay + valAligned, idle)
        }
        val front = (
            learned?.let { Front(detector = it) }
                ?: Front(sensitivity = TapStream.SENSITIVITY_GRID.maxBy { s -> detection(fitPlay, idle, Front(s)).f1 })
            ).withCenters(history)

        val alignedTrials = aligned.map { it.trial }.ifEmpty { fitLive }
        val taps = fitPlay.flatMap { labeledTaps(it, front) } +
            alignedTrials.flatMap { t -> labeledTaps(t, front).let { l -> List(LIVE_WEIGHT) { l }.flatten() } }
        val position = TapPositionModel.fit(taps)
        val valObs = valSet.map { labelOf(it) to observe(position, it, front) }
        val chosen = selectWeights(position, valObs)
        val configs = ablationConfigs(position).map { (name, w) -> name to calibrate(position, valObs, w) } +
            (SELECTED to chosen)
        return Fitted(front, position, chosen, configs, fitSet.size, valSet.size, taps.size, stage1)
    }

    /**
     * @param foldOf held-out fold per recording; [foldCount] folds. Default: [CV_FOLDS] by id hash.
     * Pass recording sessions instead to test on sessions (people / holds) never seen in training.
     */
    fun train(
        trials: List<Trial>,
        now: Long = System.currentTimeMillis(),
        foldOf: (Trial) -> Int = { foldOf(it.id) },
        foldCount: Int = CV_FOLDS,
    ): TapModelBundle {
        val typed = trials.filter { !isIdle(it) && it.touches.isNotEmpty() }
        val idle = trials.filter { isIdle(it) }

        val acc = EvalAccumulator()
        for (k in 0 until foldCount) {
            val test = typed.filter { foldOf(it) == k }
            val trainSet = typed.filter { foldOf(it) != k }
            if (test.isEmpty() || trainSet.count { !isValidation(it.id) } < MIN_TRAIN_TRIALS) continue
            // Centering history includes the test recordings' own earlier taps (unlabeled, as live).
            val fitted = fit(trainSet, idle.filter { foldOf(it) != k }, history = typed)
            acc.addFold(fitted, test, idle.filter { foldOf(it) == k })
        }

        val final = fit(typed, idle)
        val report = acc.report(final)
        return TapModelBundle(
            final.position, final.front.sensitivity, final.chosen.threshold, now, final.fitTaps, report,
            weights = final.chosen.weights, detector = final.front.detector, featureMean = final.front.featureMean,
        )
    }

    // ---- Tap detection vs. ground-truth touches -------------------------------------------

    data class Detection(val tp: Int, val fp: Int, val fn: Int, val idleFalseTaps: Int, val idleMinutes: Float) {
        val precision: Float get() = if (tp + fp == 0) 0f else tp.toFloat() / (tp + fp)
        val recall: Float get() = if (tp + fn == 0) 0f else tp.toFloat() / (tp + fn)
        val f1: Float get() = if (precision + recall == 0f) 0f else 2 * precision * recall / (precision + recall)
    }

    fun detection(trials: List<Trial>, idle: List<Trial>, front: Front): Detection {
        var tp = 0
        var fp = 0
        var fn = 0
        for (t in trials) {
            val (_, taps) = front.scan(t)
            val m = match(t.touches, taps.map { it.onsetNs })
            tp += m.pairs.size
            fp += taps.size - m.pairs.size
            fn += t.touches.size - m.pairs.size
        }
        var idleTaps = 0
        var idleNs = 0L
        for (t in idle) {
            idleTaps += front.scan(t).second.size
            if (t.samples.size > 1) idleNs += t.samples.last().timestampNs - t.samples.first().timestampNs
        }
        // Taps during idle handling are false positives too.
        return Detection(tp, fp + idleTaps, fn, idleTaps, idleNs / 60e9f)
    }

    data class Matching(val pairs: List<Pair<Int, Int>>, val offsetNs: Long)

    /** Greedy one-to-one matching of touches to detected onsets after removing the median lag. */
    fun match(touches: List<TouchLabel>, onsets: List<Long>): Matching {
        if (touches.isEmpty() || onsets.isEmpty()) return Matching(emptyList(), 0L)
        val tol = MATCH_TOLERANCE_MS * 1_000_000L
        val lags = touches.mapNotNull { t ->
            onsets.minByOrNull { abs(it - t.timestampNs) }?.let { it - t.timestampNs }?.takeIf { abs(it) <= 2 * tol }
        }.sorted()
        val offset = if (lags.isEmpty()) 0L else lags[lags.size / 2]
        val candidates = ArrayList<Triple<Long, Int, Int>>()
        for ((ti, t) in touches.withIndex()) {
            for ((oi, o) in onsets.withIndex()) {
                val d = abs(o - (t.timestampNs + offset))
                if (d <= tol) candidates += Triple(d, ti, oi)
            }
        }
        candidates.sortBy { it.first }
        val usedT = BooleanArray(touches.size)
        val usedO = BooleanArray(onsets.size)
        val pairs = ArrayList<Pair<Int, Int>>()
        for ((_, ti, oi) in candidates) {
            if (usedT[ti] || usedO[oi]) continue
            usedT[ti] = true
            usedO[oi] = true
            pairs += ti to oi
        }
        return Matching(pairs, offset)
    }

    /**
     * Labeled taps for one trial: detected onsets matched to touches (exactly what live sees),
     * plus missed touches anchored at touch time + median lag so every key press still trains.
     */
    fun labeledTaps(trial: Trial, front: Front): List<LabeledTap> {
        val (stream, taps) = front.scan(trial)
        val m = match(trial.touches, taps.map { it.onsetNs })
        val out = ArrayList<LabeledTap>(trial.touches.size)
        val matched = HashSet<Int>()
        for ((ti, oi) in m.pairs) {
            val t = trial.touches[ti]
            out += LabeledTap(front.centered(trial, taps[oi].features), t.x, t.y, t.key, trial.id, trial.sessionTag, t.timestampNs)
            matched += ti
        }
        for ((ti, t) in trial.touches.withIndex()) {
            if (ti in matched) continue
            val f = stream.featuresAt(t.timestampNs + m.offsetNs) ?: continue
            out += LabeledTap(front.centered(trial, f), t.x, t.y, t.key, trial.id, trial.sessionTag, t.timestampNs)
        }
        return out
    }

    // ---- Query-level spotting --------------------------------------------------------------

    /** Live-equivalent observations for one recorded query: detect taps, predict positions. */
    fun observe(position: TapPositionModel, trial: Trial, front: Front): List<WordSpotter.Obs> {
        val spotter = WordSpotter(position)
        val start = queryStartNs(trial)
        return front.scan(trial).second.filter { it.onsetNs >= start }
            .map { spotter.observe(front.centered(trial, it.features), it.onsetNs) }
    }

    data class Calibration(val threshold: Float, val weights: WordSpotter.Weights, val valMacroF1: Float)

    /**
     * Flag threshold for fixed weights. Validation macro-F1 is constant between consecutive
     * margins, so pick the best interval (ties to the stricter one) and return its midpoint,
     * leaving slack on both sides instead of sitting exactly on a validation example.
     */
    fun calibrate(
        position: TapPositionModel,
        valObs: List<Pair<TypedLabel, List<WordSpotter.Obs>>>,
        weights: WordSpotter.Weights,
    ): Calibration {
        val hasBoth = valObs.any { it.first != TypedLabel.OTHER } && valObs.any { it.first == TypedLabel.OTHER }
        if (!hasBoth) return Calibration(WordSpotter.DEFAULT_THRESHOLD, weights, Float.NaN)
        val probe = WordSpotter(position, 0f, weights)
        val scored = valObs.map { (truth, obs) -> truth to probe.score(obs) }
        val candidates = (scored.map { it.second.margin } + WordSpotter.DEFAULT_THRESHOLD)
            .filter { it.isFinite() && it > 0f }
            .distinct()
            .sorted()
        fun f1At(th: Float) = macroF1(
            scored.map { (truth, r) ->
                truth to if (r.margin >= th && r.tapCount >= WordSpotter.MIN_TAPS) r.target else TypedLabel.OTHER
            },
        )
        val best = candidates.indices.maxWith(compareBy<Int> { f1At(candidates[it]) }.thenBy { it })
        val lower = if (best == 0) 0f else candidates[best - 1]
        return Calibration((lower + candidates[best]) / 2f, weights, f1At(candidates[best]))
    }

    val WEIGHT_GRID = floatArrayOf(0f, 0.5f, 1f)

    /**
     * Pick thumb / double-key-timing weights on validation. Ties go to the simpler setting, so an
     * extra stays off unless validation shows it helps (or when there is too little validation data).
     */
    fun selectWeights(
        position: TapPositionModel,
        valObs: List<Pair<TypedLabel, List<WordSpotter.Obs>>>,
    ): Calibration {
        val thumbGrid = if (position.hasThumb) WEIGHT_GRID else floatArrayOf(0f)
        val moveGrid = if (position.move != null) WEIGHT_GRID else floatArrayOf(0f)
        val options = thumbGrid.flatMap { th ->
            WEIGHT_GRID.flatMap { ti -> moveGrid.map { mv -> WordSpotter.Weights(th, ti, mv) } }
        }
        return options.map { calibrate(position, valObs, it) }
            .maxWith(
                compareBy<Calibration> { if (it.valMacroF1.isNaN()) -1f else it.valMacroF1 }
                    .thenBy { -(it.weights.thumb + it.weights.doubleKeyTiming + it.weights.movement) }
                    .thenBy { it.threshold },
            )
    }

    private fun ablationConfigs(position: TapPositionModel) = buildList {
        add("Position only" to WordSpotter.Weights())
        add("+ double-key timing" to WordSpotter.Weights(doubleKeyTiming = 1f))
        if (position.hasThumb) add("+ thumb side" to WordSpotter.Weights(thumb = 1f))
        if (position.move != null) add("+ movement" to WordSpotter.Weights(movement = 1f))
        add(
            "+ all" to WordSpotter.Weights(
                thumb = if (position.hasThumb) 1f else 0f,
                doubleKeyTiming = 1f,
                movement = if (position.move != null) 1f else 0f,
            ),
        )
    }

    // ---- Held-out evaluation (pooled over CV folds) -------------------------------------------

    /** Pools raw counts over folds so the report covers every query once. */
    class EvalAccumulator {
        private var tp = 0
        private var fp = 0
        private var fn = 0
        private var idleTaps = 0
        private var idleMinutes = 0f
        private var errSum = 0f
        private var keyHits = 0
        private var rowHits = 0
        private var nTaps = 0
        private var thumbHits = 0
        private var thumbN = 0
        private var scoreNs = 0L
        private var scored = 0
        private val preds = linkedMapOf<String, MutableList<Pair<TypedLabel, TypedLabel>>>()
        var folds = 0
            private set

        /** "truth->pred: typed text" for every wrong query (debug / report). */
        val misses = ArrayList<String>()
        private val livePreds = ArrayList<Pair<TypedLabel, TypedLabel>>()
        /** (recording id, correct) for every held-out query — e.g. accuracy by hold angle. */
        val outcomes = ArrayList<Pair<String, Boolean>>()
        /** Debug: every real-keyboard target search with tap count and scores. */
        val liveDetail = ArrayList<String>()

        fun addFold(f: Fitted, test: List<Trial>, idleTest: List<Trial>) {
            folds++
            // Detection P/R needs exact touch-downs, which only the training keyboard records.
            val det = detection(test.filter { !isLiveKeyboard(it) }, idleTest, f.front)
            tp += det.tp; fp += det.fp; fn += det.fn
            idleTaps += det.idleFalseTaps
            idleMinutes += det.idleMinutes
            val position = f.position
            val tapTrials = test.map { t ->
                if (isLiveKeyboard(t) && f.alignDetector != null) alignLive(t, f.alignDetector).trial else t
            }
            for (t in tapTrials) for (tap in labeledTaps(t, f.front)) {
                val (px, py) = position.predict(tap.features)
                val dx = (px - tap.x) / KeyboardLayout.KEY_W
                val dy = (py - tap.y) / KeyboardLayout.KEY_W // both axes in key widths
                errSum += sqrt(dx * dx + dy * dy)
                val truth = KeyboardLayout.keyAt(tap.x, tap.y)
                val guess = position.likelyKey(px, py)
                if (truth == guess) keyHits++
                if (truth.cy == guess.cy) rowHits++
                nTaps++
                val thumb = TapPositionModel.thumbLabel(tap)
                if (thumb != null && position.hasThumb) {
                    if ((position.rightThumb(position.scaled(tap.features)) > 0.5f) == thumb) thumbHits++
                    thumbN++
                }
            }
            val testObs = test.map { labelOf(it) to observe(position, it, f.front) }
            for ((name, cal) in f.configs) {
                val spotter = WordSpotter(position, cal.threshold, cal.weights)
                val list = preds.getOrPut(name) { ArrayList() }
                for ((i, pair) in testObs.withIndex()) {
                    val (truth, obs) = pair
                    val t0 = System.nanoTime()
                    val r = spotter.score(obs)
                    if (name == SELECTED) {
                        scoreNs += System.nanoTime() - t0
                        scored++
                        if (truth != r.label) misses += "${truth.id}->${r.label.id}: \"${test[i].label}\"" + if (isLiveKeyboard(test[i])) " [real keyboard]" else ""
                        if (isLiveKeyboard(test[i])) livePreds += truth to r.label
                        outcomes += test[i].id to (truth == r.label)
                        if (isLiveKeyboard(test[i]) && truth != TypedLabel.OTHER) {
                            liveDetail += "%s taps=%d keys=%d margin=%.1f g=%.1f y=%.1f thr=%.1f -> %s".format(
                                test[i].label, r.tapCount, test[i].touches.size, r.margin,
                                r.scores.getValue("google"), r.scores.getValue("yahoo"), cal.threshold, r.label.id,
                            )
                        }
                    }
                    list += truth to r.label
                }
            }
        }

        fun report(final: Fitted): TapEvalReport {
            val chosen = preds[SELECTED].orEmpty()
            val classes = TypedLabel.SNIPPED
            val confusion = Array(classes.size) { IntArray(classes.size) }
            for ((truth, pred) in chosen) confusion[classes.indexOf(truth)][classes.indexOf(pred)]++
            fun nan(ok: Boolean, v: () -> Float) = if (ok) v() else Float.NaN
            val p = nan(tp + fp > 0) { tp.toFloat() / (tp + fp) }
            val r = nan(tp + fn > 0) { tp.toFloat() / (tp + fn) }
            val finalThresholds = final.configs.toMap()
            val ablation = preds.map { (name, list) ->
                val cal = finalThresholds[name]
                AblationRow(
                    name = name,
                    thumbWeight = cal?.weights?.thumb ?: 0f,
                    timingWeight = cal?.weights?.doubleKeyTiming ?: 0f,
                    threshold = cal?.threshold ?: Float.NaN,
                    testMacroF1 = nan(list.isNotEmpty()) { macroF1(list) },
                    testFalseFlagRate = falseFlagRate(list),
                    movementWeight = cal?.weights?.movement ?: 0f,
                )
            }
            return TapEvalReport(
                testQueries = chosen.size,
                tapPrecision = p,
                tapRecall = r,
                idleFalseTapsPerMin = nan(idleMinutes > 0f) { idleTaps / idleMinutes },
                meanTapErrorKeys = nan(nTaps > 0) { errSum / nTaps },
                keyAccuracy = nan(nTaps > 0) { keyHits.toFloat() / nTaps },
                rowAccuracy = nan(nTaps > 0) { rowHits.toFloat() / nTaps },
                queryAccuracy = nan(chosen.isNotEmpty()) { chosen.count { it.first == it.second }.toFloat() / chosen.size },
                queryMacroF1 = nan(chosen.isNotEmpty()) { macroF1(chosen) },
                falseFlagRate = falseFlagRate(chosen),
                meanScoreMs = if (scored == 0) 0f else scoreNs / 1e6f / scored,
                confusion = confusion,
                sensitivity = final.front.sensitivity,
                threshold = final.chosen.threshold,
                trainQueries = final.fitQueries,
                valQueries = final.valQueries,
                trainTaps = final.fitTaps,
                thumbAccuracy = nan(thumbN > 0) { thumbHits.toFloat() / thumbN },
                detectorThreshold = final.front.detector?.threshold ?: Float.NaN,
                ablation = ablation,
                cvFolds = folds,
                liveQueries = livePreds.size,
                liveCorrect = livePreds.count { it.first == it.second },
                liveTargets = livePreds.count { it.first != TypedLabel.OTHER },
                liveTargetsFlagged = livePreds.count { it.first != TypedLabel.OTHER && it.second == it.first },
            )
        }

        private fun falseFlagRate(list: List<Pair<TypedLabel, TypedLabel>>): Float {
            val others = list.count { it.first == TypedLabel.OTHER }
            if (others == 0) return Float.NaN
            return list.count { it.first == TypedLabel.OTHER && it.second != TypedLabel.OTHER }.toFloat() / others
        }
    }

    fun macroF1(preds: List<Pair<TypedLabel, TypedLabel>>): Float {
        val f1s = TypedLabel.SNIPPED.map { c ->
            val tp = preds.count { it.first == c && it.second == c }
            val fp = preds.count { it.first != c && it.second == c }
            val fn = preds.count { it.first == c && it.second != c }
            if (tp == 0) 0f else 2f * tp / (2f * tp + fp + fn)
        }
        return f1s.average().toFloat()
    }
}

/** Held-out (test-split) numbers for the report and the Extra credit screen. */
data class TapEvalReport(
    val testQueries: Int,
    val tapPrecision: Float,
    val tapRecall: Float,
    val idleFalseTapsPerMin: Float,
    /** Mean distance between predicted and true tap position, in key widths. */
    val meanTapErrorKeys: Float,
    /** Nearest key to the predicted position is the true key. */
    val keyAccuracy: Float,
    val rowAccuracy: Float,
    val queryAccuracy: Float,
    val queryMacroF1: Float,
    /** Share of `other` queries that were flagged. */
    val falseFlagRate: Float,
    val meanScoreMs: Float,
    /** Rows = truth, cols = prediction, order [TypedLabel.SNIPPED]. */
    val confusion: Array<IntArray>,
    val sensitivity: Float,
    val threshold: Float,
    val trainQueries: Int = 0,
    val valQueries: Int = 0,
    val trainTaps: Int = 0,
    /** Right/left thumb accuracy on two-thumb test taps away from the keyboard centre. */
    val thumbAccuracy: Float = Float.NaN,
    /** Learned detector probability threshold (NaN = energy fallback was used). */
    val detectorThreshold: Float = Float.NaN,
    /** Cross-validation folds the test numbers are pooled over (0 = none ran). */
    val cvFolds: Int = 0,
    /** Searches typed on the real system keyboard (live page, "Save my searches") in the CV pool. */
    val liveQueries: Int = 0,
    val liveCorrect: Int = 0,
    val liveTargets: Int = 0,
    val liveTargetsFlagged: Int = 0,
    /** Test macro-F1 / false-flag rate per evidence configuration (each with its own val threshold). */
    val ablation: List<AblationRow> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        fun f(k: String, v: Float) = put(k, if (v.isFinite()) v.toDouble() else JSONObject.NULL)
        put("testQueries", testQueries)
        f("tapPrecision", tapPrecision)
        f("tapRecall", tapRecall)
        f("idleFalseTapsPerMin", idleFalseTapsPerMin)
        f("meanTapErrorKeys", meanTapErrorKeys)
        f("keyAccuracy", keyAccuracy)
        f("rowAccuracy", rowAccuracy)
        f("queryAccuracy", queryAccuracy)
        f("queryMacroF1", queryMacroF1)
        f("falseFlagRate", falseFlagRate)
        f("meanScoreMs", meanScoreMs)
        f("sensitivity", sensitivity)
        f("threshold", threshold)
        put("trainQueries", trainQueries)
        put("valQueries", valQueries)
        put("trainTaps", trainTaps)
        f("thumbAccuracy", thumbAccuracy)
        f("detectorThreshold", detectorThreshold)
        put("cvFolds", cvFolds)
        put("liveQueries", liveQueries)
        put("liveCorrect", liveCorrect)
        put("liveTargets", liveTargets)
        put("liveTargetsFlagged", liveTargetsFlagged)
        put("ablation", JSONArray().also { a -> ablation.forEach { a.put(it.toJson()) } })
        put("confusion", JSONArray().also { a -> confusion.forEach { row -> a.put(JSONArray(row.toList())) } })
    }

    companion object {
        fun fromJson(o: JSONObject): TapEvalReport {
            fun f(k: String) = if (o.isNull(k)) Float.NaN else o.optDouble(k, Double.NaN).toFloat()
            val c = o.getJSONArray("confusion")
            return TapEvalReport(
                testQueries = o.getInt("testQueries"),
                tapPrecision = f("tapPrecision"),
                tapRecall = f("tapRecall"),
                idleFalseTapsPerMin = f("idleFalseTapsPerMin"),
                meanTapErrorKeys = f("meanTapErrorKeys"),
                keyAccuracy = f("keyAccuracy"),
                rowAccuracy = f("rowAccuracy"),
                queryAccuracy = f("queryAccuracy"),
                queryMacroF1 = f("queryMacroF1"),
                falseFlagRate = f("falseFlagRate"),
                meanScoreMs = f("meanScoreMs"),
                confusion = Array(c.length()) { r ->
                    val row = c.getJSONArray(r)
                    IntArray(row.length()) { row.getInt(it) }
                },
                sensitivity = f("sensitivity"),
                threshold = f("threshold"),
                trainQueries = o.optInt("trainQueries"),
                valQueries = o.optInt("valQueries"),
                trainTaps = o.optInt("trainTaps"),
                thumbAccuracy = f("thumbAccuracy"),
                detectorThreshold = f("detectorThreshold"),
                cvFolds = o.optInt("cvFolds"),
                liveQueries = o.optInt("liveQueries"),
                liveCorrect = o.optInt("liveCorrect"),
                liveTargets = o.optInt("liveTargets"),
                liveTargetsFlagged = o.optInt("liveTargetsFlagged"),
                ablation = o.optJSONArray("ablation")?.let { a ->
                    List(a.length()) { AblationRow.fromJson(a.getJSONObject(it)) }
                }.orEmpty(),
            )
        }
    }
}

/** One row of the evidence ablation: which extras were on, and held-out results. */
data class AblationRow(
    val name: String,
    val thumbWeight: Float,
    val timingWeight: Float,
    val threshold: Float,
    val testMacroF1: Float,
    val testFalseFlagRate: Float,
    val movementWeight: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("movementWeight", movementWeight.toDouble())
        put("thumbWeight", thumbWeight.toDouble())
        put("timingWeight", timingWeight.toDouble())
        put("threshold", threshold.toDouble())
        put("testMacroF1", if (testMacroF1.isFinite()) testMacroF1.toDouble() else JSONObject.NULL)
        put("testFalseFlagRate", if (testFalseFlagRate.isFinite()) testFalseFlagRate.toDouble() else JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): AblationRow {
            fun f(k: String) = if (o.isNull(k)) Float.NaN else o.getDouble(k).toFloat()
            return AblationRow(
                o.getString("name"), f("thumbWeight"), f("timingWeight"), f("threshold"),
                f("testMacroF1"), f("testFalseFlagRate"), o.optDouble("movementWeight", 0.0).toFloat(),
            )
        }
    }
}
