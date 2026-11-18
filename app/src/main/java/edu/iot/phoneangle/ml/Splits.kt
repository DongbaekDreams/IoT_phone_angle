package edu.iot.phoneangle.ml

import edu.iot.phoneangle.data.Trial

/**
 * Deterministic per-trial train/test assignment.
 *
 * Unlike a random shuffle, a trial's side of the split never changes as more data is collected
 * (earlier trials keep their index within their label), so a model trained earlier cannot "see"
 * trials that later land in the held-out set. Stratified by label, which guarantees every pose
 * with at least 2 trials has held-out data.
 */
object StableSplit {
    /** Within each label, trials in recording order; every 4th (index 1, 5, 9, ...) is held out. */
    fun holdoutIds(trials: List<Trial>): Set<String> =
        trials.groupBy { it.label }.values.flatMap { group ->
            group.sortedWith(compareBy({ it.startedAtEpochMs }, { it.id }))
                .filterIndexed { i, _ -> i % 4 == 1 }
                .map { it.id }
        }.toSet()

    /** How many of [n] trials of one label land in train. */
    fun trainCount(n: Int): Int = n - (n + 2) / 4
}
