package edu.iot.phoneangle

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import edu.iot.phoneangle.databinding.ActivityShellBinding
import edu.iot.phoneangle.databinding.DialogIdentitySlotsBinding
import edu.iot.phoneangle.federation.FederationTransport
import edu.iot.phoneangle.ml.ModelSlot
import edu.iot.phoneangle.ml.ModelStore
import edu.iot.phoneangle.ui.CollectFragment
import edu.iot.phoneangle.ui.CompareFragment
import edu.iot.phoneangle.ui.FederateFragment
import edu.iot.phoneangle.ui.HomeFragment
import edu.iot.phoneangle.ui.ModelFragment
import edu.iot.phoneangle.ui.Refreshable
import edu.iot.phoneangle.ui.ShellHost
import java.util.concurrent.Executors

/**
 * App shell: Home checklist + Collect / Model / Sync / Results.
 * Rare tools live under the overflow menu.
 */
class MainShellActivity : AppCompatActivity(), ShellHost {

    private lateinit var binding: ActivityShellBinding
    private lateinit var settings: ProjectSettings
    private lateinit var registry: IdentityRegistry
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShellBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = ProjectSettings(this)
        registry = IdentityRegistry(this)

        // Locked phones always re-apply their bound identity (in case prefs drifted).
        registry.myBinding()?.let { settings.applyIdentity(it.slot.nationality, it.slot.role) }

        binding.toolbar.inflateMenu(R.menu.menu_shell)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_switch_client -> { openIdentityDialog(); true }
                R.id.action_extra_credit -> { startActivity(Intent(this, ExtraCreditActivity::class.java)); true }
                R.id.action_dev_tools -> { openDevTools(); true }
                else -> false
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        binding.chipClient.setOnClickListener { openIdentityDialog() }
        binding.chipMode.setOnClickListener { openTab(R.id.nav_sync) }
        binding.chipRound.setOnClickListener { openTab(R.id.nav_sync) }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        }
        refreshChips()
    }

    override fun onResume() {
        super.onResume()
        refreshChips()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun refreshChips() {
        val global = ModelStore(this).load(ModelSlot.GLOBAL)
        val label = settings.displayLabel()
        binding.chipClient.text = if (registry.isLocked) {
            getString(R.string.chip_identity_locked, label)
        } else {
            getString(R.string.chip_unclaimed)
        }
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

    override fun setBottomNavVisible(visible: Boolean) {
        binding.bottomNav.isVisible = visible
    }

    private fun showTab(itemId: Int) {
        val fragment: Fragment = when (itemId) {
            R.id.nav_home -> HomeFragment()
            R.id.nav_model -> ModelFragment()
            R.id.nav_sync -> FederateFragment()
            R.id.nav_results -> CompareFragment()
            R.id.nav_collect -> CollectFragment()
            else -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    /**
     * Four slots (🇨🇦/🇵🇪 × Class/Personal). Claim one to bind this phone forever.
     * Filled slots stay filled; empty ones remain available for the other phones.
     */
    override fun openIdentityDialog() {
        val form = DialogIdentitySlotsBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_slots_title)
            .setView(form.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun render(statuses: Map<IdentitySlot, SlotStatus>, syncHint: String, allowClaim: Boolean) {
            form.slotsSyncHint.text = syncHint
            form.slotsList.removeAllViews()
            form.slotsProgress.isVisible = false
            for (slot in IdentitySlot.ALL) {
                val status = statuses[slot] ?: SlotStatus.EMPTY
                val button = LayoutInflater.from(this)
                    .inflate(R.layout.item_identity_slot, form.slotsList, false) as MaterialButton
                button.text = when (status) {
                    SlotStatus.EMPTY -> getString(R.string.slot_empty, slot.displayName)
                    SlotStatus.MINE -> getString(R.string.slot_mine, slot.displayName)
                    SlotStatus.TAKEN -> getString(R.string.slot_taken, slot.displayName)
                }
                button.isEnabled = allowClaim && status == SlotStatus.EMPTY && !registry.isLocked
                button.setOnClickListener {
                    confirmClaim(slot, dialog)
                }
                form.slotsList.addView(button)
            }
            form.publishBindingButton.isVisible = registry.isLocked
            form.publishBindingButton.setOnClickListener { publishBinding(dialog) }
            if (registry.isLocked) {
                form.slotsMessage.setText(R.string.dialog_slots_locked_message)
            }
        }

        form.slotsProgress.isVisible = true
        form.slotsMessage.setText(R.string.dialog_slots_message)
        dialog.show()

        executor.execute {
            val remote = runCatching {
                FederationTransport.create(settings).fetchBindings()
            }
            val statuses = registry.statuses(remote.getOrDefault(emptyList()))
            val hint = when {
                registry.isLocked -> getString(R.string.slots_hint_locked)
                remote.isSuccess -> getString(R.string.slots_hint_synced, remote.getOrNull()!!.size)
                else -> getString(R.string.slots_hint_offline)
            }
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                render(statuses, hint, allowClaim = !registry.isLocked)
            }
        }
    }

    private fun confirmClaim(slot: IdentitySlot, parent: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle(R.string.slot_claim_title)
            .setMessage(getString(R.string.slot_claim_message, slot.displayName))
            .setPositiveButton(R.string.slot_claim_confirm) { _, _ ->
                parent.dismiss()
                doClaim(slot)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doClaim(slot: IdentitySlot) {
        Toast.makeText(this, R.string.slot_claiming, Toast.LENGTH_SHORT).show()
        executor.execute {
            val result = runCatching {
                val transport = runCatching { FederationTransport.create(settings) }.getOrNull()
                registry.claim(slot, transport)
            }
            runOnUiThread {
                result.onSuccess { (binding, note) ->
                    val msg = when (note) {
                        null -> getString(R.string.slot_claimed, binding.slot.displayName)
                        "offline" -> getString(R.string.slot_claimed_offline, binding.slot.displayName)
                        else -> getString(R.string.slot_claimed_offline, binding.slot.displayName)
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    refreshChips()
                    (supportFragmentManager.findFragmentById(R.id.fragmentContainer) as? Refreshable)?.refresh()
                }.onFailure { e ->
                    Toast.makeText(this, e.message ?: e.javaClass.simpleName, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun publishBinding(dialog: AlertDialog) {
        executor.execute {
            val result = runCatching {
                registry.publish(FederationTransport.create(settings))
            }
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, R.string.slot_published, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }.onFailure { e ->
                    Toast.makeText(this, e.message ?: e.javaClass.simpleName, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
