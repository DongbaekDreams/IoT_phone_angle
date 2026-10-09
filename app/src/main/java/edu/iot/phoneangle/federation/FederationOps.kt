package edu.iot.phoneangle.federation

import android.content.Context
import edu.iot.phoneangle.ProjectSettings
import edu.iot.phoneangle.ml.EvaluationReport
import edu.iot.phoneangle.ml.ModelSlot
import edu.iot.phoneangle.ml.PoseModelBundle
import edu.iot.phoneangle.ml.PoseWorkflow
import edu.iot.phoneangle.ml.SensorConfig

/**
 * The federation round, step by step. Everything here is blocking; call from a background thread.
 *
 *  1. [trainFromGlobal]   pull newest global (if any), warm-start, train on local data
 *  2. [sendUpdate]        upload the round-local weights (+ this phone's baseline)
 *  3. [getGlobal]         pull the aggregated model, or [aggregate] it first if every update is in
 */
class FederationOps(context: Context) {

    val settings = ProjectSettings(context)
    val workflow = PoseWorkflow(context)
    private val models get() = workflow.models

    fun transport(): FederationTransport = FederationTransport.create(settings)

    /** Local view of the federation, for display. */
    fun localGlobalRound(): Int = models.load(ModelSlot.GLOBAL)?.round ?: 0

    // --- step 1 ------------------------------------------------------------------------

    fun trainFromGlobal(t: FederationTransport): PoseWorkflow.TrainOutcome {
        val status = t.status()
        syncGlobalIfNewer(t, status)
        val global = models.load(ModelSlot.GLOBAL)
        return workflow.trainRoundLocal(global, SensorConfig.ALL)
    }

    // --- step 2 ------------------------------------------------------------------------

    /** @return a one-line summary of what was sent. */
    fun sendUpdate(t: FederationTransport): String {
        val local = models.load(ModelSlot.ROUND_LOCAL)
            ?: throw TransportException("Nothing to send yet. Run step 1 first.")
        val status = t.status()
        if (local.round != status.pendingRound) {
            throw TransportException(
                "Your update is for round ${local.round}, but the federation is waiting for round " +
                    "${status.pendingRound}. Run step 1 again.",
            )
        }
        t.submitUpdate(toPayload(PayloadType.UPDATE, local))
        val baselineNote = models.load(ModelSlot.LOCAL_BASELINE)?.let { baseline ->
            t.putBaseline(toPayload(PayloadType.BASELINE, baseline))
            " Baseline shared too."
        } ?: " (No local baseline yet - train one in the Model tab to compare later.)"
        return "Sent round ${local.round} update (${local.trainSampleCount} trials).$baselineNote"
    }

    // --- step 3 ------------------------------------------------------------------------

    sealed interface GlobalResult {
        data class Updated(val bundle: PoseModelBundle, val accuracy: Float?) : GlobalResult
        data class Waiting(val status: RemoteStatus) : GlobalResult
    }

    fun getGlobal(t: FederationTransport): GlobalResult {
        val status = t.status()
        val bundle = syncGlobalIfNewer(t, status)
        return if (bundle != null) {
            GlobalResult.Updated(bundle, bundle.testAccuracy)
        } else {
            GlobalResult.Waiting(status)
        }
    }

    /** Aggregate the pending round (any client may do this) and store the result as the global model. */
    fun aggregate(t: FederationTransport): PoseModelBundle {
        val payload = t.aggregate()
        return saveGlobal(payload)
    }

    // --- sharing for the Compare tab ---------------------------------------------------

    /**
     * Other clients' baselines, best comparison first: the opposite role (different data
     * distribution) before the same role, newest first within each group.
     */
    fun peerCandidates(t: FederationTransport): List<ModelPayload> =
        t.fetchBaselines()
            .filter { it.clientId != settings.clientId }
            .sortedWith(
                compareByDescending<ModelPayload> { it.clientRole != settings.clientRole.name }
                    .thenByDescending { it.createdAtEpochMs },
            )

    /** Make [peer] the baseline shown in the Compare tab. */
    fun usePeer(peer: ModelPayload): PoseModelBundle {
        val bundle = fromPayload(peer).copy(sourceClientId = peer.clientId)
        models.save(ModelSlot.PEER_BASELINE, bundle)
        return bundle
    }

    /** Evaluate every saved model on this phone's held-out trials and upload only the metrics. */
    fun publishResults(t: FederationTransport): Int {
        val tags = workflow.dataset.sessionTags()
        var count = 0
        val kinds = listOf(
            ModelSlot.LOCAL_BASELINE to "local_baseline",
            ModelSlot.GLOBAL to "global",
            ModelSlot.PEER_BASELINE to "peer_baseline",
        )
        for ((slot, kind) in kinds) {
            val bundle = models.load(slot) ?: continue
            val report = workflow.evaluateHoldout(bundle) ?: continue
            val label = if (slot == ModelSlot.PEER_BASELINE && bundle.sourceClientId.isNotBlank()) {
                "$kind-${bundle.sourceClientId}"
            } else {
                kind
            }
            t.putResult(resultPayload(label, bundle, report, tags))
            count++
        }
        return count
    }

    // --- conversions -------------------------------------------------------------------

    private fun syncGlobalIfNewer(t: FederationTransport, status: RemoteStatus): PoseModelBundle? {
        if (status.latestGlobalRound <= localGlobalRound()) return null
        val payload = t.fetchGlobal() ?: return null
        return saveGlobal(payload)
    }

    private fun saveGlobal(payload: ModelPayload): PoseModelBundle {
        val base = fromPayload(payload)
        val bundle = base.copy(testAccuracy = workflow.evaluateHoldout(base)?.accuracy)
        models.save(ModelSlot.GLOBAL, bundle)
        return bundle
    }

    private fun poseCounts(): Map<String, Int> =
        workflow.dataset.countByPose().mapKeys { it.key.shortId }

    fun toPayload(type: PayloadType, bundle: PoseModelBundle): ModelPayload = ModelPayload(
        type = type,
        clientId = settings.clientId,
        clientRole = settings.clientRole.name,
        round = bundle.round,
        sampleCount = bundle.trainSampleCount.coerceAtLeast(1),
        poseCounts = poseCounts(),
        sensorConfig = bundle.sensorConfig,
        model = bundle.model,
        testAccuracy = bundle.testAccuracy,
    )

    private fun fromPayload(p: ModelPayload) = PoseModelBundle(
        sensorConfig = p.sensorConfig,
        model = p.model,
        trainedAtEpochMs = p.createdAtEpochMs,
        trainAccuracy = 0f,
        trainSampleCount = p.sampleCount,
        testAccuracy = p.testAccuracy,
        round = p.round,
        sourceClientId = p.clientId,
        contributors = p.contributors,
    )

    private fun resultPayload(
        kind: String,
        bundle: PoseModelBundle,
        report: EvaluationReport,
        tags: List<String>,
    ) = ResultPayload(
        clientId = settings.clientId,
        clientRole = settings.clientRole.name,
        modelKind = kind,
        round = bundle.round,
        sensorConfig = bundle.sensorConfig,
        evalCount = report.sampleCount,
        accuracy = report.accuracy,
        macroF1 = report.macroF1,
        perClass = report.perClass.map { ClassScore(it.pose.shortId, it.accuracy, it.f1, it.support) },
        classes = report.classes.map { it.shortId },
        confusion = report.confusion,
        sessionTags = tags,
    )
}
