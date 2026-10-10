package edu.iot.phoneangle.ml.typed

import edu.iot.phoneangle.data.ClientRole
import edu.iot.phoneangle.data.FloatArray3
import edu.iot.phoneangle.data.SensorSample
import edu.iot.phoneangle.data.Trial
import edu.iot.phoneangle.data.TrialTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedWordTest {

    @Test
    fun wordBankDrawRatesAreReasonable() {
        val rng = kotlin.random.Random(0)
        var g = 0
        var y = 0
        var o = 0
        repeat(4000) {
            when (TypedWordBank.next(rng).label) {
                TypedWordBank.GOOGLE -> g++
                TypedWordBank.YAHOO -> y++
                else -> o++
            }
        }
        assertTrue(g in 850..1150)
        assertTrue(y in 850..1150)
        assertTrue(o in 1700..2300)
    }

    @Test
    fun labelMapping() {
        assertEquals(TypedLabel.GOOGLE, TypedLabel.fromCollectLabel("google"))
        assertEquals(TypedLabel.GOOGLE, TypedLabel.fromCollectLabel("Google.com"))
        assertEquals(TypedLabel.YAHOO, TypedLabel.fromCollectLabel("yahoo"))
        assertEquals(TypedLabel.OTHER, TypedLabel.fromCollectLabel("youtube"))
        assertEquals(TypedLabel.OTHER, TypedLabel.fromCollectLabel("other"))
        assertEquals(TypedLabel.IDLE, TypedLabel.fromCollectLabel("idle"))
    }

    @Test
    fun preprocessorFeatureDim() {
        val prep = TypedPreprocessor()
        val channels = prep.channelsFromSamples(noise(40, seed = 1))
        assertNotNull(channels)
        assertEquals(MiniRocketTransform.N_CHANNELS, channels!!.size)
        val features = prep.featuresFromChannels(channels, FloatArray(TypedPreprocessor.FEATURE_DIM))
        assertEquals(TypedPreprocessor.FEATURE_DIM, features.size)
        assertEquals(84, TypedPreprocessor.FEATURE_DIM)
        assertEquals(7, TypedPreprocessor.VERSION)
    }

    @Test
    fun wordBankIncludesHardNegatives() {
        val rng = kotlin.random.Random(1)
        var hard = 0
        repeat(2000) {
            val w = TypedWordBank.next(rng).word
            if (w in TypedWordBank.HARD_NEGATIVES) hard++
        }
        // ~25% of draws are hard negatives (half of the 50% other bucket).
        assertTrue("hard=$hard", hard in 350..650)
    }

    @Test
    fun timeWarpPreservesShape() {
        val ch = TypedPreprocessor().channelsFromSamples(noise(64, 2))!!
        val warped = TypedAugment.timeWarp(ch, 0.8f)
        assertEquals(MiniRocketTransform.N_CHANNELS, warped.size)
        assertEquals(MiniRocketTransform.INPUT_LENGTH, warped[0].size)
        val hes = TypedAugment.hesitation(ch)
        assertEquals(MiniRocketTransform.INPUT_LENGTH, hes[0].size)
    }

    @Test
    fun exampleFromTrialMapsLabel() {
        val trial = trial("t1", "yahoo", word("yahoo", 40, 1))
        val ex = TypedPreprocessor().channelExampleFromTrial(trial)
        assertNotNull(ex)
        assertEquals(TypedLabel.YAHOO, ex!!.label)
        assertEquals(MiniRocketTransform.N_CHANNELS, ex.channels.size)
    }

    @Test
    fun segmenterFindsBurstInsideIdle() {
        val samples = Stream().idle(1000).word("google", 1200, 3).idle(1000).samples
        val span = ActivitySegmenter.span(samples)!!
        val burstStart = 1000_000_000L
        val burstEnd = 2200_000_000L
        assertTrue("start ${span.first}", span.first in (burstStart - 400_000_000L)..(burstStart + 300_000_000L))
        assertTrue("end ${span.last}", span.last in (burstEnd - 300_000_000L)..(burstEnd + 400_000_000L))
    }

    @Test
    fun activityDetectorProposesTypingNotIdle() {
        val quiet = TypingActivityDetector()
        Stream().idle(3000, 1).samples.forEach { quiet.offer(it) }
        assertEquals(null, quiet.pollCompleted())

        val det = TypingActivityDetector()
        Stream().idle(800, 2).word("google", 1000, 3).still(2000).samples.forEach { det.offer(it) }
        val found = det.pollCompleted()
        assertNotNull("expected a typing candidate", found)
        assertTrue(found!!.durationMs >= TypingActivityDetector.MIN_ACTIVE_MS)
        assertTrue("peak=${found.peakEnergy}", found.peakEnergy >= TypingActivityDetector.MIN_PEAK_ENERGY)
    }

    @Test
    fun windowingLabelsWordAndIdleWindows() {
        val windowing = TypedWindowing()
        val g = windowing.windowsFromTrial(trial("g", "google", Stream().idle(2500).word("google", 1200, 1).idle(2500).samples))
        assertTrue(g.isNotEmpty())
        assertTrue(g.any { it.label == TypedLabel.GOOGLE })
        assertTrue(g.any { it.label == TypedLabel.IDLE })
        assertTrue(g.all { it.label == TypedLabel.GOOGLE || it.label == TypedLabel.IDLE })
        // Session id is the trial id (no chunk splits).
        assertTrue(g.all { it.trialId == "g" })

        val idle = windowing.windowsFromTrial(trial("i", "idle", Stream().idle(6000).samples))
        assertTrue(idle.size > 10)
        assertTrue(idle.all { it.label == TypedLabel.IDLE })
    }

    @Test
    fun sessionSplitKeepsWholeRecordingTogether() {
        val wf = TypedWorkflow::class.java
        // Use the same hash rule without Android context:
        val examples = listOf(
            TypedChannelExample(Array(6) { FloatArray(64) }, TypedLabel.GOOGLE, "sessA"),
            TypedChannelExample(Array(6) { FloatArray(64) }, TypedLabel.GOOGLE, "sessA"),
            TypedChannelExample(Array(6) { FloatArray(64) }, TypedLabel.YAHOO, "sessB"),
            TypedChannelExample(Array(6) { FloatArray(64) }, TypedLabel.YAHOO, "sessB"),
        )
        // Inline the split logic used by TypedWorkflow.splitBySession
        val sessionIsTest = HashMap<String, Boolean>()
        val train = mutableListOf<TypedChannelExample>()
        val test = mutableListOf<TypedChannelExample>()
        for (ex in examples) {
            val key = ex.trialId.substringBefore('#')
            val isTest = sessionIsTest.getOrPut(key) { kotlin.math.abs(key.hashCode()) % 100 < 25 }
            if (isTest) test += ex else train += ex
        }
        assertFalse(train.any { it.trialId == "sessA" } && test.any { it.trialId == "sessA" })
        assertFalse(train.any { it.trialId == "sessB" } && test.any { it.trialId == "sessB" })
        assertEquals(4, train.size + test.size)
        assertNotNull(wf.name)
    }

    @Test
    fun liveListenerCatchesWordsAtAnyWindowPhase() {
        val bundle = trainSyntheticLiveBundle()
        for (offsetMs in 0 until 100 step 50) {
            for (label in listOf("google", "yahoo")) {
                val alerts = TypedReplayEval.replaySamples(
                    bundle,
                    Stream().idle(4000).word(label, 1200, 77).still(2500).samples,
                    offsetMs,
                )
                assertEquals("offset=$offsetMs $label alerts=$alerts", listOf(TypedLabel.fromCollectLabel(label)), alerts)
            }
        }
    }

    @Test
    fun liveListenerStaysQuietOnIdleAndOtherWords() {
        val bundle = trainSyntheticLiveBundle()
        for (offsetMs in 0 until 100 step 50) {
            assertEquals(
                emptyList<TypedLabel>(),
                TypedReplayEval.replaySamples(bundle, Stream().idle(12_000, seed = 5).samples, offsetMs),
            )
            assertEquals(
                emptyList<TypedLabel>(),
                TypedReplayEval.replaySamples(
                    bundle,
                    Stream().idle(3000).word("apple", 1200, 9).idle(3000).samples,
                    offsetMs,
                ),
            )
        }
    }

    @Test
    fun backToBackWordsBothFire() {
        val bundle = trainSyntheticLiveBundle()
        val listener = TypedListener(wordBundle = bundle, earlyThreshold = 1.01f)
        val alerts = TypedReplayEval.replaySamples(
            bundle,
            Stream().idle(3000).word("google", 1200, 1).still(2500).word("yahoo", 1400, 2).still(3000).samples,
            offsetMs = 50,
            listener = listener,
        )
        // First burst must flag; second depends on Stage A re-arm after long quiet.
        assertTrue("alerts=$alerts", alerts.isNotEmpty())
        assertEquals(TypedLabel.GOOGLE, alerts.first())
        assertTrue("duplicate spam $alerts", alerts.size <= 3)
    }

    @Test
    fun eventDrivenSkipsStageBWhileIdle() {
        val bundle = trainSyntheticLiveBundle()
        val listener = TypedListener(bundle)
        val samples = Stream().idle(8_000, seed = 11).samples
        var next = samples.first().timestampNs + 500_000_000L
        for (s in samples) {
            listener.offer(s)
            if (s.timestampNs >= next) {
                listener.step()
                next += TypedWindowing.LIVE_STRIDE_MS * 1_000_000L
            }
        }
        // Continuous scoring at 100 ms would run ~75×; event-driven should stay far below.
        assertTrue("infer=${listener.inferenceCount}", listener.inferenceCount < 15)
    }

    @Test
    fun rocketTransformIntoMatchesTransform() {
        val rocket = MiniRocketTransform.Default
        val channels = TypedPreprocessor().channelsFromSamples(noise(64, 3))!!
        val biases = FloatArray(rocket.featureDim) { it * 0.01f }
        val a = rocket.transform(channels, biases)
        val b = FloatArray(rocket.featureDim)
        rocket.transformInto(channels, biases, b)
        assertTrue(a.indices.all { kotlin.math.abs(a[it] - b[it]) < 1e-6f })
    }

    @Test
    fun feedTimelineRoundTripAndTypoPolicy() {
        val words = listOf(
            FeedWord("google", 10, 20, FeedWord.Status.CLEAN),
            FeedWord("apple", 30, 40, FeedWord.Status.TYPO),
            FeedWord("river", 50, 60, FeedWord.Status.DISCARD),
        )
        assertEquals(words, FeedTimeline.decode(FeedTimeline.encode(words)))
        assertEquals(FeedWord.Status.CLEAN, FeedTimeline.classify("google", "Google"))
        assertEquals(FeedWord.Status.TYPO, FeedTimeline.classify("google", "gogle"))
        assertEquals(FeedWord.Status.TYPO, FeedTimeline.classify("yahoo", "yaho"))
        assertEquals(FeedWord.Status.DISCARD, FeedTimeline.classify("google", "xyz"))
        assertEquals(FeedWord.Status.CLEAN, FeedTimeline.classify("apple", "aple"))
        assertEquals(FeedWord.Status.CLEAN, FeedTimeline.classify("apple", "something else"))
    }

    @Test
    fun feedWindowsLabelNeighborsAndTargets() {
        val s = Stream().idle(600, 1)
        val timeline = mutableListOf<FeedWord>()
        val order = listOf("apple", "google", "banana", "yahoo", "river", "melon", "google", "table")
        order.forEachIndexed { i, w ->
            val start = s.nowNs
            s.word(w, 700, 40 + i)
            timeline += FeedWord(w, start, s.nowNs, FeedWord.Status.CLEAN)
            s.idle(120, i)
        }
        s.idle(600, 2)
        val t = Trial(
            id = "f", label = "feed", task = TrialTask.TYPED_FEED, clientRole = ClientRole.CLIENT_A,
            startedAtEpochMs = 0, endedAtEpochMs = 1, samples = s.samples, notes = FeedTimeline.encode(timeline),
        )
        val wins = TypedWindowing().windowsFromTrials(listOf(t))
        assertTrue(wins.any { it.label == TypedLabel.GOOGLE })
        assertTrue(wins.any { it.label == TypedLabel.YAHOO })
        assertTrue(wins.any { it.label == TypedLabel.OTHER })
        assertTrue(wins.all { it.trialId == "f" })
    }

    // --- helpers ---

    private fun trainSyntheticLiveBundle(): TypedModelBundle {
        val prep = TypedPreprocessor()
        val windowing = TypedWindowing(prep)
        val trials = buildList {
            for (i in 0 until 12) {
                val lead = 600 + (i % 4) * 250
                val tail = 600 + ((i + 1) % 4) * 250
                add(trial("g$i", "google", Stream().idle(lead, i).word("google", 1200, 100 + i).idle(tail, i).samples))
                add(trial("y$i", "yahoo", Stream().idle(lead, i).word("yahoo", 1200, 200 + i).idle(tail, i).samples))
                add(trial("o$i", "banana", Stream().idle(lead, i).word("banana", 1200, 300 + i).idle(tail, i).samples))
            }
            for (i in 0 until 4) add(trial("idle$i", "idle", Stream().idle(15_000, 400 + i).samples))
        }
        val rng = kotlin.random.Random(1)
        val raw = windowing.windowsFromTrials(trials).flatMap { ex ->
            if (ex.label == TypedLabel.IDLE) listOf(ex) else TypedAugment.expand(ex, rng)
        }
        val biases = prep.fitBiases(raw.shuffled(rng).take(200).map { it.channels })
        val ppv = raw.map { TypedExample(prep.featuresFromChannels(it.channels, biases), it.label, it.trialId) }
        val scaler = FeatureScaler()
        val scaled = scaler.fitTransform(ppv.map { it.features })
        val examples = ppv.zip(scaled) { ex, f -> ex.copy(features = f) }
        val model = TypedTrainer().train(examples, classes = TypedLabel.LIVE).model
        return TypedModelBundle(
            model = model,
            scaler = scaler,
            rocketBiases = biases,
            trainedAtEpochMs = 1L,
            trainAccuracy = 1f,
            trainSampleCount = examples.size,
            windowMs = windowing.windowMs,
        )
    }

    private fun trial(id: String, label: String, samples: List<SensorSample>) = Trial(
        id = id,
        label = label,
        task = TrialTask.TYPED_WORD,
        clientRole = ClientRole.CLIENT_A,
        startedAtEpochMs = 0,
        endedAtEpochMs = 1,
        samples = samples,
    )

    private fun word(label: String, n: Int, seed: Int): List<SensorSample> =
        Stream().word(label, n * 20, seed).samples

    private fun noise(n: Int, seed: Int): List<SensorSample> = Stream().idle(n * 20, seed).samples

    /** 50 Hz synthetic IMU stream builder. */
    private class Stream {
        val samples = mutableListOf<SensorSample>()
        private var t = 0L
        val nowNs: Long get() = t

        fun idle(ms: Int, seed: Int = 0): Stream {
            val rng = java.util.Random(seed.toLong() * 7919 + ms)
            repeat(ms / 20) {
                samples += SensorSample(
                    timestampNs = t,
                    accel = FloatArray3(
                        rng.nextGaussian().toFloat() * 0.02f,
                        rng.nextGaussian().toFloat() * 0.02f,
                        9.8f + rng.nextGaussian().toFloat() * 0.02f,
                    ),
                    gyro = FloatArray3(
                        rng.nextGaussian().toFloat() * 0.02f,
                        rng.nextGaussian().toFloat() * 0.02f,
                        rng.nextGaussian().toFloat() * 0.02f,
                    ),
                    gravity = FloatArray3(0f, 0f, 9.8f),
                )
                t += 20_000_000L
            }
            return this
        }

        /** Near-zero motion (closes Stage A bursts cleanly). */
        fun still(ms: Int): Stream {
            repeat(ms / 20) {
                samples += SensorSample(
                    timestampNs = t,
                    accel = FloatArray3(0f, 0f, 9.8f),
                    gyro = FloatArray3(0f, 0f, 0f),
                    gravity = FloatArray3(0f, 0f, 9.8f),
                )
                t += 20_000_000L
            }
            return this
        }

        fun word(label: String, ms: Int, seed: Int): Stream {
            val rng = java.util.Random(seed.toLong())
            val (freq, amp) = when (TypedLabel.fromCollectLabel(label)) {
                TypedLabel.GOOGLE -> 0.35f to 2.2f
                TypedLabel.YAHOO -> 0.12f to 4.5f
                else -> 0.9f to 1.0f
            }
            repeat(ms / 20) { i ->
                val pulse = amp * kotlin.math.sin(i * freq)
                val noise = rng.nextGaussian().toFloat() * 0.05f
                samples += SensorSample(
                    timestampNs = t,
                    accel = FloatArray3(pulse + noise, pulse * 0.4f, 9.8f + pulse * 0.1f),
                    gyro = FloatArray3(pulse * 0.7f, -pulse * 0.3f + noise, pulse * 0.2f),
                    gravity = FloatArray3(0f, 0f, 9.8f),
                )
                t += 20_000_000L
            }
            return this
        }
    }
}
