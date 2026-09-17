package edu.iot.phoneangle

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import edu.iot.phoneangle.databinding.ActivitySettingsBinding
import edu.iot.phoneangle.databinding.ViewSettingSwitchBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettings(this)

        binding.settingsToolbar.setNavigationOnClickListener { finish() }

        bindSwitch(
            binding.settingSmooth,
            R.string.setting_smooth_title,
            R.string.setting_smooth_summary,
            settings.smoothNumbers,
        ) { settings.smoothNumbers = it }

        bindSwitch(
            binding.settingDeadZone,
            R.string.setting_dead_zone_title,
            R.string.setting_dead_zone_summary,
            settings.deadZone,
        ) { settings.deadZone = it }

        bindSwitch(
            binding.settingSparklines,
            R.string.setting_sparklines_title,
            R.string.setting_sparklines_summary,
            settings.showSparklines,
        ) { settings.showSparklines = it }

        bindSwitch(
            binding.settingHints,
            R.string.setting_hints_title,
            R.string.setting_hints_summary,
            settings.showHints,
        ) { settings.showHints = it }

        bindSwitch(
            binding.settingDetails,
            R.string.setting_details_title,
            R.string.setting_details_summary,
            settings.showDetails,
        ) { settings.showDetails = it }

        bindSwitch(
            binding.settingHaptic,
            R.string.setting_haptic_title,
            R.string.setting_haptic_summary,
            settings.hapticFeedback,
        ) { settings.hapticFeedback = it }

        bindSwitch(
            binding.settingKeepAwake,
            R.string.setting_keep_awake_title,
            R.string.setting_keep_awake_summary,
            settings.keepScreenOn,
        ) { settings.keepScreenOn = it }
    }

    private fun bindSwitch(
        row: ViewSettingSwitchBinding,
        titleRes: Int,
        summaryRes: Int,
        initial: Boolean,
        onChanged: (Boolean) -> Unit,
    ) {
        row.settingTitle.setText(titleRes)
        row.settingSummary.setText(summaryRes)
        row.settingSwitch.isChecked = initial
        row.settingSwitch.setOnCheckedChangeListener { _, checked ->
            onChanged(checked)
        }
    }
}
