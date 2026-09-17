package edu.iot.phoneangle

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import edu.iot.phoneangle.databinding.ActivityHubBinding

/**
 * Progressive project hub (local-first → federation later).
 */
class HubActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHubBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openOrientation.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.openPoseCollection.setOnClickListener {
            startActivity(Intent(this, PoseCollectionActivity::class.java))
        }
        binding.openPoseTrain.setOnClickListener {
            startActivity(Intent(this, PoseTrainActivity::class.java))
        }
        binding.openPoseInfer.setOnClickListener {
            startActivity(Intent(this, PoseInferActivity::class.java))
        }
        binding.openExperiments.setOnClickListener {
            startActivity(Intent(this, ExperimentsActivity::class.java))
        }
        binding.openTypedWord.setOnClickListener {
            startActivity(Intent(this, TypedWordActivity::class.java))
        }
        binding.openFederation.setOnClickListener {
            startActivity(Intent(this, FederationActivity::class.java))
        }
        binding.openCompare.setOnClickListener {
            startActivity(Intent(this, ModelCompareActivity::class.java))
        }
    }
}
