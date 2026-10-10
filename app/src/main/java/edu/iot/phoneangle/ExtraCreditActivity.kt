package edu.iot.phoneangle

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import edu.iot.phoneangle.databinding.ActivityExtraCreditBinding
import edu.iot.phoneangle.databinding.ItemMetricRowBinding
import edu.iot.phoneangle.ml.typed.TypedEvalReport
import edu.iot.phoneangle.ml.typed.TypedLabel
import edu.iot.phoneangle.ml.typed.TypedModelBundle
import edu.iot.phoneangle.ml.typed.TypedReplayEval
import edu.iot.phoneangle.ml.typed.TypedWorkflow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extra credit hub: typed-word detection from phone motion (IMU), separate from the
 * phone-angle class project. Collect (prompted words, idle) -> train -> live flagging.
 */
class ExtraCreditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExtraCreditBinding
    private lateinit var workflow: TypedWorkflow
    private val executor = Executors.newSingleThreadExecutor()
    private val training = AtomicBoolean(false)

    /** Last train outcome text for the live model this session; falls back to the saved bundle. */
    private var liveNote: String? = null
    private var snippedNote: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExtraCreditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        workflow = TypedWorkflow(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.ecPlayButton.setOnClickListener { open(TypedPlayActivity::class.java) }
        binding.ecIdleButton.setOnClickListener {
            startActivity(
                Intent(this, TypedWordActivity::class.java)
                    .putExtra(TypedWordActivity.EXTRA_TARGET, "idle"),
            )
        }
        binding.ecTrainButton.setOnClickListener { runTraining() }
        binding.ecListenButton.setOnClickListener { open(TypedListenActivity::class.java) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun refresh() {
        val status = workflow.status()
        binding.ecCounts.text = getString(
            R.string.ec_counts,
            status.count(TypedLabel.GOOGLE),
            status.count(TypedLabel.YAHOO),
            status.count(TypedLabel.OTHER),
            status.idleCount,
        )
        binding.ecTrainStatus.text = when {
            status.total == 0 -> getString(R.string.model_status_none)
            !status.ready -> getString(
                R.string.typed_guidance_need,
                TypedWorkflow.MIN_PER_CLASS,
                status.count(TypedLabel.GOOGLE),
                status.count(TypedLabel.YAHOO),
                status.count(TypedLabel.OTHER),
            )
            else -> getString(R.string.model_status_ready, status.total)
        }
        binding.ecTrainButton.isEnabled = !training.get()
        val hasLive = workflow.liveModels.has()
        binding.ecListenButton.isEnabled = hasLive
        binding.ecListenHint.setText(if (hasLive) R.string.ec_listen_ready else R.string.typed_listen_need_model)
        renderResults()
    }

    /** Load models and the holdout report off the UI thread (re-featurizing every trial is slow). */
    private fun renderResults() {
        val bundle = workflow.models.load()
        if (bundle == null) {
            binding.ecResultSummary.setText(R.string.model_results_empty)
            binding.ecPerClass.removeAllViews()
            binding.ecConfusion.isVisible = false
            binding.ecConfusionLegend.isVisible = false
            binding.ecLiveSummary.text = liveLine()
            return
        }
        binding.ecResultSummary.text = getString(R.string.model_holdout_loading, bundle.trainSampleCount)
        binding.ecPerClass.removeAllViews()
        binding.ecConfusion.isVisible = false
        binding.ecConfusionLegend.isVisible = false
        binding.ecLiveSummary.text = liveLine()
        executor.execute {
            val report = runCatching { workflow.evaluateHoldout(bundle) }.getOrNull()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                renderTyped(bundle.trainSampleCount, report)
            }
        }
    }

    private fun renderTyped(trainN: Int, report: TypedEvalReport?) {
        binding.ecPerClass.removeAllViews()
        if (report == null) {
            binding.ecResultSummary.text = getString(R.string.model_results_no_test, trainN, "typed")
            binding.ecConfusion.isVisible = false
            binding.ecConfusionLegend.isVisible = false
            return
        }
        binding.ecResultSummary.text = getString(
            R.string.model_results_summary,
            trainN,
            report.sampleCount,
            "typed",
            report.accuracy * 100f,
            report.macroF1 * 100f,
        )
        for (m in report.perClass) {
            val row = ItemMetricRowBinding.inflate(layoutInflater, binding.ecPerClass, false)
            row.metricName.text = m.label.displayName
            row.metricBar.progress = (m.f1 * 100).toInt()
            row.metricValue.text = getString(
                R.string.model_typed_class_value,
                m.f1 * 100f,
                m.precision * 100f,
                m.recall * 100f,
                m.support,
                m.falsePositives,
            )
            binding.ecPerClass.addView(row.root)
        }
        binding.ecConfusion.isVisible = true
        binding.ecConfusionLegend.isVisible = true
        binding.ecConfusion.setMatrix(report.confusion)
        binding.ecConfusionLegend.text = report.classes.withIndex()
            .joinToString("   ") { (i, label) -> "${i + 1} = ${label.displayName}" }
    }

    private fun liveSummary(
        bundle: TypedModelBundle,
        train: Int,
        test: Int,
        acc: Float?,
        replay: TypedReplayEval.Report? = null,
    ): String {
        val head = getString(
            R.string.model_live_summary,
            train,
            test,
            acc?.let { "%.0f%%".format(it * 100f) } ?: "n/a",
            bundle.windowMs,
        )
        if (replay == null) return head
        return head + "\n" + getString(
            R.string.model_live_replay,
            replay.precision * 100f,
            replay.recall * 100f,
            replay.falseAlertsPerMinute,
            replay.meanLatencyMs,
            replay.inferenceCount,
            replay.meanInferenceMs,
        )
    }

    private fun liveLine(): String {
        val head = snippedNote?.let { it + "\n" }.orEmpty()
        liveNote?.let { return head + it }
        val b = workflow.liveModels.load() ?: return head + getString(R.string.model_live_none)
        return head + liveSummary(b, b.trainSampleCount, 0, b.testAccuracy)
    }

    private fun runTraining() {
        if (!training.compareAndSet(false, true)) return
        binding.ecTrainButton.isEnabled = false
        binding.ecTrainProgress.isVisible = true
        executor.execute {
            // Independent: the live model can train even if the prompted-word model cannot (and vice versa).
            val snipped = runCatching { workflow.trainBaseline() }
            val live = runCatching { workflow.trainLive() }
            snippedNote = snipped.exceptionOrNull()?.let {
                getString(R.string.model_train_failed, it.message ?: "unknown error")
            }
            liveNote = live.fold(
                onSuccess = {
                    liveSummary(it.bundle, it.trainWindows, it.testWindows, it.report?.accuracy, it.replay)
                },
                onFailure = { getString(R.string.model_live_failed, it.message ?: "unknown error") },
            )
            runOnUiThread {
                training.set(false)
                if (isFinishing) return@runOnUiThread
                binding.ecTrainButton.isEnabled = true
                binding.ecTrainProgress.isVisible = false
                refresh()
            }
        }
    }
}