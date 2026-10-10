package edu.iot.phoneangle

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import edu.iot.phoneangle.collection.TrialRecorder
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.databinding.ActivityTypedPlayBinding
import edu.iot.phoneangle.ml.typed.TypedWordBank
import edu.iot.phoneangle.sensors.SensorCollector
import edu.iot.phoneangle.store.LocalDatasetStore
import kotlin.random.Random

/**
 * Full-screen typing round: back, target word, keyboard field. Nothing else.
 */
class TypedPlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTypedPlayBinding
    private lateinit var settings: ProjectSettings
    private lateinit var dataset: LocalDatasetStore
    private lateinit var collector: SensorCollector

    private val recorder = TrialRecorder()
    private val handler = Handler(Looper.getMainLooper())
    private val rng = Random.Default

    private var prompt: TypedWordBank.Prompt = TypedWordBank.next(rng)
    private var completing = false

    private val advanceRunnable = Runnable { nextWord() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTypedPlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = ProjectSettings(this)
        dataset = LocalDatasetStore(this)
        collector = SensorCollector(this) { sample ->
            if (recorder.isRecording) recorder.offer(sample)
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.typoButton.setOnClickListener { onTypo() }
        binding.typedInput.doAfterTextChanged { onTextChanged() }

        showWord()
        startRecording()
        binding.root.post { focusKeyboard() }
    }

    override fun onResume() {
        super.onResume()
        collector.start()
        if (!completing && !recorder.isRecording) startRecording()
        focusKeyboard()
    }

    override fun onPause() {
        handler.removeCallbacks(advanceRunnable)
        if (recorder.isRecording) recorder.cancel()
        completing = false
        collector.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(advanceRunnable)
        super.onDestroy()
    }

    private fun showWord() {
        binding.promptWord.text = prompt.word
        binding.feedback.text = ""
        binding.typedInput.setText("")
        binding.typedInput.isEnabled = true
    }

    private fun startRecording() {
        if (recorder.isRecording) return
        recorder.start(
            label = prompt.label,
            task = TrialTask.TYPED_WORD,
            clientRole = settings.clientRole,
            notes = "typed_play",
        )
    }

    private fun onTextChanged() {
        if (completing) return
        if (!TypedWordBank.matches(binding.typedInput.text ?: "", prompt.word)) return
        completing = true
        binding.typedInput.isEnabled = false
        binding.feedback.setText(R.string.typed_matched_pause)
        if (recorder.isRecording) {
            dataset.save(recorder.stop())
        }
        handler.removeCallbacks(advanceRunnable)
        handler.postDelayed(advanceRunnable, MATCH_PAUSE_MS)
    }

    private fun onTypo() {
        if (completing) return
        completing = true
        binding.typedInput.isEnabled = false
        handler.removeCallbacks(advanceRunnable)
        // Keep the trial: typo / correction motion is useful for live training.
        if (recorder.isRecording) {
            val trial = recorder.stop()
            dataset.save(trial.copy(notes = "typed_play_typo"))
        }
        binding.feedback.setText(R.string.typed_typo_saved)
        handler.postDelayed(advanceRunnable, MATCH_PAUSE_MS)
    }

    private fun nextWord() {
        completing = false
        prompt = TypedWordBank.next(rng)
        showWord()
        startRecording()
        focusKeyboard()
    }

    private fun focusKeyboard() {
        binding.typedInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.typedInput, InputMethodManager.SHOW_IMPLICIT)
    }

    companion object {
        private const val MATCH_PAUSE_MS = 300L
    }
}
