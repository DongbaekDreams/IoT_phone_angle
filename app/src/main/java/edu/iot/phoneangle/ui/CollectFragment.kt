package edu.iot.phoneangle.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.GridLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import edu.iot.phoneangle.ProjectSettings
import edu.iot.phoneangle.R
import edu.iot.phoneangle.TaskKind
import edu.iot.phoneangle.collection.TrialRecorder
import edu.iot.phoneangle.data.FloatArray3
import edu.iot.phoneangle.data.PhonePose
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.databinding.FragmentCollectBinding
import edu.iot.phoneangle.databinding.ItemPoseCardBinding
import edu.iot.phoneangle.ml.PoseWorkflow
import edu.iot.phoneangle.sensors.SensorCollector
import edu.iot.phoneangle.store.LocalDatasetStore

/**
 * Collect tab — pose holds (main path) or typed-word trials (preview; no training yet).
 */
class CollectFragment : Fragment(), Refreshable {

    private var _binding: FragmentCollectBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: ProjectSettings
    private lateinit var workflow: PoseWorkflow
    private lateinit var dataset: LocalDatasetStore
    private lateinit var collector: SensorCollector

    private val recorder = TrialRecorder()
    private var countdown: CountDownTimer? = null
    private var phase: Phase = Phase.Idle
    private var pendingPose: PhonePose? = null
    private var latestGravity: FloatArray3? = null
    private val cards = mutableMapOf<PhonePose, ItemPoseCardBinding>()
    private val handler = Handler(Looper.getMainLooper())

    private enum class Phase { Idle, GetReady, Recording, TypedRecording }

    private val ticker = object : Runnable {
        override fun run() {
            updateMatchHighlights()
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentCollectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        settings = ProjectSettings(ctx)
        workflow = PoseWorkflow(ctx)
        dataset = LocalDatasetStore(ctx)
        collector = SensorCollector(ctx) { sample ->
            latestGravity = sample.gravity ?: sample.accel ?: latestGravity
            if (recorder.isRecording) recorder.offer(sample)
        }

        binding.tagInput.setText(settings.sessionTag)
        binding.tagInput.doAfterTextChanged { settings.sessionTag = it?.toString().orEmpty() }
        binding.cancelRecordButton.setOnClickListener { cancelCapture() }

        binding.taskToggle.check(
            if (settings.taskKind == TaskKind.TYPED_WORD) R.id.taskTyped else R.id.taskPose,
        )
        binding.taskToggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            if (phase != Phase.Idle) {
                binding.taskToggle.check(
                    if (settings.taskKind == TaskKind.TYPED_WORD) R.id.taskTyped else R.id.taskPose,
                )
                return@addOnButtonCheckedListener
            }
            settings.taskKind = if (id == R.id.taskTyped) TaskKind.TYPED_WORD else TaskKind.POSE
            applyTaskUi()
            refresh()
        }

        binding.targetSpinner.adapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_dropdown_item,
            TYPED_TARGETS,
        )
        binding.typedStartButton.setOnClickListener { startTypedTrial() }
        binding.typedStopButton.setOnClickListener { stopTypedTrial(save = true) }
        binding.typedCancelButton.setOnClickListener { stopTypedTrial(save = false) }

        buildPoseCards()
        applyTaskUi()
        setTypedRecordingUi(false)
    }

    override fun onResume() {
        super.onResume()
        collector.start()
        handler.post(ticker)
        refresh()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        cancelCapture()
        if (phase == Phase.TypedRecording) stopTypedTrial(save = false)
        collector.stop()
        super.onPause()
    }

    override fun onDestroyView() {
        cards.clear()
        _binding = null
        super.onDestroyView()
    }

    override fun refresh() {
        if (_binding == null) return
        val wanted = if (settings.taskKind == TaskKind.TYPED_WORD) R.id.taskTyped else R.id.taskPose
        if (binding.taskToggle.checkedButtonId != wanted) {
            binding.taskToggle.check(wanted)
        }
        applyTaskUi()

        if (binding.tagInput.text?.toString() != settings.sessionTag) {
            binding.tagInput.setText(settings.sessionTag)
        }

        if (settings.taskKind == TaskKind.TYPED_WORD) {
            refreshTyped()
        } else {
            refreshPose()
        }
    }

    private fun applyTaskUi() {
        val typed = settings.taskKind == TaskKind.TYPED_WORD
        binding.typedPreviewBanner.isVisible = typed
        binding.poseSection.isVisible = !typed
        binding.typedSection.isVisible = typed
    }

    private fun refreshPose() {
        val role = settings.clientRole
        val status = workflow.status(role)
        binding.collectStatus.text = getString(R.string.collect_status, settings.displayLabel(), status.totalTrials)
        binding.collectGuidance.text = if (status.needed.isEmpty()) {
            getString(R.string.collect_guidance_done)
        } else {
            getString(
                R.string.collect_guidance_need,
                status.needed.joinToString(", ") { (pose, n) -> "${pose.displayName} x$n" },
            )
        }
        for (pose in PhonePose.entries) {
            val card = cards[pose] ?: continue
            val primary = role.isPrimary(pose)
            val n = status.perPose[pose] ?: 0
            card.poseCount.text = getString(
                if (primary) R.string.collect_count_primary else R.string.collect_count_cover,
                n,
            )
        }
    }

    private fun refreshTyped() {
        val trials = dataset.loadAll(TrialTask.TYPED_WORD)
        val byLabel = trials.groupingBy { it.label }.eachCount()
        binding.collectStatus.text = getString(
            R.string.collect_status,
            settings.displayLabel(),
            trials.size,
        )
        binding.collectGuidance.text = getString(R.string.typed_blurb)
        binding.typedCounts.text = if (byLabel.isEmpty()) {
            getString(R.string.typed_counts_empty)
        } else {
            byLabel.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
        if (phase != Phase.TypedRecording) {
            binding.typedStatus.text = getString(R.string.typed_idle)
        }
    }

    private fun buildPoseCards() {
        binding.poseGrid.removeAllViews()
        cards.clear()
        val margin = (6 * resources.displayMetrics.density).toInt()
        for (pose in PhonePose.entries) {
            val card = ItemPoseCardBinding.inflate(layoutInflater, binding.poseGrid, false)
            card.poseTitle.text = pose.displayName
            card.poseHow.text = pose.howToHold
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(margin, margin, margin, margin)
            }
            card.root.layoutParams = lp
            card.root.setOnClickListener { onPoseTapped(pose) }
            binding.poseGrid.addView(card.root)
            cards[pose] = card
        }
    }

    private fun updateMatchHighlights() {
        if (_binding == null || phase != Phase.Idle || settings.taskKind != TaskKind.POSE) return
        val g = latestGravity
        val accent = ContextCompat.getColor(requireContext(), R.color.accent)
        val line = ContextCompat.getColor(requireContext(), R.color.line)
        val density = resources.displayMetrics.density
        for ((pose, card) in cards) {
            val matches = g != null && pose.matchScore(g) >= PhonePose.MATCH_THRESHOLD
            card.root.strokeColor = if (matches) accent else line
            card.root.strokeWidth = ((if (matches) 2 else 1) * density).toInt()
            card.poseMatch.text = if (matches) getString(R.string.collect_matches_now) else ""
        }
    }

    private fun onPoseTapped(pose: PhonePose) {
        if (phase != Phase.Idle || settings.taskKind != TaskKind.POSE) return
        if (pose.needsGetReady) startGetReady(pose) else maybeWarnThenRecord(pose)
    }

    private fun startGetReady(pose: PhonePose) {
        pendingPose = pose
        phase = Phase.GetReady
        setBusyUi(true)
        binding.recordingCard.isVisible = true
        binding.recordingTitle.text = getString(R.string.collect_ready_title, pose.displayName)
        binding.cancelRecordButton.isVisible = true
        countdown = object : CountDownTimer(GET_READY_MS, 200) {
            override fun onTick(remaining: Long) {
                if (_binding == null) return
                val secs = ((remaining + 999) / 1000).toInt().coerceAtLeast(1)
                binding.recordingDetail.text = getString(R.string.collect_ready_detail, secs)
                binding.recordProgress.progress = ((GET_READY_MS - remaining) * 100 / GET_READY_MS).toInt()
            }

            override fun onFinish() {
                val target = pendingPose ?: return cancelCapture()
                maybeWarnThenRecord(target)
            }
        }.start()
    }

    private fun maybeWarnThenRecord(pose: PhonePose) {
        val g = latestGravity
        if (g != null && pose.matchScore(g) < PhonePose.MATCH_THRESHOLD) {
            phase = Phase.Idle
            setBusyUi(false)
            binding.recordingCard.isVisible = false
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.collect_mismatch_title)
                .setMessage(getString(R.string.collect_mismatch_after_ready, pose.displayName))
                .setPositiveButton(R.string.collect_record_anyway) { _, _ -> beginRecording(pose) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            beginRecording(pose)
        }
    }

    private fun beginRecording(pose: PhonePose) {
        val role = settings.clientRole
        phase = Phase.Recording
        pendingPose = pose
        setBusyUi(true)
        binding.recordingCard.isVisible = true
        binding.cancelRecordButton.isVisible = true
        recorder.start(
            label = pose.shortId,
            task = TrialTask.POSE,
            clientRole = role,
            notes = if (role.isPrimary(pose)) "primary" else "eval_cover",
            sessionTag = settings.sessionTag,
        )
        binding.recordingTitle.text = getString(R.string.collect_recording_title, pose.displayName)
        countdown = object : CountDownTimer(RECORD_MS, 50) {
            override fun onTick(remaining: Long) {
                if (_binding == null) return
                binding.recordProgress.progress = ((RECORD_MS - remaining) * 100 / RECORD_MS).toInt()
                binding.recordingDetail.text = getString(R.string.collect_recording_samples, recorder.sampleCount)
            }

            override fun onFinish() {
                val trial = recorder.stop()
                workflow.dataset.save(trial)
                finishCapture()
                refresh()
            }
        }.start()
    }

    private fun cancelCapture() {
        countdown?.cancel()
        countdown = null
        if (recorder.isRecording && phase != Phase.TypedRecording) recorder.cancel()
        finishCapture()
    }

    private fun finishCapture() {
        countdown?.cancel()
        countdown = null
        pendingPose = null
        if (phase != Phase.TypedRecording) phase = Phase.Idle
        if (_binding == null) return
        binding.recordingCard.isVisible = false
        binding.recordProgress.progress = 0
        setBusyUi(false)
    }

    private fun setBusyUi(busy: Boolean) {
        if (_binding == null) return
        for (card in cards.values) card.root.isEnabled = !busy
        binding.tagInput.isEnabled = !busy
        binding.taskToggle.isEnabled = !busy
    }

    private fun startTypedTrial() {
        if (phase != Phase.Idle || settings.taskKind != TaskKind.TYPED_WORD) return
        val label = TYPED_TARGETS[binding.targetSpinner.selectedItemPosition]
        phase = Phase.TypedRecording
        recorder.start(
            label = label,
            task = TrialTask.TYPED_WORD,
            clientRole = settings.clientRole,
            notes = "typed_word_shell",
            sessionTag = settings.sessionTag,
        )
        setTypedRecordingUi(true)
        binding.typedStatus.text = getString(R.string.typed_recording, label)
        countdown = object : CountDownTimer(TYPED_AUTO_STOP_MS, 200L) {
            override fun onTick(ms: Long) {
                if (_binding == null) return
                binding.typedStatus.text = getString(
                    R.string.typed_recording_count,
                    label,
                    recorder.sampleCount,
                    ms / 1000,
                )
            }

            override fun onFinish() {
                stopTypedTrial(save = true)
            }
        }.start()
    }

    private fun stopTypedTrial(save: Boolean) {
        countdown?.cancel()
        countdown = null
        if (phase != Phase.TypedRecording && !recorder.isRecording) {
            setTypedRecordingUi(false)
            phase = Phase.Idle
            return
        }
        if (!save) {
            if (recorder.isRecording) recorder.cancel()
            phase = Phase.Idle
            setTypedRecordingUi(false)
            binding.typedStatus.text = getString(R.string.typed_cancelled)
            return
        }
        if (!recorder.isRecording) {
            phase = Phase.Idle
            setTypedRecordingUi(false)
            return
        }
        val trial = recorder.stop()
        dataset.save(trial)
        phase = Phase.Idle
        setTypedRecordingUi(false)
        refreshTyped()
        Toast.makeText(requireContext(), getString(R.string.typed_saved, trial.samples.size), Toast.LENGTH_SHORT).show()
        binding.typedStatus.text = getString(R.string.typed_idle)
    }

    private fun setTypedRecordingUi(recording: Boolean) {
        if (_binding == null) return
        binding.typedStartButton.isEnabled = !recording
        binding.typedStopButton.isEnabled = recording
        binding.typedCancelButton.isEnabled = recording
        binding.targetSpinner.isEnabled = !recording
        binding.typedHint.isVisible = recording
        binding.tagInput.isEnabled = !recording
        binding.taskToggle.isEnabled = !recording
    }

    companion object {
        private const val GET_READY_MS = 4_000L
        private const val RECORD_MS = 2_000L
        private const val TYPED_AUTO_STOP_MS = 12_000L

        val TYPED_TARGETS = listOf(
            "google.com",
            "youtube.com",
            "wikipedia.org",
            "example.com",
            "other",
        )
    }
}
