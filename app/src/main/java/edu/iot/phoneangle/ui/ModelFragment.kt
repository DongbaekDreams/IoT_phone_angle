package edu.iot.phoneangle.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import edu.iot.phoneangle.ProjectSettings
import edu.iot.phoneangle.R
import edu.iot.phoneangle.data.PhonePose
import edu.iot.phoneangle.databinding.FragmentModelBinding
import edu.iot.phoneangle.databinding.ItemMetricRowBinding
import edu.iot.phoneangle.ml.EvaluationReport
import edu.iot.phoneangle.ml.InferenceEngine
import edu.iot.phoneangle.ml.ModelSlot
import edu.iot.phoneangle.ml.PoseModelBundle
import edu.iot.phoneangle.ml.PoseWorkflow
import edu.iot.phoneangle.ml.SensorConfig
import edu.iot.phoneangle.sensors.SensorCollector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Train the local pose baseline, show held-out metrics, and run a live test (class project).
 */
class ModelFragment : Fragment(), Refreshable {

    private var _binding: FragmentModelBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: ProjectSettings
    private lateinit var workflow: PoseWorkflow
    private lateinit var collector: SensorCollector
    private val engine = InferenceEngine(null)

    private val executor = Executors.newSingleThreadExecutor()
    private val training = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val poseLiveRows = mutableMapOf<PhonePose, ItemMetricRowBinding>()
    private var liveOn = false

    private val liveTicker = object : Runnable {
        override fun run() {
            renderPoseLive()
            handler.postDelayed(this, 200L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentModelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        settings = ProjectSettings(ctx)
        workflow = PoseWorkflow(ctx)
        collector = SensorCollector(ctx) { sample ->
            if (liveOn) engine.offer(sample)
        }

        binding.sensorConfigSpinner.adapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_dropdown_item,
            SensorConfig.entries.map { it.displayName },
        )
        binding.advancedToggle.setOnClickListener {
            val show = !binding.advancedSection.isVisible
            binding.advancedSection.isVisible = show
            binding.advancedToggle.setText(if (show) R.string.model_advanced_hide else R.string.model_advanced_show)
        }
        binding.trainButton.setOnClickListener { runTraining() }
        binding.trainButton.setText(R.string.model_train)
        binding.modelExplain.setText(R.string.model_explain)

        for (pose in PhonePose.entries) {
            val row = ItemMetricRowBinding.inflate(layoutInflater, binding.liveBars, false)
            row.metricName.text = pose.displayName
            binding.liveBars.addView(row.root)
            poseLiveRows[pose] = row
        }
        binding.liveModelToggle.check(R.id.liveUseBaseline)
        binding.liveModelToggle.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) loadLiveModel()
        }
        binding.liveSwitch.setOnCheckedChangeListener { _, on -> setLive(on) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        binding.liveSwitch.isChecked = false
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(liveTicker)
        collector.stop()
        poseLiveRows.clear()
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun refresh() {
        if (_binding == null) return
        binding.trainButton.isEnabled = !training.get()
        val role = settings.clientRole
        val status = workflow.status(role)
        binding.modelDataStatus.text = when {
            status.totalTrials == 0 -> getString(R.string.model_status_none)
            !status.ready -> getString(R.string.model_status_few, status.totalTrials, PoseWorkflow.MIN_TOTAL_EXAMPLES)
            else -> getString(R.string.model_status_ready, status.totalTrials)
        }
        val baseline = workflow.models.load(ModelSlot.LOCAL_BASELINE)
        if (baseline == null) showEmptyResults()
        else {
            val report = workflow.evaluateHoldout(baseline)
            renderPoseResults(baseline, report, baseline.trainSampleCount, report?.sampleCount ?: 0)
        }
        loadLiveModel()
    }

    private fun runTraining() {
        if (!training.compareAndSet(false, true)) return
        binding.trainButton.isEnabled = false
        binding.trainProgress.isVisible = true
        executor.execute {
            val config = SensorConfig.entries[binding.sensorConfigSpinner.selectedItemPosition]
            val outcome = runCatching { workflow.trainBaseline(config) }
            activity?.runOnUiThread {
                training.set(false)
                if (_binding == null) return@runOnUiThread
                binding.trainButton.isEnabled = true
                binding.trainProgress.isVisible = false
                outcome.onSuccess {
                    refresh()
                }.onFailure { e ->
                    binding.resultSummary.text = getString(R.string.model_train_failed, e.message ?: "unknown error")
                }
            }
        }
    }

    private fun showEmptyResults() {
        binding.resultSummary.setText(R.string.model_results_empty)
        binding.perClassContainer.removeAllViews()
        binding.confusionTitle.isVisible = false
        binding.confusionView.isVisible = false
        binding.confusionLegend.isVisible = false
    }

    private fun renderPoseResults(bundle: PoseModelBundle, report: EvaluationReport?, trainN: Int, testN: Int) {
        binding.perClassContainer.removeAllViews()
        if (report == null) {
            binding.resultSummary.text = getString(R.string.model_results_no_test, trainN, bundle.sensorConfig.displayName)
            binding.confusionTitle.isVisible = false
            binding.confusionView.isVisible = false
            binding.confusionLegend.isVisible = false
            return
        }
        binding.resultSummary.text = getString(
            R.string.model_results_summary,
            trainN,
            testN,
            bundle.sensorConfig.displayName,
            report.accuracy * 100f,
            report.macroF1 * 100f,
        )
        for (m in report.perClass) {
            val row = ItemMetricRowBinding.inflate(layoutInflater, binding.perClassContainer, false)
            row.metricName.text = m.pose.displayName
            row.metricBar.progress = (m.f1 * 100).toInt()
            row.metricValue.text = getString(R.string.model_class_value, m.f1 * 100f, m.support)
            binding.perClassContainer.addView(row.root)
        }
        binding.confusionTitle.isVisible = true
        binding.confusionView.isVisible = true
        binding.confusionLegend.isVisible = true
        binding.confusionView.setMatrix(report.confusion)
        binding.confusionLegend.text = report.classes.withIndex()
            .joinToString("   ") { (i, pose) -> "${i + 1} = ${pose.displayName}" }
    }

    private fun loadLiveModel() {
        if (_binding == null) return
        val slot = if (binding.liveModelToggle.checkedButtonId == R.id.liveUseGlobal) {
            ModelSlot.GLOBAL
        } else {
            ModelSlot.LOCAL_BASELINE
        }
        engine.setModel(workflow.models.load(slot))
        engine.clearBuffer()
        if (!engine.hasModel) {
            binding.livePose.text = getString(R.string.model_live_no_model)
            binding.liveConfidence.text = ""
            poseLiveRows.values.forEach { it.metricBar.progress = 0; it.metricValue.text = "" }
        }
    }

    private fun setLive(on: Boolean) {
        if (_binding == null || on == liveOn) return
        liveOn = on
        if (on) {
            loadLiveModel()
            collector.start()
            handler.post(liveTicker)
        } else {
            handler.removeCallbacks(liveTicker)
            collector.stop()
            engine.clearBuffer()
            if (engine.hasModel) {
                binding.livePose.text = getString(R.string.model_live_off)
                binding.liveConfidence.text = ""
            }
        }
    }

    private fun renderPoseLive() {
        if (_binding == null || !engine.hasModel) return
        val pred = engine.predict()
        if (pred == null) {
            binding.livePose.text = getString(R.string.model_live_warming)
            binding.liveConfidence.text = ""
            return
        }
        binding.livePose.text = pred.pose.displayName
        binding.liveConfidence.text = getString(R.string.model_live_confidence, pred.confidence * 100f)
        for ((pose, row) in poseLiveRows) {
            val p = pred.probabilities[pose] ?: 0f
            row.metricBar.progress = (p * 100).toInt()
            row.metricValue.text = getString(R.string.percent_format, p * 100f)
        }
    }
}
