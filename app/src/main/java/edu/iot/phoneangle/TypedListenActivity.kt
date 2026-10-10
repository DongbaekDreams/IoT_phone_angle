package edu.iot.phoneangle

import android.content.Context
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import edu.iot.phoneangle.databinding.ActivityTypedListenBinding
import edu.iot.phoneangle.databinding.ItemMetricRowBinding
import edu.iot.phoneangle.ml.typed.ListenPhase
import edu.iot.phoneangle.ml.typed.TypedLabel
import edu.iot.phoneangle.ml.typed.TypedListenTick
import edu.iot.phoneangle.ml.typed.TypedListener
import edu.iot.phoneangle.ml.typed.TypedModelStore
import edu.iot.phoneangle.ml.typed.TypedWindowing
import edu.iot.phoneangle.sensors.SensorCollector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live demo: monitor IMU typing bursts; show a clear safety-style **Flagged** alert
 * when a stand-in target site name (google / yahoo) is likely. Keyboard is display-only.
 */
class TypedListenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTypedListenBinding
    private lateinit var collector: SensorCollector
    private val listener = TypedListener.forLiveListen()
    private val handler = Handler(Looper.getMainLooper())
    private val scoreExecutor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val confRows = linkedMapOf<TypedLabel, ItemMetricRowBinding>()
    private val liveTicker = object : Runnable {
        override fun run() {
            requestStep()
            handler.postDelayed(this, TypedWindowing.LIVE_STRIDE_MS)
        }
    }

    private val hidePopup = Runnable {
        binding.detectPopup.isVisible = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTypedListenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        binding.statusLine.setText(R.string.typed_listen_loading_model)

        collector = SensorCollector(this) { sample -> listener.offer(sample) }
        binding.toolbar.setNavigationOnClickListener { finish() }
        buildConfidenceRows()
        binding.debugSwitch.setOnCheckedChangeListener { _, checked ->
            binding.debugPanel.isVisible = checked
        }

        scoreExecutor.execute {
            val snipped = TypedModelStore(this).load()
            val live = TypedModelStore(this, TypedModelStore.LIVE_FILE_NAME).load()
            handler.post {
                listener.setModels(word = snipped, live = live)
                when {
                    listener.hasModel -> {
                        binding.statusLine.setText(R.string.typed_listen_monitoring)
                        binding.statusDetail.setText(
                            if (listener.usingSnipped) R.string.typed_listen_monitoring_detail
                            else R.string.typed_listen_monitoring_live_only,
                        )
                    }
                    else -> {
                        binding.statusLine.setText(R.string.typed_listen_need_model)
                        binding.statusDetail.setText(R.string.typed_listen_need_model_detail)
                    }
                }
            }
        }

        binding.typedInput.post { focusKeyboard() }
        binding.typedInput.postDelayed({ focusKeyboard() }, 200L)
    }

    override fun onResume() {
        super.onResume()
        collector.start(SensorManager.SENSOR_DELAY_GAME)
        handler.postDelayed(liveTicker, TypedWindowing.LIVE_STRIDE_MS)
        focusKeyboard()
    }

    override fun onPause() {
        handler.removeCallbacks(liveTicker)
        handler.removeCallbacks(hidePopup)
        collector.stop()
        super.onPause()
    }

    override fun onDestroy() {
        scoreExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildConfidenceRows() {
        binding.confidenceRows.removeAllViews()
        confRows.clear()
        for (label in TypedLabel.LIVE) {
            val row = ItemMetricRowBinding.inflate(layoutInflater, binding.confidenceRows, false)
            row.metricName.text = label.displayName
            row.metricBar.progress = 0
            row.metricValue.text = ""
            binding.confidenceRows.addView(
                row.root,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            confRows[label] = row
        }
    }

    private fun requestStep() {
        if (!listener.hasModel || !busy.compareAndSet(false, true)) return
        scoreExecutor.execute {
            val tick = try {
                listener.step()
            } finally {
                busy.set(false)
            }
            handler.post { apply(tick) }
        }
    }

    private fun apply(tick: TypedListenTick) {
        if (isFinishing) return
        when (tick.phase) {
            ListenPhase.WARMING -> {
                binding.statusLine.setText(
                    if (listener.hasModel) R.string.typed_listen_warming else R.string.typed_listen_need_model,
                )
                binding.statusDetail.setText(R.string.typed_listen_monitoring_detail)
            }
            ListenPhase.IDLE -> {
                binding.statusLine.setText(R.string.typed_listen_monitoring)
                binding.statusDetail.setText(R.string.typed_listen_phase_idle)
            }
            ListenPhase.ACTIVE -> {
                binding.statusLine.setText(R.string.typed_listen_monitoring)
                binding.statusDetail.setText(R.string.typed_listen_phase_typing)
            }
        }
        if (binding.debugSwitch.isChecked) {
            val inf = if (tick.inferenceMs >= 0) "${tick.inferenceMs} ms" else "skipped"
            val best = tick.live?.let { "${it.label.displayName} ${(it.confidence * 100).toInt()}%" } ?: "—"
            val model = if (listener.usingSnipped) "snipped" else "live"
            binding.debugLine.text = getString(
                R.string.typed_listen_debug_line,
                best,
                inf,
                listener.inferenceCount,
                listener.proposals,
            ) + " · $model"
            for ((label, row) in confRows) {
                val p = tick.probabilities?.get(label) ?: 0f
                row.metricBar.progress = (p * 100).toInt()
                row.metricValue.text = getString(R.string.percent_format, p * 100f)
            }
        }
        val alert = tick.alert ?: return
        binding.statusLine.setText(R.string.typed_listen_flagged)
        binding.statusDetail.text = getString(R.string.typed_listen_flagged_explain, alert.displayName)
        showFlagged(alert)
    }

    private fun showFlagged(label: TypedLabel) {
        handler.removeCallbacks(hidePopup)
        binding.detectPopup.text = getString(R.string.typed_listen_flagged_banner, label.displayName)
        binding.detectPopup.background = ContextCompat.getDrawable(
            this,
            if (label == TypedLabel.GOOGLE) R.drawable.bg_detect_popup else R.drawable.bg_detect_popup_yahoo,
        )
        binding.detectPopup.isVisible = true
        handler.postDelayed(hidePopup, 2_200L)
    }

    private fun focusKeyboard() {
        binding.typedInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.typedInput, InputMethodManager.SHOW_IMPLICIT)
    }
}
