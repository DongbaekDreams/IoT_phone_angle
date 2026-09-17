package edu.iot.phoneangle

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import edu.iot.phoneangle.data.TrialTask
import edu.iot.phoneangle.databinding.ActivityFederationBinding
import edu.iot.phoneangle.federation.FederationClient
import edu.iot.phoneangle.ml.Evaluator
import edu.iot.phoneangle.ml.ModelStore
import edu.iot.phoneangle.ml.PoseModelBundle
import edu.iot.phoneangle.ml.Preprocessor
import edu.iot.phoneangle.store.LocalDatasetStore
import java.util.concurrent.Executors

/**
 * Section 5 UI — submit local update, aggregate, pull global (weights only).
 */
class FederationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFederationBinding
    private lateinit var settings: ProjectSettings
    private lateinit var modelStore: ModelStore
    private lateinit var dataset: LocalDatasetStore
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFederationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = ProjectSettings(this)
        modelStore = ModelStore(this)
        dataset = LocalDatasetStore(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.serverUrl.setText(settings.serverBaseUrl)

        binding.saveUrlButton.setOnClickListener {
            settings.serverBaseUrl = binding.serverUrl.text?.toString().orEmpty()
            Toast.makeText(this, R.string.fed_url_saved, Toast.LENGTH_SHORT).show()
        }
        binding.healthButton.setOnClickListener { runNet { client().health() } }
        binding.submitButton.setOnClickListener { submitUpdate() }
        binding.aggregateButton.setOnClickListener { aggregate() }
        binding.fetchGlobalButton.setOnClickListener { fetchGlobal() }
        binding.resetServerButton.setOnClickListener { runNet { client().reset(); "Server reset" } }

        refreshLocalLine()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun client() = FederationClient(settings.serverBaseUrl)

    private fun refreshLocalLine() {
        val local = modelStore.loadLocalPose()
        binding.localLine.text = if (local == null) {
            getString(R.string.fed_no_local)
        } else {
            getString(
                R.string.fed_local_line,
                settings.clientRole.label,
                local.trainSampleCount,
                local.sensorConfig.displayName,
            )
        }
    }

    private fun submitUpdate() {
        val local = modelStore.loadLocalPose()
        if (local == null) {
            Toast.makeText(this, R.string.fed_no_local, Toast.LENGTH_SHORT).show()
            return
        }
        settings.serverBaseUrl = binding.serverUrl.text?.toString().orEmpty()
        runNet {
            val result = client().submitUpdate(
                clientId = settings.clientId,
                sampleCount = local.trainSampleCount.coerceAtLeast(1),
                model = local.model,
                sensorConfig = local.sensorConfig,
            )
            "Submitted ${settings.clientId}. Pending clients: ${result.pendingClients}"
        }
    }

    private fun aggregate() {
        settings.serverBaseUrl = binding.serverUrl.text?.toString().orEmpty()
        runNet {
            val global = client().aggregate()
            val localMeta = modelStore.loadLocalPose()
            val bundle = PoseModelBundle(
                sensorConfig = localMeta?.sensorConfig ?: edu.iot.phoneangle.ml.SensorConfig.ALL,
                model = global,
                trainedAtEpochMs = System.currentTimeMillis(),
                trainAccuracy = 0f,
                trainSampleCount = localMeta?.trainSampleCount ?: 0,
                testAccuracy = evalOnLocalData(global, localMeta),
                epochs = 0,
            )
            modelStore.saveGlobalPose(bundle)
            "Aggregated global model saved. testAcc=${bundle.testAccuracy?.let { "%.1f%%".format(it * 100) } ?: "—"}"
        }
    }

    private fun fetchGlobal() {
        settings.serverBaseUrl = binding.serverUrl.text?.toString().orEmpty()
        runNet {
            val global = client().fetchGlobal()
                ?: return@runNet "No global model on server yet"
            val localMeta = modelStore.loadLocalPose()
            val bundle = PoseModelBundle(
                sensorConfig = localMeta?.sensorConfig ?: edu.iot.phoneangle.ml.SensorConfig.ALL,
                model = global,
                trainedAtEpochMs = System.currentTimeMillis(),
                trainAccuracy = 0f,
                trainSampleCount = 0,
                testAccuracy = evalOnLocalData(global, localMeta),
            )
            modelStore.saveGlobalPose(bundle)
            "Fetched global model. testAcc=${bundle.testAccuracy?.let { "%.1f%%".format(it * 100) } ?: "—"}"
        }
    }

    private fun evalOnLocalData(
        model: edu.iot.phoneangle.ml.SoftmaxClassifier,
        meta: PoseModelBundle?,
    ): Float? {
        val config = meta?.sensorConfig ?: return null
        val examples = Preprocessor(config).examplesFromTrials(dataset.loadAll(TrialTask.POSE))
        if (examples.isEmpty()) return null
        return Evaluator().evaluate(model, examples).accuracy
    }

    private fun runNet(block: () -> String) {
        binding.statusText.text = getString(R.string.fed_working)
        executor.execute {
            val msg = try {
                block()
            } catch (e: Exception) {
                getString(R.string.fed_error, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                binding.statusText.text = msg
                refreshLocalLine()
            }
        }
    }
}
