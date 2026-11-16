package edu.iot.phoneangle.ml.typed

import android.content.Context
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.store.LocalDatasetStore

/** Android glue for the extra-credit tap pipeline: counts, train + save, load. */
class TapWorkflow(context: Context) {
    val dataset = LocalDatasetStore(context)
    val models = TapModelStore(context)
    val localOnly = TapModelStore(context, TapModelStore.LOCAL_ONLY_FILE_NAME)

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
        if (save) {
            models.save(bundle)
            localOnly.save(bundle)
        }
        return bundle
    }

    /**
     * Share this phone's own model (weights only), average every phone's (FedAvg, weighted by
     * training taps), keep this phone's scaling / calibration / thresholds, and make that the
     * active model. Blocking — call off the UI thread. Returns a one-line summary.
     */
    fun federate(transport: edu.iot.phoneangle.federation.FederationTransport, clientId: String): String {
        // First sync on a phone trained before federation existed: its active model is still its own.
        val own = localOnly.load() ?: models.load()?.also { localOnly.save(it) }
        if (own != null) transport.putTypedModel(clientId, TypedFederation.shareable(own).toString())
        val all = transport.fetchTypedModels().mapNotNull { runCatching { org.json.JSONObject(it) }.getOrNull() }
        check(all.isNotEmpty()) { "No typed models shared yet — train on a phone first." }
        val global = TypedFederation.localize(TypedFederation.average(all), own)
        models.save(global)
        return "Averaged ${all.size} phone(s), ${global.trainTaps} taps in total" +
            if (own == null) " (no local data: using the shared model as-is)." else " (kept this phone's own calibration)."
    }

    companion object {
        /** ~60% go to train, so this gives the trainer its minimum of 10 train queries. */
        const val MIN_QUERIES = 20
    }
}
