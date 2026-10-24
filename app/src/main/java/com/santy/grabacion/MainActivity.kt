package com.santy.grabacion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var qualityGroup: RadioGroup
    private lateinit var videoOptions: View
    private lateinit var timelapseOptions: View
    private lateinit var audioSwitch: MaterialSwitch
    private lateinit var frontSwitch: MaterialSwitch
    private lateinit var intervalInput: EditText
    private lateinit var maxPhotosInput: EditText
    private lateinit var startStopButton: Button

    private val prefs by lazy { getSharedPreferences("ajustes", MODE_PRIVATE) }
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermission(Manifest.permission.CAMERA)) {
                updatePreview()
            } else {
                Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview)
        statusText = findViewById(R.id.status)
        modeGroup = findViewById(R.id.modeGroup)
        qualityGroup = findViewById(R.id.qualityGroup)
        videoOptions = findViewById(R.id.videoOptions)
        timelapseOptions = findViewById(R.id.timelapseOptions)
        audioSwitch = findViewById(R.id.audioSwitch)
        frontSwitch = findViewById(R.id.frontSwitch)
        intervalInput = findViewById(R.id.intervalInput)
        maxPhotosInput = findViewById(R.id.maxPhotosInput)
        startStopButton = findViewById(R.id.startStopButton)

        loadSettings()
        modeGroup.setOnCheckedChangeListener { _, _ -> updateModeOptions() }
        frontSwitch.setOnCheckedChangeListener { _, _ -> updatePreview() }
        startStopButton.setOnClickListener {
            if (CameraService.status.value == CaptureStatus.Idle) startCapture() else stopCapture()
        }
        updateModeOptions()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CameraService.status.collect { render(it) }
            }
        }

        requestMissingPermissions()
    }

    override fun onStop() {
        saveSettings()
        super.onStop()
    }

    private fun render(status: CaptureStatus) {
        val running = status != CaptureStatus.Idle
        startStopButton.setText(if (running) R.string.stop else R.string.start)
        listOf(modeGroup, qualityGroup, audioSwitch, frontSwitch, intervalInput, maxPhotosInput)
            .forEach { it.isEnabled = !running }
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = !running
        for (i in 0 until qualityGroup.childCount) qualityGroup.getChildAt(i).isEnabled = !running

        statusText.visibility = if (running) View.VISIBLE else View.GONE
        previewView.visibility = if (running) View.INVISIBLE else View.VISIBLE
        statusText.text = when (status) {
            is CaptureStatus.Recording -> getString(R.string.status_video)
            is CaptureStatus.Timelapse -> getString(R.string.status_timelapse, status.photos, status.intervalSeconds)
            CaptureStatus.Idle -> getString(R.string.status_idle)
        }
        updatePreview()
    }

    private fun updateModeOptions() {
        val timelapse = modeGroup.checkedRadioButtonId == R.id.modeTimelapse
        videoOptions.visibility = if (timelapse) View.GONE else View.VISIBLE
        timelapseOptions.visibility = if (timelapse) View.VISIBLE else View.GONE
    }

    /**
     * Muestra la vista previa solo mientras no hay captura: cuando el servicio está grabando
     * él es dueño de la cámara y la pantalla no debe quitársela.
     */
    private fun updatePreview() {
        if (!hasPermission(Manifest.permission.CAMERA)) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            preview?.let { provider.unbind(it) }
            preview = null
            if (CameraService.status.value != CaptureStatus.Idle) return@addListener
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@addListener

            val newPreview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                provider.bindToLifecycle(this, selectedCamera(), newPreview)
                preview = newPreview
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo abrir la cámara: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun selectedCamera() =
        if (frontSwitch.isChecked) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun startCapture() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            requestMissingPermissions()
            return
        }
        saveSettings()
        // Liberar la cámara de la vista previa antes de que el servicio la tome.
        preview?.let { cameraProvider?.unbind(it) }
        preview = null

        val timelapse = modeGroup.checkedRadioButtonId == R.id.modeTimelapse
        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_START
            putExtra(CameraService.EXTRA_MODE, if (timelapse) CaptureMode.TIMELAPSE.name else CaptureMode.VIDEO.name)
            putExtra(CameraService.EXTRA_FRONT, frontSwitch.isChecked)
            putExtra(CameraService.EXTRA_AUDIO, audioSwitch.isChecked)
            putExtra(CameraService.EXTRA_QUALITY, selectedQualityName())
            putExtra(CameraService.EXTRA_INTERVAL, intervalInput.text.toString().toIntOrNull() ?: 5)
            putExtra(CameraService.EXTRA_MAX_PHOTOS, maxPhotosInput.text.toString().toIntOrNull() ?: 0)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopCapture() {
        startService(Intent(this, CameraService::class.java).setAction(CameraService.ACTION_STOP))
    }

    private fun selectedQualityName() = when (qualityGroup.checkedRadioButtonId) {
        R.id.qualityHd -> "HD"
        R.id.qualityUhd -> "UHD"
        else -> "FHD"
    }

    private fun requestMissingPermissions() {
        val wanted = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val missing = wanted.filterNot { hasPermission(it) }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun loadSettings() {
        modeGroup.check(if (prefs.getBoolean("timelapse", false)) R.id.modeTimelapse else R.id.modeVideo)
        qualityGroup.check(
            when (prefs.getString("quality", "FHD")) {
                "HD" -> R.id.qualityHd
                "UHD" -> R.id.qualityUhd
                else -> R.id.qualityFhd
            }
        )
        audioSwitch.isChecked = prefs.getBoolean("audio", true)
        frontSwitch.isChecked = prefs.getBoolean("front", false)
        intervalInput.setText(prefs.getInt("interval", 5).toString())
        maxPhotosInput.setText(prefs.getInt("max_photos", 0).toString())
    }

    private fun saveSettings() {
        prefs.edit()
            .putBoolean("timelapse", modeGroup.checkedRadioButtonId == R.id.modeTimelapse)
            .putString("quality", selectedQualityName())
            .putBoolean("audio", audioSwitch.isChecked)
            .putBoolean("front", frontSwitch.isChecked)
            .putInt("interval", intervalInput.text.toString().toIntOrNull() ?: 5)
            .putInt("max_photos", maxPhotosInput.text.toString().toIntOrNull() ?: 0)
            .apply()
    }
}
