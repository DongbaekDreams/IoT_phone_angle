package edu.iot.phoneangle

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import edu.iot.phoneangle.databinding.ActivityShellBinding
import edu.iot.phoneangle.ml.ModelSlot
import edu.iot.phoneangle.ml.ModelStore
import edu.iot.phoneangle.ui.CollectFragment
import edu.iot.phoneangle.ui.CompareFragment
import edu.iot.phoneangle.ui.FederateFragment
import edu.iot.phoneangle.ui.ModelFragment
import edu.iot.phoneangle.ui.Refreshable
import edu.iot.phoneangle.ui.ShellHost

/**
 * App shell: bottom navigation (Collect, Model, Federate, Compare) plus a persistent
 * status row (client, transport, round). Rare tools live under the overflow menu.
 */
class MainShellActivity : AppCompatActivity(), ShellHost {

    private lateinit var binding: ActivityShellBinding
    private lateinit var settings: ProjectSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShellBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = ProjectSettings(this)

        binding.toolbar.inflateMenu(R.menu.menu_shell)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_switch_client -> { showClientDialog(); true }
                R.id.action_dev_tools -> { openDevTools(); true }
                else -> false
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        binding.chipClient.setOnClickListener { showClientDialog() }
        binding.chipMode.setOnClickListener { openTab(R.id.nav_federate) }
        binding.chipRound.setOnClickListener { openTab(R.id.nav_federate) }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_collect
        }
        refreshChips()
    }

    override fun onResume() {
        super.onResume()
        refreshChips()
    }

    override fun refreshChips() {
        val global = ModelStore(this).load(ModelSlot.GLOBAL)
        binding.chipClient.text = settings.displayLabel()
        binding.chipMode.text = getString(R.string.chip_mode, settings.transportMode.label)
        binding.chipRound.text = if (global == null) {
            getString(R.string.chip_round_none)
        } else {
            getString(R.string.chip_round, global.round)
        }
    }

    override fun openTab(menuItemId: Int) {
        binding.bottomNav.selectedItemId = menuItemId
    }

    override fun openDevTools() {
        startActivity(Intent(this, DevToolsActivity::class.java))
    }

    private fun showTab(itemId: Int) {
        val fragment: Fragment = when (itemId) {
            R.id.nav_model -> ModelFragment()
            R.id.nav_federate -> FederateFragment()
            R.id.nav_compare -> CompareFragment()
            else -> CollectFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun showClientDialog() {
        val presets = PhonePreset.entries
        val labels = presets.map { it.displayName }.toTypedArray()
        var picked = presets.indexOfFirst { it.clientId == settings.clientId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_phone_title)
            .setMessage(R.string.dialog_phone_message)
            .setSingleChoiceItems(labels, picked) { _, which -> picked = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.applyPreset(presets[picked])
                refreshChips()
                (supportFragmentManager.findFragmentById(R.id.fragmentContainer) as? Refreshable)?.refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
