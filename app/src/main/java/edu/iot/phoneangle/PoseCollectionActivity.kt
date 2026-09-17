package edu.iot.phoneangle

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import edu.iot.phoneangle.collection.TrialRecorder
import edu.iot.phoneangle.data.ClientRole
import edu.iot.phoneangle.data.PhonePose
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.databinding.ActivityPoseCollectionBinding
import edu.iot.phoneangle.sensors.SensorCollector
import edu.iot.phoneangle.store.LocalDatasetStore

/**
 * Section 1 — labeled pose trial recording (local only).
 */
class PoseCollectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPoseCollectionBinding
    private lateinit var projectSettings: ProjectSettings
    private lateinit var store: LocalDatasetStore
    private lateinit var collector: SensorCollector

    private val recorder = TrialRecorder()
    private var countdown: CountDownTimer? = null

    private val recordDurationMs = 2_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPoseCollectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectSettings = ProjectSettings(this)
        store = LocalDatasetStore(this)
        collector = SensorCollector(this) { sample ->
            if (recorder.isRecording) recorder.offer(sample)
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.clientRoleGroup.check(
            when (projectSettings.clientRole) {
                ClientRole.CLIENT_A -> R.id.roleClientA
                ClientRole.CLIENT_B -> R.id.roleClientB
            }
        )
        binding.clientRoleGroup.setOnCheckedChangeListener { _, checkedId ->
            projectSettings.clientRole = when (checkedId) {
                R.id.roleClientB -> ClientRole.CLIENT_B
                else -> ClientRole.CLIENT_A
            }
            refreshCounts()
            refreshPoseHint()
        }

        val poses = PhonePose.entries.map { it.displayName }
        binding.poseSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            poses,
        )
        binding.poseSpinner.setSelection(0)
        binding.poseSpinner.onItemSelectedListener = simpleSelection { refreshPoseHint() }

        binding.recordButton.setOnClickListener { startRecording() }
        binding.clearButton.setOnClickListener {
            store.deleteAll()
            refreshCounts()
            Toast.makeText(this, R.string.pose_cleared, Toast.LENGTH_SHORT).show()
        }

        refreshSensorBanner()
        refreshCounts()
        refreshPoseHint()
    }

    override fun onResume() {
        super.onResume()
        collector.start()
    }

    override fun onPause() {
        countdown?.cancel()
        if (recorder.isRecording) recorder.cancel()
        setRecordingUi(false)
        collector.stop()
        super.onPause()
    }

    private fun startRecording() {
        if (recorder.isRecording) return

        val pose = PhonePose.entries[binding.poseSpinner.selectedItemPosition]
        val role = projectSettings.clientRole
        recorder.start(
            label = pose.shortId,
            task = TrialTask.POSE,
            clientRole = role,
            notes = if (role.isPrimary(pose)) "primary" else "eval_cover",
        )
        setRecordingUi(true)

        countdown = object : CountDownTimer(recordDurationMs, 50) {
            override fun onTick(millisUntilFinished: Long) {
                val progress = ((recordDurationMs - millisUntilFinished) * 100 / recordDurationMs).toInt()
                binding.recordProgress.progress = progress
                binding.statusText.text = getString(
                    R.string.pose_recording_status,
                    pose.displayName,
                    recorder.sampleCount,
                )
            }

            override fun onFinish() {
                val trial = recorder.stop()
                store.save(trial)
                setRecordingUi(false)
                refreshCounts()
                Toast.makeText(
                    this@PoseCollectionActivity,
                    getString(R.string.pose_saved, trial.samples.size),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }.start()
    }

    private fun setRecordingUi(recording: Boolean) {
        binding.recordButton.isEnabled = !recording
        binding.poseSpinner.isEnabled = !recording
        binding.roleClientA.isEnabled = !recording
        binding.roleClientB.isEnabled = !recording
        binding.recordProgress.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        if (!recording) {
            binding.recordProgress.progress = 0
            binding.statusText.text = getString(R.string.pose_idle_status)
        }
    }

    private fun refreshCounts() {
        val role = projectSettings.clientRole
        val counts = store.countByPose()
        val lines = PhonePose.entries.joinToString("\n") { pose ->
            val mark = if (role.isPrimary(pose)) "★" else "·"
            "$mark ${pose.displayName}: ${counts[pose] ?: 0}"
        }
        binding.countsText.text = lines
        binding.totalText.text = getString(
            R.string.pose_total_trials,
            counts.values.sum(),
        )
    }

    private fun refreshPoseHint() {
        val pose = PhonePose.entries[binding.poseSpinner.selectedItemPosition]
        val role = projectSettings.clientRole
        val roleNote = if (role.isPrimary(pose)) {
            getString(R.string.pose_primary_hint)
        } else {
            getString(R.string.pose_eval_hint)
        }
        binding.poseHint.text = "${pose.howToHold}\n\n$roleNote"
    }

    private fun refreshSensorBanner() {
        val a = collector.availableSensors
        binding.sensorBanner.text = getString(
            R.string.pose_sensors_banner,
            yesNo(a.accelerometer),
            yesNo(a.gyroscope),
            yesNo(a.gravity),
            yesNo(a.rotationVector),
        )
    }

    private fun yesNo(available: Boolean) = if (available) "yes" else "no"

    private fun simpleSelection(onSelected: () -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) = onSelected()

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
}
