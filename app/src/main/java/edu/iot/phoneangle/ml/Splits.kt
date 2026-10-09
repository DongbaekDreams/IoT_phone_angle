package edu.iot.phoneangle.ml

/**
 * Deterministic per-trial train/test assignment.
 *
 * Unlike a random shuffle, a trial's side of the split never changes as more data is collected,
 * so a model trained earlier cannot "see" trials that later land in the held-out set.
 */
object StableSplit {
    /** ~25% of trials are held out: top 2 bits of the mixed hash are zero. */
    fun isHoldout(trialId: String): Boolean {
        val mixed = trialId.hashCode() * -1640531527 // golden-ratio multiplicative hash
        return (mixed ushr 30) == 0
    }

    fun split(examples: List<LabeledExample>): Pair<List<LabeledExample>, List<LabeledExample>> {
        val (test, train) = examples.partition { isHoldout(it.trialId) }
        return train to test
    }
}
