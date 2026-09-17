package edu.iot.phoneangle

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import edu.iot.phoneangle.collection.TrialRecorder
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.databinding.ActivityTypedWordBinding
import edu.iot.phoneangle.sensors.SensorCollector
import edu.iot.phoneangle.store.LocalDatasetStore

/**
 * Section 4 shell — labeled typing trials (extra credit). Train later.
 */
class TypedWordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTypedWordBinding
    private lateinit var store: LocalDatasetStore
    private lateinit var settings: ProjectSettings
    private lateinit var collector: SensorCollector
    private val recorder = TrialRecorder()
    private var countdown: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTypedWordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = LocalDatasetStore(this)
        settings = ProjectSettings(this)
        collector = SensorCollector(this) { sample ->
            if (recorder.isRecording) recorder.offer(sample)
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.targetSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            TARGETS,
        )

        binding.startButton.setOnClickListener { startTrial() }
        binding.stopButton.setOnClickListener { stopTrial(save = true) }
        binding.cancelButton.setOnClickListener { stopTrial(save = false) }

        refreshCounts()
        setRecordingUi(false)
    }

    override fun onResume() {
        super.onResume()
        collector.start()
    }

    override fun onPause() {
        countdown?.cancel()
        if (recorder.isRecording) recorder.cancel()
        collector.stop()
        super.onPause()
    }

    private fun startTrial() {
        if (recorder.isRecording) return
        val label = TARGETS[binding.targetSpinner.selectedItemPosition]
        recorder.start(
            label = label,
            task = TrialTask.TYPED_WORD,
            clientRole = settings.clientRole,
            notes = "typed_word_shell",
        )
        setRecordingUi(true)
        binding.statusText.text = getString(R.string.typed_recording, label)

        // Soft 12s auto-stop so trials don't run forever if user forgets Stop.
        countdown = object : CountDownTimer(12_000L, 200L) {
            override fun onTick(ms: Long) {
                binding.statusText.text = getString(
                    R.string.typed_recording_count,
                    label,
                    recorder.sampleCount,
                    ms / 1000,
                )
            }

            override fun onFinish() {
                stopTrial(save = true)
            }
        }.start()
    }

    private fun stopTrial(save: Boolean) {
        countdown?.cancel()
        countdown = null
        if (!recorder.isRecording) {
            setRecordingUi(false)
            return
        }
        if (!save) {
            recorder.cancel()
            setRecordingUi(false)
            binding.statusText.text = getString(R.string.typed_cancelled)
            return
        }
        val trial = recorder.stop()
        store.save(trial)
        setRecordingUi(false)
        refreshCounts()
        Toast.makeText(this, getString(R.string.typed_saved, trial.samples.size), Toast.LENGTH_SHORT).show()
        binding.statusText.text = getString(R.string.typed_idle)
    }

    private fun setRecordingUi(recording: Boolean) {
        binding.startButton.isEnabled = !recording
        binding.stopButton.isEnabled = recording
        binding.cancelButton.isEnabled = recording
        binding.targetSpinner.isEnabled = !recording
        binding.hintText.visibility = if (recording) View.VISIBLE else View.GONE
    }

    private fun refreshCounts() {
        val trials = store.loadAll(TrialTask.TYPED_WORD)
        val byLabel = trials.groupingBy { it.label }.eachCount()
        binding.countsText.text = if (byLabel.isEmpty()) {
            getString(R.string.typed_counts_empty)
        } else {
            byLabel.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
    }

    companion object {
        val TARGETS = listOf(
            "google.com",
            "youtube.com",
            "wikipedia.org",
            "example.com",
            "other",
        )
    }
}
