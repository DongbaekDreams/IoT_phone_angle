package edu.iot.phoneangle.ml

import edu.iot.phoneangle.data.ClientRole
import edu.iot.phoneangle.data.PhonePose
import edu.iot.phoneangle.data.SyntheticPoseData
import edu.iot.phoneangle.data.Trial
import org.junit.Assert.assertEquals
import org.junit.Test

class StableSplitTest {

    private fun trials(n: Int, pose: PhonePose = PhonePose.SCREEN_UP, baseMs: Long = 1_000L): List<Trial> =
        List(n) { i ->
            SyntheticPoseData.generateTrial(pose, ClientRole.CLIENT_A, seed = i.toLong())
                .copy(startedAtEpochMs = baseMs + i)
        }

    @Test
    fun tenTrials_holdOutThree() {
        val t = trials(10)
        val holdout = StableSplit.holdoutIds(t)
        assertEquals(3, holdout.size)
        assertEquals(setOf(t[1].id, t[5].id, t[9].id), holdout)
    }

    @Test
    fun threeTrials_holdOutOne_oneTrial_holdsNone() {
        assertEquals(1, StableSplit.holdoutIds(trials(3)).size)
        assertEquals(0, StableSplit.holdoutIds(trials(1)).size)
    }

    @Test
    fun appendingLaterTrials_neverChangesEarlierMembership() {
        val first = trials(5)
        val before = StableSplit.holdoutIds(first)
        val after = StableSplit.holdoutIds(first + trials(5, baseMs = 10_000L))
        for (t in first) {
            assertEquals(t.id in before, t.id in after)
        }
    }

    @Test
    fun labels_areStratifiedIndependently() {
        val a = trials(4, PhonePose.SCREEN_UP)
        val b = trials(4, PhonePose.SCREEN_DOWN, baseMs = 50_000L)
        val holdout = StableSplit.holdoutIds(a + b)
        assertEquals(setOf(a[1].id, b[1].id), holdout)
    }

    @Test
    fun trainCount_matchesHoldoutIds() {
        for (n in 0..25) {
            assertEquals(
                "n=$n",
                n - StableSplit.holdoutIds(trials(n)).size,
                StableSplit.trainCount(n),
            )
        }
    }
}
