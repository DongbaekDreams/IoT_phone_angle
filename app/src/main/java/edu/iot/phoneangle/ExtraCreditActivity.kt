package edu.iot.phoneangle

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import edu.iot.phoneangle.databinding.ActivityExtraCreditBinding
import edu.iot.phoneangle.databinding.ItemMetricRowBinding
import edu.iot.phoneangle.ml.typed.TapModelBundle
import edu.iot.phoneangle.ml.typed.TapWorkflow
import edu.iot.phoneangle.ml.typed.TypedLabel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extra credit hub: search-query spotting from phone motion (IMU), separate from the
 * phone-angle class project. Collect (search queries, idle) -> train -> live flagging.
 */
class ExtraCreditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExtraCreditBinding
    private lateinit var workflow: TapWorkflow
    private val executor = Executors.newSingleThreadExecutor()
    private val training = AtomicBoolean(false)
    private var trainError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExtraCreditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        workflow = TapWorkflow(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.ecPlayButton.setOnClickListener { open(TypedPlayActivity::class.java) }
        binding.ecIdleButton.setOnClickListener {
            startActivity(
                Intent(this, TypedWordActivity::class.java)
                    .putExtra(TypedWordActivity.EXTRA_TARGET, "idle"),
            )
        }
        binding.ecTrainButton.setOnClickListener { runTraining() }
        binding.ecFederateButton.setOnClickListener { runFederation() }
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
            status.count(TypedLabel.IDLE),
        )
        binding.ecTrainStatus.text = when {
            status.queries == 0 -> getString(R.string.model_status_none)
            !status.ready -> getString(R.string.model_status_few, status.queries, TapWorkflow.MIN_QUERIES)
            else -> getString(R.string.model_status_ready, status.queries)
        }
        binding.ecTrainButton.isEnabled = !training.get()
        val hasModel = workflow.models.has()
        binding.ecListenButton.isEnabled = hasModel
        binding.ecListenHint.setText(if (hasModel) R.string.ec_listen_ready else R.string.typed_listen_need_model)
        executor.execute {
            val bundle = workflow.models.load()
            runOnUiThread { if (!isFinishing) render(bundle) }
        }
    }

    private fun render(bundle: TapModelBundle?) {
        binding.ecPerClass.removeAllViews()
        binding.ecConfusion.isVisible = false
        binding.ecConfusionLegend.isVisible = false
        val err = trainError?.let { getString(R.string.model_train_failed, it) + "\n" }.orEmpty()
        if (bundle == null) {
            binding.ecResultSummary.text = err + getString(R.string.model_results_empty)
            binding.ecLiveSummary.text = ""
            return
        }
        val r = bundle.report
        val ablation = r?.ablation.orEmpty().joinToString("\n") {
            getString(R.string.ec_ablation_row, it.name, pct(it.testMacroF1), pct(it.testFalseFlagRate))
        }
        binding.ecLiveSummary.text = listOf(
            getString(
                R.string.ec_model_detail,
                bundle.trainTaps,
                bundle.sensitivity,
                bundle.threshold,
                r?.meanScoreMs ?: 0f,
            ),
            getString(
                R.string.ec_model_weights,
                bundle.weights.thumb,
                bundle.weights.doubleKeyTiming,
                bundle.weights.movement,
            ),
            if (ablation.isEmpty()) "" else getString(R.string.ec_ablation_title) + "\n" + ablation,
        ).filter { it.isNotEmpty() }.joinToString("\n\n")
        if (r == null || r.testQueries == 0) {
            binding.ecResultSummary.text = err + getString(R.string.ec_results_no_test, bundle.trainTaps)
            return
        }
        binding.ecResultSummary.text = err + getString(
            R.string.ec_results_summary,
            r.trainQueries,
            r.valQueries,
            r.testQueries,
            pct(r.queryAccuracy),
            pct(r.queryMacroF1),
            pct(r.falseFlagRate),
            r.cvFolds,
        )
        metric(R.string.ec_metric_tap_detect, f1(r.tapPrecision, r.tapRecall),
            getString(R.string.ec_metric_tap_detect_value, pct(r.tapPrecision), pct(r.tapRecall), num(r.idleFalseTapsPerMin)))
        metric(R.string.ec_metric_row, r.rowAccuracy, pct(r.rowAccuracy))
        metric(R.string.ec_metric_key, r.keyAccuracy,
            getString(R.string.ec_metric_key_value, pct(r.keyAccuracy), num(r.meanTapErrorKeys)))
        if (r.thumbAccuracy.isFinite()) metric(R.string.ec_metric_thumb, r.thumbAccuracy, pct(r.thumbAccuracy))
        metric(R.string.ec_metric_query, r.queryMacroF1, pct(r.queryMacroF1))
        if (r.liveQueries > 0) {
            metric(
                R.string.ec_metric_live,
                r.liveCorrect.toFloat() / r.liveQueries,
                getString(R.string.ec_metric_live_value, r.liveCorrect, r.liveQueries, r.liveTargetsFlagged, r.liveTargets),
            )
        }

        binding.ecConfusion.isVisible = true
        binding.ecConfusionLegend.isVisible = true
        binding.ecConfusion.setMatrix(r.confusion)
        binding.ecConfusionLegend.text = TypedLabel.SNIPPED.withIndex()
            .joinToString("   ") { (i, label) -> "${i + 1} = ${label.displayName}" }
    }

    private fun metric(nameRes: Int, value: Float, text: String) {
        val row = ItemMetricRowBinding.inflate(layoutInflater, binding.ecPerClass, false)
        row.metricName.setText(nameRes)
        row.metricBar.progress = if (value.isFinite()) (value * 100).toInt() else 0
        row.metricValue.text = text
        binding.ecPerClass.addView(row.root)
    }

    private fun pct(v: Float): String = if (v.isFinite()) "%.0f%%".format(v * 100f) else "n/a"
    private fun num(v: Float): String = if (v.isFinite()) "%.2f".format(v) else "n/a"
    private fun f1(p: Float, r: Float): Float = if (p + r <= 0f) 0f else 2 * p * r / (p + r)

    /** Share this phone's typed model and average with every other phone's (Git transport). */
    private fun runFederation() {
        if (!training.compareAndSet(false, true)) return
        binding.ecFederateButton.isEnabled = false
        binding.ecTrainProgress.isVisible = true
        binding.ecFederateStatus.setText(R.string.ec_federate_running)
        executor.execute {
            val msg = runCatching {
                val settings = ProjectSettings(this)
                workflow.federate(edu.iot.phoneangle.federation.FederationTransport.create(settings), settings.clientId)
            }.getOrElse { getString(R.string.model_train_failed, it.message ?: it.javaClass.simpleName) }
            runOnUiThread {
                training.set(false)
                if (isFinishing) return@runOnUiThread
                binding.ecFederateButton.isEnabled = true
                binding.ecTrainProgress.isVisible = false
                binding.ecFederateStatus.text = msg
                refresh()
            }
        }
    }

    private fun runTraining() {
        if (!training.compareAndSet(false, true)) return
        binding.ecTrainButton.isEnabled = false
        binding.ecTrainProgress.isVisible = true
        executor.execute {
            val result = runCatching { workflow.train() }
            trainError = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            runOnUiThread {
                training.set(false)
                if (isFinishing) return@runOnUiThread
                binding.ecTrainProgress.isVisible = false
                refresh()
            }
        }
    }
}
