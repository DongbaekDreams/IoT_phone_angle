package edu.iot.phoneangle

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.databinding.ActivityPoseTrainBinding
import edu.iot.phoneangle.ml.Evaluator
import edu.iot.phoneangle.ml.LocalTrainer
import edu.iot.phoneangle.ml.ModelStore
import edu.iot.phoneangle.ml.PoseModelBundle
import edu.iot.phoneangle.ml.Preprocessor
import edu.iot.phoneangle.ml.SensorConfig
import edu.iot.phoneangle.ml.TrainConfig
import edu.iot.phoneangle.ml.splitTrainTest
import edu.iot.phoneangle.store.LocalDatasetStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Section 1 — preprocess, train, save, evaluate local pose model.
 */
class PoseTrainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPoseTrainBinding
    private lateinit var dataset: LocalDatasetStore
    private lateinit var modelStore: ModelStore

    private val executor = Executors.newSingleThreadExecutor()
    private val training = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPoseTrainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dataset = LocalDatasetStore(this)
        modelStore = ModelStore(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.sensorConfigSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            SensorConfig.entries.map { it.displayName },
        )

        binding.trainButton.setOnClickListener { runTraining() }
        binding.refreshButton.setOnClickListener { refreshSummary() }

        refreshSummary()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun refreshSummary() {
        val trials = dataset.loadAll(TrialTask.POSE)
        val bundle = modelStore.loadLocalPose()
        binding.datasetSummary.text = getString(
            R.string.train_dataset_summary,
            trials.size,
            trials.mapNotNull { it.label }.toSet().size,
        )
        binding.modelSummary.text = if (bundle == null) {
            getString(R.string.train_no_model)
        } else {
            getString(
                R.string.train_model_summary,
                bundle.sensorConfig.displayName,
                bundle.trainSampleCount,
                pct(bundle.trainAccuracy),
                bundle.testAccuracy?.let { pct(it) } ?: "—",
            )
        }
    }

    private fun runTraining() {
        if (!training.compareAndSet(false, true)) return

        val config = SensorConfig.entries[binding.sensorConfigSpinner.selectedItemPosition]
        binding.trainButton.isEnabled = false
        binding.resultsText.text = getString(R.string.train_running)

        executor.execute {
            val resultText = try {
                trainAndEvaluate(config)
            } catch (e: Exception) {
                getString(R.string.train_failed, e.message ?: e.javaClass.simpleName)
            }

            runOnUiThread {
                training.set(false)
                binding.trainButton.isEnabled = true
                binding.resultsText.text = resultText
                refreshSummary()
            }
        }
    }

    private fun trainAndEvaluate(sensorConfig: SensorConfig): String {
        val trials = dataset.loadAll(TrialTask.POSE)
        val preprocessor = Preprocessor(sensorConfig)
        val examples = preprocessor.examplesFromTrials(trials)
        if (examples.size < 6) {
            return getString(R.string.train_need_more, examples.size)
        }

        val (train, test) = splitTrainTest(examples)
        if (train.isEmpty()) {
            return getString(R.string.train_need_more, examples.size)
        }

        val trainResult = LocalTrainer(TrainConfig()).train(train)
        val eval = if (test.isNotEmpty()) {
            Evaluator().evaluate(trainResult.model, test)
        } else {
            null
        }

        val bundle = PoseModelBundle(
            sensorConfig = sensorConfig,
            model = trainResult.model,
            trainedAtEpochMs = System.currentTimeMillis(),
            trainAccuracy = trainResult.trainAccuracy,
            trainSampleCount = trainResult.sampleCount,
            testAccuracy = eval?.accuracy,
            epochs = trainResult.epochsRun,
        )
        modelStore.saveLocalPose(bundle)

        runOnUiThread {
            Toast.makeText(this, R.string.train_saved, Toast.LENGTH_SHORT).show()
        }

        return buildString {
            appendLine(getString(R.string.train_result_header, sensorConfig.displayName))
            appendLine(getString(R.string.train_result_counts, train.size, test.size))
            appendLine(getString(R.string.train_result_train_acc, pct(trainResult.trainAccuracy)))
            if (eval != null) {
                appendLine(getString(R.string.train_result_test_acc, pct(eval.accuracy)))
                appendLine(getString(R.string.train_result_macro_f1, pct(eval.macroF1)))
                appendLine()
                appendLine(getString(R.string.train_result_per_class))
                eval.perClass.forEach { m ->
                    appendLine(
                        "  ${m.pose.displayName}: acc=${pct(m.accuracy)}  F1=${pct(m.f1)}  n=${m.support}",
                    )
                }
                appendLine()
                appendLine(getString(R.string.train_result_confusion))
                appendLine(eval.formatConfusion())
            } else {
                appendLine(getString(R.string.train_no_test_split))
            }
        }
    }

    private fun pct(v: Float): String = "%.1f%%".format(v * 100f)
}
