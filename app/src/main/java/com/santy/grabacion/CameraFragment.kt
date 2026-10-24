package com.santy.grabacion

import android.Manifest
import android.content.Context
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

class CameraFragment : Fragment(R.layout.fragment_camera) {

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

    private val prefs by lazy { requireContext().getSharedPreferences("ajustes", Context.MODE_PRIVATE) }
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermission(Manifest.permission.CAMERA)) {
                updatePreview()
            } else {
                Toast.makeText(requireContext(), R.string.permissions_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        previewView = view.findViewById(R.id.preview)
        statusText = view.findViewById(R.id.status)
        modeGroup = view.findViewById(R.id.modeGroup)
        qualityGroup = view.findViewById(R.id.qualityGroup)
        videoOptions = view.findViewById(R.id.videoOptions)
        timelapseOptions = view.findViewById(R.id.timelapseOptions)
        audioSwitch = view.findViewById(R.id.audioSwitch)
        frontSwitch = view.findViewById(R.id.frontSwitch)
        intervalInput = view.findViewById(R.id.intervalInput)
        maxPhotosInput = view.findViewById(R.id.maxPhotosInput)
        startStopButton = view.findViewById(R.id.startStopButton)

        loadSettings()
        modeGroup.setOnCheckedChangeListener { _, _ -> updateModeOptions() }
        frontSwitch.setOnCheckedChangeListener { _, _ -> updatePreview() }
        startStopButton.setOnClickListener {
            if (CameraService.status.value == CaptureStatus.Idle) startCapture() else stopCapture()
        }
        updateModeOptions()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CameraService.status.collect { render(it) }
            }
        }

        if (savedInstanceState == null) requestMissingPermissions()
    }

    override fun onStop() {
        saveSettings()
        super.onStop()
    }

    override fun onDestroyView() {
        preview?.let { cameraProvider?.unbind(it) }
        preview = null
        super.onDestroyView()
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
        val context = context ?: return
        if (!hasPermission(Manifest.permission.CAMERA)) return
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            preview?.let { provider.unbind(it) }
            preview = null
            if (view == null) return@addListener
            if (CameraService.status.value != CaptureStatus.Idle) return@addListener
            val owner = viewLifecycleOwner
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@addListener

            val newPreview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                provider.bindToLifecycle(owner, selectedCamera(), newPreview)
                preview = newPreview
            } catch (e: Exception) {
                Toast.makeText(context, "No se pudo abrir la cámara: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(context))
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

        val context = requireContext()
        val timelapse = modeGroup.checkedRadioButtonId == R.id.modeTimelapse
        val intent = Intent(context, CameraService::class.java).apply {
            action = CameraService.ACTION_START
            putExtra(CameraService.EXTRA_MODE, if (timelapse) CaptureMode.TIMELAPSE.name else CaptureMode.VIDEO.name)
            putExtra(CameraService.EXTRA_FRONT, frontSwitch.isChecked)
            putExtra(CameraService.EXTRA_AUDIO, audioSwitch.isChecked)
            putExtra(CameraService.EXTRA_QUALITY, selectedQualityName())
            putExtra(CameraService.EXTRA_INTERVAL, intervalInput.text.toString().toIntOrNull() ?: 5)
            putExtra(CameraService.EXTRA_MAX_PHOTOS, maxPhotosInput.text.toString().toIntOrNull() ?: 0)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopCapture() {
        val context = requireContext()
        context.startService(Intent(context, CameraService::class.java).setAction(CameraService.ACTION_STOP))
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
        wanted += mediaPermissions()
        val missing = wanted.filterNot { hasPermission(it) }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED

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

/** Permisos para leer fotos y videos de la galería según la versión de Android. */
fun mediaPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
