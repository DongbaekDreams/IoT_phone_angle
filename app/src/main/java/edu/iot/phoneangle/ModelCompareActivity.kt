package edu.iot.phoneangle

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.databinding.ActivityModelCompareBinding
import edu.iot.phoneangle.ml.Evaluator
import edu.iot.phoneangle.ml.ModelStore
import edu.iot.phoneangle.ml.PoseModelBundle
import edu.iot.phoneangle.ml.Preprocessor
import edu.iot.phoneangle.store.LocalDatasetStore
import java.util.concurrent.Executors

/**
 * Side-by-side Local / Peer / Global evaluation on the same local pose trials.
 */
class ModelCompareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelCompareBinding
    private lateinit var modelStore: ModelStore
    private lateinit var dataset: LocalDatasetStore
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelCompareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelStore = ModelStore(this)
        dataset = LocalDatasetStore(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.refreshButton.setOnClickListener { refresh() }
        binding.copyLocalAsPeerButton.setOnClickListener {
            val local = modelStore.loadLocalPose()
            if (local != null) {
                modelStore.savePeerPose(local.copy(trainedAtEpochMs = System.currentTimeMillis()))
            }
            refresh()
        }

        refresh()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        binding.resultsText.text = getString(R.string.compare_working)
        executor.execute {
            val text = buildCompareText()
            runOnUiThread { binding.resultsText.text = text }
        }
    }

    private fun buildCompareText(): String {
        val local = modelStore.loadLocalPose()
        val peer = modelStore.loadPeerPose()
        val global = modelStore.loadGlobalPose()
        val trials = dataset.loadAll(TrialTask.POSE)
        if (trials.isEmpty()) return getString(R.string.compare_no_data)

        return buildString {
            appendLine(getString(R.string.compare_header, trials.size))
            appendLine()
            appendLine(evalBlock("Local", local, trials))
            appendLine()
            appendLine(evalBlock("Peer (other client)", peer, trials))
            appendLine()
            appendLine(evalBlock("Federated global", global, trials))
        }
    }

    private fun evalBlock(title: String, bundle: PoseModelBundle?, trials: List<edu.iot.phoneangle.data.Trial>): String {
        if (bundle == null) return "$title: (none saved)"
        val examples = Preprocessor(bundle.sensorConfig).examplesFromTrials(trials)
        if (examples.isEmpty()) return "$title: no usable examples"
        val report = Evaluator().evaluate(bundle.model, examples)
        return buildString {
            appendLine("$title · ${bundle.sensorConfig.displayName}")
            appendLine("  accuracy=${"%.1f".format(report.accuracy * 100)}%  macro-F1=${"%.1f".format(report.macroF1 * 100)}%  n=${report.sampleCount}")
            report.perClass.forEach { m ->
                appendLine("  ${m.pose.displayName}: F1=${"%.2f".format(m.f1)} n=${m.support}")
            }
        }
    }
}
