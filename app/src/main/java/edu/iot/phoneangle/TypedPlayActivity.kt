package edu.iot.phoneangle

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import edu.iot.phoneangle.collection.TrialRecorder
import edu.iot.phoneangle.data.TouchLabel
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.databinding.ActivityTypedPlayBinding
import edu.iot.phoneangle.ml.typed.TypedWordBank
import edu.iot.phoneangle.sensors.SensorCollector
import edu.iot.phoneangle.store.LocalDatasetStore
import edu.iot.phoneangle.ui.TapKeyboardView
import kotlin.random.Random

/**
 * Search-bar collection round: prompt, fake search bar, in-app training keyboard.
 *
 * Records 200 Hz IMU plus every key press (position + time) as ground-truth labels. The query
 * is saved when the user taps search; its label is what was actually typed, so typos are
 * still correctly labeled data rather than thrown away.
 */
class TypedPlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTypedPlayBinding
    private lateinit var settings: ProjectSettings
    private lateinit var dataset: LocalDatasetStore
    private lateinit var collector: SensorCollector
    private lateinit var sensorThread: HandlerThread

    private val recorder = TrialRecorder()
    private val handler = Handler(Looper.getMainLooper())
    private val rng = Random.Default
    private val typed = StringBuilder()

    private var prompt: TypedWordBank.Prompt = TypedWordBank.next(rng)
    private var completing = false
    private var savedThisSession = 0
    private var gripIndex = 0

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
        sensorThread = HandlerThread("typed-play-sensors").also { it.start() }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        gripIndex = prefs.getInt(KEY_GRIP, 0).coerceIn(0, GRIPS.lastIndex)
        binding.gripButton.setOnClickListener {
            gripIndex = (gripIndex + 1) % GRIPS.size
            prefs.edit().putInt(KEY_GRIP, gripIndex).apply()
            showGrip()
            restartRecording()
        }
        showGrip()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.keyboard.onKey = ::onKey
        showWord()
    }

    override fun onResume() {
        super.onResume()
        collector.start(SensorCollector.TAP_SAMPLING_US, Handler(sensorThread.looper))
        if (!completing && !recorder.isRecording) startRecording()
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        if (recorder.isRecording) recorder.cancel()
        completing = false
        collector.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sensorThread.quitSafely()
        super.onDestroy()
    }

    private fun showGrip() {
        binding.gripButton.text = getString(GRIPS[gripIndex].second)
    }

    private fun showWord() {
        typed.clear()
        binding.promptWord.text = prompt.word
        binding.feedback.text = ""
        binding.searchBar.text = ""
        binding.sessionCount.text = getString(R.string.typed_play_session_count, savedThisSession)
    }

    private fun startRecording() {
        if (recorder.isRecording) return
        recorder.start(
            label = prompt.label,
            task = TrialTask.TYPED_TAP,
            clientRole = settings.clientRole,
            notes = "typed_play_v2",
            sessionTag = GRIPS[gripIndex].first,
        )
    }

    private fun restartRecording() {
        if (completing) return
        if (recorder.isRecording) recorder.cancel()
        showWord()
        startRecording()
    }

    private fun onKey(touch: TouchLabel) {
        if (completing) return
        if (!recorder.isRecording) startRecording()
        recorder.offerTouch(touch)
        when (touch.key) {
            TouchLabel.KEY_BACKSPACE -> if (typed.isNotEmpty()) typed.setLength(typed.length - 1)
            TouchLabel.KEY_SPACE -> typed.append(' ')
            TouchLabel.KEY_ENTER -> {
                submit()
                return
            }
            TapKeyboardView.SHIFT, TapKeyboardView.SYMBOLS -> Unit
            else -> typed.append(touch.key)
        }
        binding.searchBar.text = typed
    }

    private fun submit() {
        val text = typed.toString().trim().lowercase()
        if (text.isEmpty()) return
        completing = true
        val exact = TypedWordBank.matches(text, prompt.word)
        binding.feedback.setText(if (exact) R.string.typed_matched_pause else R.string.typed_play_saved_as_typed)
        // Keep recording briefly so the search tap's motion window (150 ms) is complete.
        handler.postDelayed({
            if (recorder.isRecording) {
                val trial = recorder.stop()
                dataset.save(trial.copy(label = text, notes = if (exact) trial.notes else "${trial.notes};prompt=${prompt.word}"))
                savedThisSession++
            }
            handler.postDelayed(advanceRunnable, NEXT_PAUSE_MS)
        }, TAIL_MS)
    }

    private fun nextWord() {
        completing = false
        prompt = TypedWordBank.next(rng)
        showWord()
        startRecording()
    }

    companion object {
        private const val TAIL_MS = 400L
        private const val NEXT_PAUSE_MS = 350L
        private const val PREFS = "typed_play"
        private const val KEY_GRIP = "grip"

        /** Grip currently selected in Play (session tag id), also used to tag live tuning searches. */
        fun currentGrip(context: Context): String {
            val i = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_GRIP, 0)
            return GRIPS[i.coerceIn(0, GRIPS.lastIndex)].first
        }

        /** Session tag id → button label. Used for cross-grip evaluation. */
        val GRIPS = listOf(
            "two_thumbs" to R.string.typed_grip_two_thumbs,
            "one_thumb" to R.string.typed_grip_one_thumb,
            "on_table" to R.string.typed_grip_on_table,
        )
    }
}
