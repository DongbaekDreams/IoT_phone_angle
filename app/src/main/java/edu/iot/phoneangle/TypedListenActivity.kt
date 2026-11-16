package edu.iot.phoneangle

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import edu.iot.phoneangle.collection.TrialRecorder
import edu.iot.phoneangle.data.TouchLabel
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.databinding.ActivityTypedListenBinding
import edu.iot.phoneangle.databinding.ItemMetricRowBinding
import edu.iot.phoneangle.ml.typed.KeyboardLayout
import edu.iot.phoneangle.ml.typed.QueryListener
import edu.iot.phoneangle.ml.typed.TapModelStore
import edu.iot.phoneangle.ml.typed.TapTrainer
import edu.iot.phoneangle.ml.typed.TypedLabel
import edu.iot.phoneangle.ml.typed.TypedWordBank
import edu.iot.phoneangle.ml.typed.WordSpotter
import edu.iot.phoneangle.sensors.SensorCollector
import edu.iot.phoneangle.store.LocalDatasetStore
import java.util.concurrent.Executors

/**
 * Live demo dressed as a pretend search engine. You type on the normal system keyboard; the
 * parental filter only sees IMU taps. While you type, the "What the filter thinks" card shows
 * each detected tap and the current google / yahoo / other leaning. When the query ends (typing
 * pause) a blocked stand-in shows a "Search blocked" card; anything else gets pretend results.
 *
 * The search box text is display-only. It is never model input; it is saved (with the motion)
 * only when the user turns on "Save my searches for tuning".
 */
class TypedListenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTypedListenBinding
    private lateinit var collector: SensorCollector
    private lateinit var dataset: LocalDatasetStore
    private lateinit var settings: ProjectSettings
    private val sensorThread = HandlerThread("typed-listen-sensors").also { it.start() }
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()

    @Volatile
    private var listener: QueryListener? = null
    private var threshold = WordSpotter.DEFAULT_THRESHOLD
    private val guessRows = linkedMapOf<TypedLabel, ItemMetricRowBinding>()

    /** Text submitted with the search key, waiting for the filter's end-of-query verdict. */
    private var pendingSearch: String? = null
    private var lastFlag: TypedLabel? = null
    private val showPendingResults = Runnable {
        pendingSearch?.let { if (lastFlag == null) showResults(it) }
        pendingSearch = null
    }

    // Opt-in tuning capture (motion + typed text as labels, on this phone only).
    private val recorder = TrialRecorder()
    @Volatile
    private var saving = false
    private var savedCount = 0
    private var previousText = ""
    private var lastTextChangeMs = 0L
    private val trimRecording = object : Runnable {
        override fun run() {
            // Don't let an idle page grow one huge recording: restart when nothing is being typed.
            val quiet = SystemClock.uptimeMillis() - lastTextChangeMs > 3_000L
            if (saving && recorder.isRecording && quiet && recorder.sampleCount > MAX_IDLE_SAMPLES) {
                recorder.cancel()
                startTuningRecording()
            }
            main.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTypedListenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        dataset = LocalDatasetStore(this)
        settings = ProjectSettings(this)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.debugSwitch.setOnCheckedChangeListener { _, checked -> binding.debugLine.isVisible = checked }
        // Remembered across visits, so searches are not silently unsaved after reopening the page.
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        binding.saveSwitch.isChecked = prefs.getBoolean(KEY_SAVE, false)
        setSaving(binding.saveSwitch.isChecked)
        binding.saveSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SAVE, checked).apply()
            setSaving(checked)
        }
        buildGuessRows()
        showGuess(null)

        binding.typedInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                onSearch(v.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        binding.typedInput.doAfterTextChanged { onTextChanged(it?.toString().orEmpty()) }

        // Callbacks arrive on the sensor thread; the listener does all its work there.
        collector = SensorCollector(this) { sample ->
            listener?.offer(sample)
            if (saving) recorder.offer(sample)
        }

        loader.execute {
            val bundle = TapModelStore(this).load()
            main.post {
                if (isFinishing) return@post
                if (bundle == null) {
                    binding.filterChip.setText(R.string.search_filter_untrained)
                    binding.verdict.setText(R.string.search_verdict_untrained)
                    return@post
                }
                threshold = bundle.threshold
                listener = QueryListener(bundle) { event -> main.post { apply(event) } }
                binding.filterChip.setText(R.string.search_filter_on)
            }
        }

        binding.typedInput.post { focusKeyboard() }
    }

    override fun onResume() {
        super.onResume()
        listener?.reset()
        collector.start(SensorCollector.TAP_SAMPLING_US, Handler(sensorThread.looper))
        if (saving) startTuningRecording()
        main.postDelayed(trimRecording, 5_000L)
        focusKeyboard()
    }

    override fun onPause() {
        main.removeCallbacks(showPendingResults)
        main.removeCallbacks(trimRecording)
        collector.stop()
        if (recorder.isRecording) recorder.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        sensorThread.quitSafely()
        super.onDestroy()
    }

    // ---- Filter events -------------------------------------------------------------------

    private fun apply(event: QueryListener.Event) {
        if (isFinishing) return
        when (event) {
            is QueryListener.Event.Typing -> {
                if (event.taps == 1) {
                    // A new query started: clear the previous verdict.
                    lastFlag = null
                    binding.blockedCard.isVisible = false
                    binding.resultsCard.isVisible = false
                }
                showTaps(event.taps)
                showGuess(event.guess)
                val lean = event.guess.probabilities.maxByOrNull { it.value }?.key ?: TypedLabel.OTHER
                binding.verdict.text = getString(R.string.search_verdict_leaning, nameOf(lean))
            }
            QueryListener.Event.Idle -> Unit
            is QueryListener.Event.Scored -> {
                showTaps(event.result.tapCount)
                showGuess(event.result)
                if (binding.debugSwitch.isChecked) showDebug(event)
                if (event.flagged) {
                    lastFlag = event.result.label
                    showBlocked(event.result.label, event.result.tapCount)
                    binding.verdict.text = getString(
                        R.string.search_verdict_blocked, event.result.tapCount, event.result.label.displayName,
                    )
                } else {
                    binding.verdict.text = getString(R.string.search_verdict_allowed, event.result.tapCount)
                    pendingSearch?.let { showResults(it) }
                }
                pendingSearch = null
                main.removeCallbacks(showPendingResults)
            }
        }
    }

    private fun onSearch(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        // The verdict comes when the IMU sees the typing pause (usually within half a second).
        if (lastFlag == null) {
            pendingSearch = query
            main.removeCallbacks(showPendingResults)
            main.postDelayed(showPendingResults, RESULTS_FALLBACK_MS)
        }
        binding.typedInput.selectAll() // next typing replaces the query, like a real search box
        if (saving) saveTuningSearch(query)
    }

    private fun buildGuessRows() {
        binding.guessRows.removeAllViews()
        for (label in listOf(TypedLabel.GOOGLE, TypedLabel.YAHOO, TypedLabel.OTHER)) {
            val row = ItemMetricRowBinding.inflate(layoutInflater, binding.guessRows, false)
            row.metricName.text = nameOf(label)
            binding.guessRows.addView(row.root)
            guessRows[label] = row
        }
    }

    private fun nameOf(label: TypedLabel): String =
        if (label == TypedLabel.OTHER) getString(R.string.search_other_label) else label.displayName

    private fun showGuess(result: WordSpotter.Result?) {
        for ((label, row) in guessRows) {
            val p = result?.probabilities?.get(label) ?: 0f
            row.metricBar.progress = (p * 100).toInt()
            row.metricValue.text = if (result == null) "" else getString(R.string.percent_format, p * 100f)
        }
    }

    private fun showTaps(n: Int) {
        binding.tapCount.text = getString(R.string.search_tap_count, n)
        val dots = binding.tapDots
        val size = (10 * resources.displayMetrics.density).toInt()
        val gap = (5 * resources.displayMetrics.density).toInt()
        while (dots.childCount < n && dots.childCount < MAX_DOTS) {
            dots.addView(
                View(this).apply { setBackgroundResource(R.drawable.bg_dot) },
                LinearLayout.LayoutParams(size, size).apply { marginEnd = gap },
            )
        }
        while (dots.childCount > n) dots.removeViewAt(dots.childCount - 1)
    }

    private fun showBlocked(label: TypedLabel, taps: Int) {
        binding.resultsCard.isVisible = false
        binding.blockedTitle.setText(R.string.search_blocked_title)
        binding.blockedBody.text = getString(R.string.search_blocked_body, label.displayName, taps)
        binding.blockedCard.isVisible = true
    }

    private fun showResults(query: String) {
        if (binding.blockedCard.isVisible) return
        binding.resultsTitle.text = getString(R.string.search_results_title, query)
        binding.resultsCard.isVisible = true
    }

    private fun showDebug(e: QueryListener.Event.Scored) {
        val s = e.result.scores
        val bestOther = WordSpotter.COMPETITORS.maxBy { s.getValue(it) }
        binding.debugLine.text = getString(
            R.string.typed_listen_debug_line,
            e.result.tapCount,
            s.getValue(TypedWordBank.GOOGLE),
            s.getValue(TypedWordBank.YAHOO),
            bestOther,
            s.getValue(bestOther),
            e.result.margin,
            threshold,
            e.scoreMs,
        ) + (if (e.early) " · early" else "") + (if (e.carried) " · joined across pause" else "") +
            " · pause ${listener?.queryGapMs ?: 0} ms"
    }

    // ---- Opt-in tuning capture -----------------------------------------------------------

    private fun setSaving(on: Boolean) {
        saving = on
        if (on) startTuningRecording() else if (recorder.isRecording) recorder.cancel()
        showSaveStatus()
    }

    private fun showSaveStatus() {
        val total = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SAVED_TOTAL, 0)
        binding.saveStatus.text = if (saving) {
            getString(R.string.search_saved_count, savedCount, total)
        } else {
            getString(R.string.search_save_explain) + " " + getString(R.string.search_saved_total, total)
        }
    }

    private fun startTuningRecording() {
        if (recorder.isRecording) return
        recorder.start(
            label = "",
            task = TrialTask.TYPED_TAP,
            clientRole = settings.clientRole,
            notes = TapTrainer.LIVE_KEYBOARD_NOTE,
            sessionTag = TypedPlayActivity.currentGrip(this),
        )
    }

    /**
     * Text-change labels for tuning only: the system keyboard reports characters, not touches,
     * so each label is the key's centre at the time the text changed (tens of ms after the tap).
     */
    private fun onTextChanged(text: String) {
        lastTextChangeMs = SystemClock.uptimeMillis()
        val before = previousText
        previousText = text
        if (!saving || !recorder.isRecording) return
        val now = SystemClock.elapsedRealtimeNanos()
        val key = when {
            text.length == before.length + 1 && text.startsWith(before) -> text.last().toString()
            text.length == 1 -> text // typed over a selected query
            text.length == before.length - 1 && before.startsWith(text) -> TouchLabel.KEY_BACKSPACE
            else -> null
        } ?: return
        val label = if (key == " ") TouchLabel.KEY_SPACE else key.lowercase()
        val k = KeyboardLayout.key(label) ?: return
        recorder.offerTouch(TouchLabel(now, k.cx, k.cy, k.label))
    }

    private fun saveTuningSearch(query: String) {
        if (!recorder.isRecording) return
        val enter = KeyboardLayout.key(TouchLabel.KEY_ENTER)!!
        recorder.offerTouch(TouchLabel(SystemClock.elapsedRealtimeNanos(), enter.cx, enter.cy, enter.label))
        // Keep recording briefly so the search tap's motion window is complete.
        main.postDelayed({
            if (!saving || !recorder.isRecording) return@postDelayed
            val trial = recorder.stop()
            loader.execute { dataset.save(trial.copy(label = query.lowercase())) }
            savedCount++
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_SAVED_TOTAL, prefs.getInt(KEY_SAVED_TOTAL, 0) + 1).apply()
            showSaveStatus()
            startTuningRecording()
        }, TAIL_MS)
    }

    private fun focusKeyboard() {
        binding.typedInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.typedInput, InputMethodManager.SHOW_IMPLICIT)
    }

    companion object {
        private const val PREFS = "typed_listen"
        private const val KEY_SAVE = "save_for_tuning"
        private const val KEY_SAVED_TOTAL = "saved_total"
        private const val RESULTS_FALLBACK_MS = 1_500L
        private const val TAIL_MS = 400L
        private const val MAX_DOTS = 24
        /** ~30 s at 200 Hz. */
        private const val MAX_IDLE_SAMPLES = 6_000
    }
}
