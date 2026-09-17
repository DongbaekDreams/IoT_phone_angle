package edu.iot.phoneangle

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import edu.iot.phoneangle.databinding.ActivityMainBinding
import edu.iot.phoneangle.databinding.ViewAngleRowBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    private var tracker: OrientationTracker? = null
    private val angleDisplay = AngleDisplay()

    private var latestAngles: PhoneAngles? = null
    private var animating = false

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        applySettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettings(this)

        setupRow(binding.pitchRow, R.string.pitch_label, R.string.pitch_hint)
        setupRow(binding.rollRow, R.string.roll_label, R.string.roll_hint)
        setupRow(binding.yawRow, R.string.yaw_label, R.string.yaw_hint)

        tracker = OrientationTracker(this) { angles ->
            latestAngles = angles
            if (!settings.smoothNumbers) {
                runOnUiThread { renderAngles(angles) }
            }
        }

        binding.settingsButton.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        binding.calibrateButton.setOnClickListener {
            tracker?.calibrate()
            angleDisplay.reset()
            updateStatusChip(calibrated = true, available = tracker?.isAvailable == true)
            pulseStatusChip()
            if (settings.hapticFeedback) {
                binding.calibrateButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
            Toast.makeText(this, R.string.calibrated_toast, Toast.LENGTH_SHORT).show()
        }

        binding.resetButton.setOnClickListener {
            tracker?.resetCalibration()
            angleDisplay.reset()
            updateStatusChip(calibrated = false, available = tracker?.isAvailable == true)
            if (settings.hapticFeedback) {
                binding.resetButton.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            Toast.makeText(this, R.string.reset_toast, Toast.LENGTH_SHORT).show()
        }

        applySettings()
    }

    override fun onResume() {
        super.onResume()
        tracker?.start()
        startSmoothLoopIfNeeded()
    }

    override fun onPause() {
        stopSmoothLoop()
        tracker?.stop()
        super.onPause()
    }

    private fun applySettings() {
        updateStatusChip(
            calibrated = tracker?.isCalibrated == true,
            available = tracker?.isAvailable == true,
        )

        binding.detailsSection.isVisible = settings.showDetails

        val hintVis = if (settings.showHints) View.VISIBLE else View.GONE
        binding.pitchRow.hint.visibility = hintVis
        binding.rollRow.hint.visibility = hintVis
        binding.yawRow.hint.visibility = hintVis

        val sparkVis = if (settings.showSparklines) View.VISIBLE else View.GONE
        binding.pitchRow.sparkline.visibility = sparkVis
        binding.rollRow.sparkline.visibility = sparkVis
        binding.yawRow.sparkline.visibility = sparkVis
        if (!settings.showSparklines) {
            binding.pitchRow.sparkline.clear()
            binding.rollRow.sparkline.clear()
            binding.yawRow.sparkline.clear()
        }

        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        angleDisplay.reset()
        latestAngles?.let { renderAngles(it) }
        startSmoothLoopIfNeeded()
    }

    private fun startSmoothLoopIfNeeded() {
        if (!settings.smoothNumbers) {
            stopSmoothLoop()
            return
        }
        if (animating) return
        animating = true
        binding.root.post(smoothTick)
    }

    private fun stopSmoothLoop() {
        animating = false
        binding.root.removeCallbacks(smoothTick)
    }

    private val smoothTick = object : Runnable {
        override fun run() {
            latestAngles?.let { renderAngles(it) }
            if (animating && settings.smoothNumbers) {
                binding.root.postOnAnimation(this)
            }
        }
    }

    private fun setupRow(row: ViewAngleRowBinding, labelRes: Int, hintRes: Int) {
        row.label.setText(labelRes)
        row.hint.setText(hintRes)
    }

    private fun pulseStatusChip() {
        val pulse = AnimationUtils.loadAnimation(this, R.anim.chip_pulse)
        binding.sensorStatus.startAnimation(pulse)
    }

    private fun updateStatusChip(calibrated: Boolean, available: Boolean) {
        when {
            !available -> {
                binding.sensorStatus.text = getString(R.string.sensor_status_missing)
                binding.sensorStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warn))
            }
            calibrated -> {
                binding.sensorStatus.text = getString(R.string.status_calibrated)
                binding.sensorStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
            }
            else -> {
                binding.sensorStatus.text = getString(R.string.sensor_status_ok)
                binding.sensorStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
            }
        }
    }

    private fun renderAngles(angles: PhoneAngles) {
        val (pitch, roll, yaw) = angleDisplay.process(
            angles.pitchDeg,
            angles.rollDeg,
            angles.yawDeg,
            smooth = settings.smoothNumbers,
            deadZone = settings.deadZone,
        )

        binding.pitchRow.value.text = getString(R.string.degrees_format, pitch)
        binding.rollRow.value.text = getString(R.string.degrees_format, roll)
        binding.yawRow.value.text = getString(R.string.degrees_format, yaw)

        if (settings.showSparklines) {
            binding.pitchRow.sparkline.push(pitch)
            binding.rollRow.sparkline.push(roll)
            binding.yawRow.sparkline.push(yaw)
        }

        binding.orientationView.setAngles(pitch, roll, yaw)

        if (settings.showDetails) {
            binding.gravityReadout.text = getString(
                R.string.gravity_format,
                angles.gravityX,
                angles.gravityY,
                angles.gravityZ,
            )
        }

        if (angles.isCalibrated &&
            binding.sensorStatus.text != getString(R.string.status_calibrated)
        ) {
            updateStatusChip(calibrated = true, available = true)
        }
    }
}
