package edu.iot.phoneangle.ml.typed

import android.content.Context
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.store.LocalDatasetStore

/** Android glue for the extra-credit tap pipeline: counts, train + save, load. */
class TapWorkflow(context: Context) {
    val dataset = LocalDatasetStore(context)
    val models = TapModelStore(context)

    data class Status(val perClass: Map<TypedLabel, Int>) {
        fun count(label: TypedLabel): Int = perClass[label] ?: 0
        val queries: Int get() = TypedLabel.SNIPPED.sumOf { count(it) }
        val ready: Boolean get() = queries >= MIN_QUERIES
    }

    /** Label counts from trial headers only (no sample parsing). */
    fun status(): Status {
        val raw = dataset.countByLabel(TrialTask.TYPED_TAP)
        val per = TypedLabel.entries.associateWith { label ->
            raw.entries.sumOf { (k, n) -> if (TypedLabel.fromCollectLabel(k) == label) n else 0 }
        }
        return Status(per)
    }

    fun train(save: Boolean = true): TapModelBundle {
        val bundle = TapTrainer.train(dataset.loadAll(TrialTask.TYPED_TAP))
        if (save) models.save(bundle)
        return bundle
    }

    companion object {
        /** ~60% go to train, so this gives the trainer its minimum of 10 train queries. */
        const val MIN_QUERIES = 20
    }
}
