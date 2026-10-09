package edu.iot.phoneangle.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import edu.iot.phoneangle.ProjectSettings
import edu.iot.phoneangle.R
import edu.iot.phoneangle.collection.TrialRecorder
import edu.iot.phoneangle.data.FloatArray3
import edu.iot.phoneangle.data.PhonePose
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.databinding.FragmentCollectBinding
import edu.iot.phoneangle.databinding.ItemPoseCardBinding
import edu.iot.phoneangle.ml.PoseWorkflow
import edu.iot.phoneangle.sensors.SensorCollector

/**
 * Tab 1 - record labeled 2-second pose holds. Tap a pose card; the card whose pose
 * matches the live gravity reading is highlighted so mislabels are easy to avoid.
 */
class CollectFragment : Fragment(), Refreshable {

    private var _binding: FragmentCollectBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: ProjectSettings
    private lateinit var workflow: PoseWorkflow
    private lateinit var collector: SensorCollector

    private val recorder = TrialRecorder()
    private var countdown: CountDownTimer? = null
    private var latestGravity: FloatArray3? = null
    private val cards = mutableMapOf<PhonePose, ItemPoseCardBinding>()
    private val handler = Handler(Looper.getMainLooper())

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
        collector = SensorCollector(ctx) { sample ->
            latestGravity = sample.gravity ?: sample.accel ?: latestGravity
            if (recorder.isRecording) recorder.offer(sample)
        }

        binding.tagInput.setText(settings.sessionTag)
        binding.tagInput.doAfterTextChanged { settings.sessionTag = it?.toString().orEmpty() }

        buildPoseCards()
    }

    override fun onResume() {
        super.onResume()
        collector.start()
        handler.post(ticker)
        refresh()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        countdown?.cancel()
        if (recorder.isRecording) recorder.cancel()
        showRecording(false)
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
        val role = settings.clientRole
        val status = workflow.status(role)

        if (binding.tagInput.text?.toString() != settings.sessionTag) {
            binding.tagInput.setText(settings.sessionTag)
        }
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
        if (_binding == null) return
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
        if (recorder.isRecording) {
            binding.recordingDetail.text = getString(R.string.collect_recording_samples, recorder.sampleCount)
        }
    }

    private fun onPoseTapped(pose: PhonePose) {
        if (recorder.isRecording) return
        val g = latestGravity
        if (g != null && pose.matchScore(g) < PhonePose.MATCH_THRESHOLD) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.collect_mismatch_title)
                .setMessage(getString(R.string.collect_mismatch_message, pose.displayName))
                .setPositiveButton(R.string.collect_record_anyway) { _, _ -> startRecording(pose) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            startRecording(pose)
        }
    }

    private fun startRecording(pose: PhonePose) {
        val role = settings.clientRole
        recorder.start(
            label = pose.shortId,
            task = TrialTask.POSE,
            clientRole = role,
            notes = if (role.isPrimary(pose)) "primary" else "eval_cover",
            sessionTag = settings.sessionTag,
        )
        showRecording(true)
        binding.recordingTitle.text = getString(R.string.collect_recording_title, pose.displayName)
        countdown = object : CountDownTimer(RECORD_MS, 50) {
            override fun onTick(remaining: Long) {
                binding.recordProgress.progress = ((RECORD_MS - remaining) * 100 / RECORD_MS).toInt()
            }

            override fun onFinish() {
                val trial = recorder.stop()
                workflow.dataset.save(trial)
                showRecording(false)
                refresh()
            }
        }.start()
    }

    private fun showRecording(recording: Boolean) {
        if (_binding == null) return
        binding.recordingCard.isVisible = recording
        if (!recording) binding.recordProgress.progress = 0
        for (card in cards.values) card.root.isEnabled = !recording
    }

    companion object {
        private const val RECORD_MS = 2_000L
    }
}
