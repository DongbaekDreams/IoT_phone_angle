package edu.iot.phoneangle

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import edu.iot.phoneangle.databinding.ActivityDevToolsBinding
import edu.iot.phoneangle.federation.FederationTransport
import edu.iot.phoneangle.ml.ModelStore
import edu.iot.phoneangle.store.LocalDatasetStore
import java.util.concurrent.Executors

/**
 * Rarely needed things: connection settings, HUD, experiments, and destructive resets.
 * Routine work lives on Home / Collect / Model / Sync / Results. Typed-word collection is on Collect.
 */
class DevToolsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDevToolsBinding
    private lateinit var settings: ProjectSettings
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = ProjectSettings(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.clientIdInput.setText(settings.clientId)
        binding.serverUrlInput.setText(settings.serverBaseUrl)
        binding.githubRepoInput.setText(settings.githubRepo)
        binding.githubBranchInput.setText(settings.githubBranch)
        binding.githubTokenInput.setText(settings.githubToken)

        binding.saveButton.setOnClickListener {
            save()
            Toast.makeText(this, R.string.dev_saved, Toast.LENGTH_SHORT).show()
        }
        binding.testButton.setOnClickListener { testConnection() }

        binding.openHudButton.setOnClickListener { open(MainActivity::class.java) }
        binding.openExperimentsButton.setOnClickListener { open(ExperimentsActivity::class.java) }
        binding.openHudSettingsButton.setOnClickListener { open(SettingsActivity::class.java) }

        binding.clearTrialsButton.setOnClickListener {
            confirm(R.string.dev_clear_trials_confirm) { LocalDatasetStore(this).deleteAll() }
        }
        binding.clearModelsButton.setOnClickListener {
            confirm(R.string.dev_clear_models_confirm) { ModelStore(this).clearAll() }
        }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun save() {
        settings.clientId = binding.clientIdInput.text.toString().ifBlank {
            ProjectSettings.defaultClientId(settings.clientRole)
        }
        settings.serverBaseUrl = binding.serverUrlInput.text.toString()
        settings.githubRepo = binding.githubRepoInput.text.toString()
        settings.githubBranch = binding.githubBranchInput.text.toString()
        settings.githubToken = binding.githubTokenInput.text.toString()
    }

    private fun testConnection() {
        save()
        binding.testButton.isEnabled = false
        binding.testResult.setText(R.string.dev_testing)
        executor.execute {
            val message = runCatching { FederationTransport.create(settings).ping() }
                .fold(
                    onSuccess = { getString(R.string.dev_test_ok, it) },
                    onFailure = { getString(R.string.dev_test_failed, it.message ?: it.javaClass.simpleName) },
                )
            runOnUiThread {
                binding.testButton.isEnabled = true
                binding.testResult.text = message
            }
        }
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun confirm(messageRes: Int, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(messageRes)
            .setPositiveButton(R.string.dev_confirm_yes) { _, _ ->
                action()
                Toast.makeText(this, R.string.dev_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
