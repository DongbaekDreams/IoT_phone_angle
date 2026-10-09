package edu.iot.phoneangle

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import edu.iot.phoneangle.data.SyntheticPoseData
import edu.iot.phoneangle.databinding.ActivityExperimentsBinding
import edu.iot.phoneangle.ml.Evaluator
import edu.iot.phoneangle.ml.LocalTrainer
import edu.iot.phoneangle.ml.PoseWorkflow
import edu.iot.phoneangle.ml.SensorConfig
import edu.iot.phoneangle.store.ExperimentRecord
import edu.iot.phoneangle.store.ExperimentStore
import edu.iot.phoneangle.store.LocalDatasetStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Section 3 — sensor ablations + saved experiment metadata.
 */
class ExperimentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExperimentsBinding
    private lateinit var dataset: LocalDatasetStore
    private lateinit var experiments: ExperimentStore
    private lateinit var workflow: PoseWorkflow
    private lateinit var settings: ProjectSettings

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExperimentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dataset = LocalDatasetStore(this)
        experiments = ExperimentStore(this)
        workflow = PoseWorkflow(this)
        settings = ProjectSettings(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.sensorConfigSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            SensorConfig.entries.map { it.displayName },
        )

        binding.seedSyntheticButton.setOnClickListener {
            val n = SyntheticPoseData.seedStore(dataset, settings.clientRole)
            Toast.makeText(this, getString(R.string.exp_seeded, n), Toast.LENGTH_SHORT).show()
            refreshHistory()
        }

        binding.runExperimentButton.setOnClickListener { runExperiment() }
        binding.runAllAblationsButton.setOnClickListener { runAllAblations() }
        binding.runLosoButton.setOnClickListener { runLoso() }
        binding.clearExperimentsButton.setOnClickListener {
            experiments.deleteAll()
            refreshHistory()
        }

        refreshHistory()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runExperiment() {
        if (!running.compareAndSet(false, true)) return
        val config = SensorConfig.entries[binding.sensorConfigSpinner.selectedItemPosition]
        setBusy(true)
        executor.execute {
            val text = try {
                val record = trainOne(config, saveModel = true)
                experiments.save(record)
                formatRecord(record)
            } catch (e: Exception) {
                getString(R.string.exp_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                running.set(false)
                setBusy(false)
                binding.resultsText.text = text
                refreshHistory()
            }
        }
    }

    private fun runAllAblations() {
        if (!running.compareAndSet(false, true)) return
        setBusy(true)
        executor.execute {
            val text = buildString {
                try {
                    for (config in SensorConfig.entries) {
                        val record = trainOne(config, saveModel = config == SensorConfig.ALL)
                        experiments.save(record)
                        appendLine(record.titleLine())
                        appendLine("  macroF1=${"%.1f".format(record.macroF1 * 100)}%")
                        appendLine()
                    }
                } catch (e: Exception) {
                    append(getString(R.string.exp_failed, e.message ?: e.javaClass.simpleName))
                }
            }
            runOnUiThread {
                running.set(false)
                setBusy(false)
                binding.resultsText.text = text
                refreshHistory()
            }
        }
    }

    /** Ablation on the stable held-out split. [saveModel] also stores it as the local baseline. */
    private fun trainOne(config: SensorConfig, saveModel: Boolean): ExperimentRecord {
        val outcome = workflow.trainBaseline(config, save = saveModel)
        val report = outcome.report ?: error("No held-out trials yet; record more data")
        return ExperimentStore.fromEval(
            sensorConfig = config,
            trainCount = outcome.trainCount,
            testCount = outcome.testCount,
            trainAccuracy = outcome.bundle.trainAccuracy,
            report = report,
            epochs = outcome.bundle.epochs,
            notes = "client=${settings.clientRole.name}",
        )
    }

    private fun runLoso() {
        if (!running.compareAndSet(false, true)) return
        setBusy(true)
        executor.execute {
            val text = try {
                leaveOneSessionOut()
            } catch (e: Exception) {
                getString(R.string.exp_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                running.set(false)
                setBusy(false)
                binding.resultsText.text = text
                refreshHistory()
            }
        }
    }

    /**
     * Room/person generalization: for every session tag, train on all OTHER tags and test on
     * that tag. Tests whether the model survives a new environment.
     */
    private fun leaveOneSessionOut(): String {
        val examples = workflow.examples(SensorConfig.ALL).filter { it.sessionTag.isNotBlank() }
        val tags = examples.map { it.sessionTag }.distinct().sorted()
        if (tags.size < 2) {
            return getString(R.string.exp_loso_need_tags, tags.size)
        }
        val accuracies = mutableListOf<Float>()
        val out = StringBuilder()
        out.appendLine(getString(R.string.exp_loso_header, tags.size))
        for (tag in tags) {
            val train = examples.filter { it.sessionTag != tag }
            val test = examples.filter { it.sessionTag == tag }
            if (train.size < PoseWorkflow.MIN_TOTAL_EXAMPLES || test.isEmpty()) {
                out.appendLine("$tag: skipped (train=${train.size}, test=${test.size})")
                continue
            }
            val result = LocalTrainer(PoseWorkflow.BASELINE_TRAIN).train(train)
            val report = Evaluator().evaluate(result.model, test)
            accuracies += report.accuracy
            out.appendLine("held out '$tag': acc=${"%.1f".format(report.accuracy * 100)}%  macro-F1=${"%.1f".format(report.macroF1 * 100)}%  (train=${train.size}, test=${test.size})")
            experiments.save(
                ExperimentStore.fromEval(
                    sensorConfig = SensorConfig.ALL,
                    trainCount = train.size,
                    testCount = test.size,
                    trainAccuracy = result.trainAccuracy,
                    report = report,
                    epochs = result.epochsRun,
                    notes = "loso heldout=$tag client=${settings.clientRole.name}",
                ),
            )
        }
        if (accuracies.isNotEmpty()) {
            out.appendLine()
            out.appendLine("mean across sessions: ${"%.1f".format(accuracies.average() * 100)}%")
        }
        return out.toString()
    }

    private fun formatRecord(r: edu.iot.phoneangle.store.ExperimentRecord): String = buildString {
        appendLine(r.titleLine())
        appendLine("train acc=${"%.1f".format(r.trainAccuracy * 100)}%  test=${"%.1f".format(r.testAccuracy * 100)}%  macro-F1=${"%.1f".format(r.macroF1 * 100)}%")
        appendLine("n train=${r.trainCount} test=${r.testCount} epochs=${r.epochs}")
        appendLine()
        appendLine(r.perClassSummary)
        appendLine()
        appendLine(r.confusionText)
    }

    private fun refreshHistory() {
        val rows = experiments.loadAll()
        binding.historyText.text = if (rows.isEmpty()) {
            getString(R.string.exp_history_empty)
        } else {
            rows.joinToString("\n") { it.titleLine() }
        }
        val trials = dataset.countByPose().values.sum()
        binding.datasetLine.text = getString(R.string.exp_dataset_line, trials, settings.displayLabel())
    }

    private fun setBusy(busy: Boolean) {
        binding.runExperimentButton.isEnabled = !busy
        binding.runAllAblationsButton.isEnabled = !busy
        binding.runLosoButton.isEnabled = !busy
        binding.seedSyntheticButton.isEnabled = !busy
    }
}
