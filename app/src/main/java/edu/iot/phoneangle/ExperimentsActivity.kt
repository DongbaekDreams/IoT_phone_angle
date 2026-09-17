package edu.iot.phoneangle

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import edu.iot.phoneangle.data.SyntheticPoseData
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.databinding.ActivityExperimentsBinding
import edu.iot.phoneangle.ml.Evaluator
import edu.iot.phoneangle.ml.LocalTrainer
import edu.iot.phoneangle.ml.ModelStore
import edu.iot.phoneangle.ml.PoseModelBundle
import edu.iot.phoneangle.ml.Preprocessor
import edu.iot.phoneangle.ml.SensorConfig
import edu.iot.phoneangle.ml.TrainConfig
import edu.iot.phoneangle.ml.splitTrainTest
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
    private lateinit var modelStore: ModelStore
    private lateinit var settings: ProjectSettings

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExperimentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dataset = LocalDatasetStore(this)
        experiments = ExperimentStore(this)
        modelStore = ModelStore(this)
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

    private fun trainOne(config: SensorConfig, saveModel: Boolean) =
        run {
            val trials = dataset.loadAll(TrialTask.POSE)
            val examples = Preprocessor(config).examplesFromTrials(trials)
            if (examples.size < 6) error("Need more trials (have ${examples.size})")
            val (train, test) = splitTrainTest(examples)
            if (train.isEmpty() || test.isEmpty()) error("Need enough samples for train/test split")
            val result = LocalTrainer(TrainConfig()).train(train)
            val report = Evaluator().evaluate(result.model, test)
            if (saveModel) {
                modelStore.saveLocalPose(
                    PoseModelBundle(
                        sensorConfig = config,
                        model = result.model,
                        trainedAtEpochMs = System.currentTimeMillis(),
                        trainAccuracy = result.trainAccuracy,
                        trainSampleCount = result.sampleCount,
                        testAccuracy = report.accuracy,
                        epochs = result.epochsRun,
                    ),
                )
            }
            ExperimentStore.fromEval(
                sensorConfig = config,
                trainCount = train.size,
                testCount = test.size,
                trainAccuracy = result.trainAccuracy,
                report = report,
                epochs = result.epochsRun,
                notes = "client=${settings.clientRole.name}",
            )
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
        val trials = dataset.loadAll(TrialTask.POSE).size
        binding.datasetLine.text = getString(R.string.exp_dataset_line, trials, settings.clientRole.label)
    }

    private fun setBusy(busy: Boolean) {
        binding.runExperimentButton.isEnabled = !busy
        binding.runAllAblationsButton.isEnabled = !busy
        binding.seedSyntheticButton.isEnabled = !busy
    }
}
