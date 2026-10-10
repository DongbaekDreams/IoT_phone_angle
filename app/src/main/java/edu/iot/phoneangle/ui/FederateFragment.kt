package edu.iot.phoneangle.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import edu.iot.phoneangle.R
import edu.iot.phoneangle.TransportMode
import edu.iot.phoneangle.databinding.FragmentFederateBinding
import edu.iot.phoneangle.federation.FederationOps
import edu.iot.phoneangle.federation.FederationTransport
import edu.iot.phoneangle.federation.RemoteStatus
import edu.iot.phoneangle.federation.TransportException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** In-memory activity log that survives switching tabs. */
object ActivityLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(message: String) {
        lines.addFirst("${fmt.format(Date())}  $message")
        while (lines.size > 12) lines.removeLast()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}

/**
 * Sync tab - connection settings + one federated pose round (Git or LAN).
 * Federation covers the pose models only.
 */
class FederateFragment : Fragment(), Refreshable {

    private var _binding: FragmentFederateBinding? = null
    private val binding get() = _binding!!

    private lateinit var ops: FederationOps
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var remoteText: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentFederateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ops = FederationOps(requireContext())

        binding.modeToggle.check(if (ops.settings.transportMode == TransportMode.GIT) R.id.modeGit else R.id.modeLan)
        binding.modeToggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            ops.settings.transportMode = if (id == R.id.modeGit) TransportMode.GIT else TransportMode.LAN
            (activity as? ShellHost)?.refreshChips()
            remoteText = ""
            refresh()
            refreshRemoteStatus()
        }

        binding.serverUrlInput.setText(ops.settings.serverBaseUrl)
        binding.githubRepoInput.setText(ops.settings.githubRepo)
        binding.githubTokenInput.setText(ops.settings.githubToken)

        binding.refreshStatusButton.setOnClickListener { refreshRemoteStatus() }
        binding.settingsButton.setOnClickListener { (activity as? ShellHost)?.openIdentityDialog() }
        binding.getTokenButton.setOnClickListener { openTokenPage() }
        binding.testConnectionButton.setOnClickListener { testConnection() }
        binding.step1Button.setOnClickListener { stepTrain() }
        binding.step2Button.setOnClickListener { stepSend() }
        binding.step3Button.setOnClickListener { stepGetGlobal() }
        binding.aggregateButton.setOnClickListener { stepAggregate() }
    }

    override fun onPause() {
        saveConnectionFields()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshRemoteStatus()
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
        val s = ops.settings
        val git = s.transportMode == TransportMode.GIT
        binding.modeHint.setText(if (git) R.string.fed_mode_git_hint else R.string.fed_mode_lan_hint)
        binding.gitFields.isVisible = git
        binding.serverUrlLayout.isVisible = !git
        val global = ops.localGlobalRound()
        val local = getString(
            R.string.fed_status_local,
            s.displayLabel(),
            if (global == 0) getString(R.string.fed_no_global) else getString(R.string.fed_global_round, global),
        )
        binding.fedStatus.text = if (remoteText.isBlank()) local else "$local\n\n$remoteText"
        binding.fedLog.text = ActivityLog.text().ifBlank { getString(R.string.fed_log_empty) }
        setRoundButtonsEnabled(!busy.get())
    }

    private fun saveConnectionFields() {
        if (_binding == null) return
        ops.settings.serverBaseUrl = binding.serverUrlInput.text?.toString().orEmpty()
        ops.settings.githubRepo = binding.githubRepoInput.text?.toString().orEmpty()
        ops.settings.githubToken = binding.githubTokenInput.text?.toString().orEmpty()
    }

    private fun openTokenPage() {
        val uri = Uri.parse(
            "https://github.com/settings/personal-access-tokens/new",
        )
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun testConnection() {
        saveConnectionFields()
        binding.testConnectionButton.isEnabled = false
        binding.testConnectionResult.setText(R.string.dev_testing)
        executor.execute {
            val message = runCatching { FederationTransport.create(ops.settings).ping() }
                .fold(
                    onSuccess = { getString(R.string.dev_test_ok, it) },
                    onFailure = { getString(R.string.dev_test_failed, it.message ?: it.javaClass.simpleName) },
                )
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                binding.testConnectionButton.isEnabled = true
                binding.testConnectionResult.text = message
                (activity as? ShellHost)?.refreshChips()
                refreshRemoteStatus()
            }
        }
    }

    private fun setRoundButtonsEnabled(enabled: Boolean) {
        binding.step1Button.isEnabled = enabled
        binding.step2Button.isEnabled = enabled
        binding.step3Button.isEnabled = enabled
        binding.aggregateButton.isEnabled = enabled
    }

    private fun refreshRemoteStatus() {
        val noneText = getString(R.string.fed_remote_nobody)
        val template = getString(R.string.fed_status_remote)
        runTask(showProgress = false, onFailure = { remoteText = getString(R.string.fed_remote_unreachable) }) {
            val t = ops.transport()
            val status = t.status()
            val waiting = status.pendingClients.joinToString(", ").ifBlank { noneText }
            remoteText = String.format(
                template, t.label, status.latestGlobalRound, status.pendingRound, waiting,
            )
        }
    }

    private fun percent(v: Float?): String =
        v?.let { getString(R.string.percent_format, it * 100f) } ?: "n/a"

    private fun stepTrain() = runTask {
        val outcome = ops.trainFromGlobal(ops.transport())
        val b = outcome.bundle
        ActivityLog.add(
            getString(
                R.string.fed_log_trained,
                b.round,
                outcome.trainCount,
                percent(b.trainAccuracy),
                percent(b.testAccuracy),
            ),
        )
    }

    private fun stepSend() = runTask {
        ActivityLog.add(ops.sendUpdate(ops.transport()))
    }

    private fun stepGetGlobal() = runTask {
        when (val r = ops.getGlobal(ops.transport())) {
            is FederationOps.GlobalResult.Updated -> ActivityLog.add(
                getString(
                    R.string.fed_log_global,
                    r.bundle.round,
                    r.bundle.contributors.joinToString(", ").ifBlank { "?" },
                    percent(r.accuracy),
                ),
            )
            is FederationOps.GlobalResult.Waiting -> ActivityLog.add(
                getString(R.string.fed_log_waiting, r.status.pendingRound, r.status.pendingClients.size),
            )
        }
    }

    private var statusForAggregate: RemoteStatus? = null

    /** With several phones, aggregating early locks the round, so show who has sent an update first. */
    private fun stepAggregate() = runTask(onSuccess = ::confirmAggregate) {
        statusForAggregate = ops.transport().status()
    }

    private fun confirmAggregate() {
        val s = statusForAggregate ?: return
        if (s.pendingClients.isEmpty()) {
            ActivityLog.add(getString(R.string.fed_log_nothing_to_aggregate, s.pendingRound))
            refresh()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.fed_aggregate_confirm_title, s.pendingRound))
            .setMessage(
                getString(R.string.fed_aggregate_confirm_message, s.pendingClients.size, s.pendingClients.joinToString(", ")),
            )
            .setPositiveButton(R.string.fed_aggregate_confirm_yes) { _, _ -> runAggregate() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runAggregate() = runTask {
        val bundle = ops.aggregate(ops.transport())
        ActivityLog.add(
            getString(
                R.string.fed_log_aggregated,
                bundle.round,
                bundle.contributors.joinToString(", "),
                percent(bundle.testAccuracy),
            ),
        )
    }

    // --- plumbing ----------------------------------------------------------------------

    private fun runTask(
        showProgress: Boolean = true,
        onFailure: (() -> Unit)? = null,
        onSuccess: (() -> Unit)? = null,
        block: () -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) return
        binding.fedProgress.isVisible = showProgress
        setButtonsEnabled(false)
        executor.execute {
            val result = runCatching(block)
            activity?.runOnUiThread {
                busy.set(false)
                if (_binding == null) return@runOnUiThread
                binding.fedProgress.isVisible = false
                setButtonsEnabled(true)
                result.onFailure { e ->
                    onFailure?.invoke()
                    if (onFailure == null || e !is TransportException) {
                        val msg = e.message ?: e.javaClass.simpleName
                        ActivityLog.add(getString(R.string.fed_log_error, msg))
                    }
                }
                (activity as? ShellHost)?.refreshChips()
                refresh()
                if (result.isSuccess) onSuccess?.invoke()
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        setRoundButtonsEnabled(enabled)
        binding.refreshStatusButton.isEnabled = enabled
        binding.testConnectionButton.isEnabled = enabled
    }
}
