package edu.iot.phoneangle

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import edu.iot.phoneangle.data.PhonePose
import edu.iot.phoneangle.databinding.ActivityPoseInferBinding
import edu.iot.phoneangle.ml.InferenceEngine
import edu.iot.phoneangle.ml.ModelStore
import edu.iot.phoneangle.sensors.SensorCollector

/**
 * Section 1 — live pose inference from the saved local model.
 */
class PoseInferActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPoseInferBinding
    private lateinit var collector: SensorCollector
    private lateinit var engine: InferenceEngine

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            renderPrediction()
            handler.postDelayed(this, 150L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPoseInferBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val bundle = ModelStore(this).loadLocalPose()
        engine = InferenceEngine(bundle)
        collector = SensorCollector(this) { sample -> engine.offer(sample) }

        if (bundle == null) {
            binding.poseLabel.text = getString(R.string.infer_no_model)
            binding.confidenceLabel.text = ""
            binding.probsText.text = getString(R.string.infer_train_first)
            binding.modelMeta.text = ""
        } else {
            binding.modelMeta.text = getString(
                R.string.infer_model_meta,
                bundle.sensorConfig.displayName,
                bundle.trainSampleCount,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (engine.hasModel) {
            collector.start()
            handler.post(refresh)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        collector.stop()
        engine.clearBuffer()
        super.onPause()
    }

    private fun renderPrediction() {
        val pred = engine.predict()
        if (pred == null) {
            binding.poseLabel.text = getString(R.string.infer_warming)
            binding.confidenceLabel.text = ""
            return
        }
        binding.poseLabel.text = pred.pose.displayName
        binding.confidenceLabel.text = getString(
            R.string.infer_confidence,
            pred.confidence * 100f,
        )
        binding.probsText.text = PhonePose.entries.joinToString("\n") { pose ->
            val p = pred.probabilities[pose] ?: 0f
            val bar = "█".repeat((p * 12).toInt().coerceIn(0, 12))
            "${pose.displayName.padEnd(16)} ${"%.0f".format(p * 100)}%  $bar"
        }
    }
}
