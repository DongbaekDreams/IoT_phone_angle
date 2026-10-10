package edu.iot.phoneangle.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import edu.iot.phoneangle.ProjectSettings
import edu.iot.phoneangle.R
import edu.iot.phoneangle.TaskKind
import edu.iot.phoneangle.databinding.FragmentCompareBinding
import edu.iot.phoneangle.databinding.ItemMetricRowBinding
import edu.iot.phoneangle.federation.FederationOps
import edu.iot.phoneangle.federation.ModelPayload
import edu.iot.phoneangle.ml.EvaluationReport
import edu.iot.phoneangle.ml.ModelSlot
import edu.iot.phoneangle.ml.PoseModelBundle
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tab 4 - local baseline vs peer baseline vs global model, all scored on THIS phone's
 * held-out trials. This is the headline result for the project.
 */
class CompareFragment : Fragment(), Refreshable {

    private var _binding: FragmentCompareBinding? = null
    private val binding get() = _binding!!

    private lateinit var ops: FederationOps
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private data class Entry(val slot: ModelSlot, val bundle: PoseModelBundle?, val report: EvaluationReport?)

    private var entries: List<Entry> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentCompareBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ops = FederationOps(requireContext())
        binding.compareToggle.check(R.id.showGlobal)
        binding.compareToggle.addOnButtonCheckedListener { _, _, checked -> if (checked) renderConfusion() }
        binding.pullButton.setOnClickListener { pull() }
        binding.publishButton.setOnClickListener { publish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun refresh() {
        if (_binding == null) return
        val typed = ProjectSettings(requireContext()).taskKind == TaskKind.TYPED_WORD
        binding.typedGateBanner.isVisible = typed
        binding.pullButton.isEnabled = !typed && !busy.get()
        binding.publishButton.isEnabled = !typed && !busy.get()

        entries = listOf(ModelSlot.LOCAL_BASELINE, ModelSlot.PEER_BASELINE, ModelSlot.GLOBAL).map { slot ->
            val bundle = ops.workflow.models.load(slot)
            Entry(slot, bundle, bundle?.let { ops.workflow.evaluateHoldout(it) })
        }
        binding.compareRows.removeAllViews()
        for (e in entries) {
            val row = ItemMetricRowBinding.inflate(layoutInflater, binding.compareRows, false)
            row.metricName.text = nameFor(e)
            val acc = e.report?.accuracy
            row.metricBar.progress = ((acc ?: 0f) * 100).toInt()
            row.metricValue.text = when {
                e.bundle == null -> getString(R.string.compare_missing)
                acc == null -> getString(R.string.compare_no_holdout)
                else -> getString(R.string.compare_value, acc * 100f, e.report?.sampleCount ?: 0)
            }
            binding.compareRows.addView(row.root)
        }
        binding.compareVerdict.text = verdict()
        renderConfusion()
    }

    private fun nameFor(e: Entry): String = when (e.slot) {
        ModelSlot.LOCAL_BASELINE -> getString(R.string.compare_local)
        ModelSlot.PEER_BASELINE -> e.bundle?.sourceClientId?.takeIf { it.isNotBlank() }
            ?.let { getString(R.string.compare_peer_named, it) } ?: getString(R.string.compare_peer)
        else -> e.bundle?.round?.let { getString(R.string.compare_global_round, it) } ?: getString(R.string.compare_global)
    }

    private fun verdict(): String {
        val local = entries.first { it.slot == ModelSlot.LOCAL_BASELINE }.report
        val peer = entries.first { it.slot == ModelSlot.PEER_BASELINE }.report
        val global = entries.first { it.slot == ModelSlot.GLOBAL }.report
        if (local == null && global == null && peer == null) return getString(R.string.compare_verdict_none)
        val parts = mutableListOf<String>()
        if (global != null && local != null) {
            parts += getString(R.string.compare_verdict_global_vs_local, (global.accuracy - local.accuracy) * 100f)
        }
        if (global != null && peer != null) {
            parts += getString(R.string.compare_verdict_global_vs_peer, (global.accuracy - peer.accuracy) * 100f)
        }
        if (parts.isEmpty()) return getString(R.string.compare_verdict_need_more)
        return parts.joinToString("\n")
    }

    private fun renderConfusion() {
        if (_binding == null) return
        val slot = when (binding.compareToggle.checkedButtonId) {
            R.id.showLocal -> ModelSlot.LOCAL_BASELINE
            R.id.showPeer -> ModelSlot.PEER_BASELINE
            else -> ModelSlot.GLOBAL
        }
        val report = entries.firstOrNull { it.slot == slot }?.report
        binding.compareConfusion.isVisible = report != null
        binding.compareLegend.isVisible = report != null
        if (report == null) return
        binding.compareConfusion.setMatrix(report.confusion)
        binding.compareLegend.text = report.classes.withIndex()
            .joinToString("   ") { (i, pose) -> "${i + 1} = ${pose.displayName}" }
    }

    private var peerChoices: List<ModelPayload> = emptyList()

    private fun pull() {
        if (ProjectSettings(requireContext()).taskKind == TaskKind.TYPED_WORD) return
        peerChoices = emptyList()
        runTask(R.string.compare_pulling, afterSuccess = ::choosePeer) {
            val t = ops.transport()
            val global = ops.getGlobal(t)
            peerChoices = ops.peerCandidates(t)
            val globalText = when (global) {
                is FederationOps.GlobalResult.Updated -> getString(R.string.compare_pulled_global, global.bundle.round)
                is FederationOps.GlobalResult.Waiting -> getString(R.string.compare_global_current)
            }
            if (peerChoices.isEmpty()) "$globalText ${getString(R.string.compare_no_peer)}" else globalText
        }
    }

    /** One candidate: use it. Several phones: let the user pick which peer to compare against. */
    private fun choosePeer() {
        if (_binding == null) return
        val choices = peerChoices
        when {
            choices.isEmpty() -> Unit
            choices.size == 1 -> applyPeer(choices.first())
            else -> {
                val labels = choices.map { p ->
                    getString(R.string.compare_peer_choice, p.clientId, p.clientRole.ifBlank { "?" }, p.sampleCount)
                }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.compare_peer_pick_title)
                    .setItems(labels) { _, which -> applyPeer(choices[which]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun applyPeer(peer: ModelPayload) {
        val bundle = ops.usePeer(peer)
        binding.compareStatus.text = getString(R.string.compare_pulled_peer, bundle.sourceClientId)
        refresh()
    }

    private fun publish() {
        if (ProjectSettings(requireContext()).taskKind == TaskKind.TYPED_WORD) return
        runTask(R.string.compare_publishing) {
            val n = ops.publishResults(ops.transport())
            getString(R.string.compare_published, n)
        }
    }

    private fun runTask(progressMessage: Int, afterSuccess: (() -> Unit)? = null, block: () -> String) {
        if (!busy.compareAndSet(false, true)) return
        binding.compareProgress.isVisible = true
        binding.compareStatus.setText(progressMessage)
        binding.pullButton.isEnabled = false
        binding.publishButton.isEnabled = false
        executor.execute {
            val result = runCatching(block)
            activity?.runOnUiThread {
                busy.set(false)
                if (_binding == null) return@runOnUiThread
                binding.compareProgress.isVisible = false
                binding.pullButton.isEnabled = true
                binding.publishButton.isEnabled = true
                binding.compareStatus.text = result.fold(
                    onSuccess = { it },
                    onFailure = { getString(R.string.fed_log_error, it.message ?: it.javaClass.simpleName) },
                )
                (activity as? ShellHost)?.refreshChips()
                refresh()
                if (result.isSuccess) afterSuccess?.invoke()
            }
        }
    }
}
