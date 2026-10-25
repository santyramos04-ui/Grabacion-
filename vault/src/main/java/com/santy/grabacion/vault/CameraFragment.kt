package com.santy.grabacion.vault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.santy.grabacion.core.formatDuration
import com.santy.grabacion.recorder.Bitrate
import com.santy.grabacion.recorder.CaptureOrigin
import com.santy.grabacion.recorder.CaptureStatus
import com.santy.grabacion.recorder.RecorderCapabilities
import com.santy.grabacion.recorder.RecordingController
import com.santy.grabacion.recorder.RecordingSettings
import com.santy.grabacion.recorder.RecordingSettingsStore
import com.santy.grabacion.recorder.VideoFormat
import com.santy.grabacion.recorder.VideoQuality
import com.santy.grabacion.recorder.isPaused
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Cámara del vault: vista previa, ajustes de grabación (guardados entre sesiones y limitados a
 * lo que la cámara admite) y controles para iniciar, pausar, reanudar y detener.
 */
class CameraFragment : Fragment(R.layout.fragment_camera) {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var durationChips: ChipGroup
    private lateinit var formatGroup: MaterialButtonToggleGroup
    private lateinit var formatHint: TextView
    private lateinit var qualityGroup: MaterialButtonToggleGroup
    private lateinit var fpsGroup: MaterialButtonToggleGroup
    private lateinit var bitrateGroup: MaterialButtonToggleGroup
    private lateinit var videoOptions: View
    private lateinit var timelapseOptions: View
    private lateinit var audioSwitch: MaterialSwitch
    private lateinit var frontSwitch: MaterialSwitch
    private lateinit var stabilizationSwitch: MaterialSwitch
    private lateinit var torchSwitch: MaterialSwitch
    private lateinit var intervalInput: EditText
    private lateinit var maxPhotosInput: EditText
    private lateinit var startStopButton: MaterialButton
    private lateinit var pauseButton: MaterialButton

    /** Preferencias propias del vault (modo y timelapse); las de video viven en el grabador. */
    private val prefs by lazy { requireContext().getSharedPreferences("ajustes", Context.MODE_PRIVATE) }
    private var settings = RecordingSettings()
    private var capabilities: RecorderCapabilities? = null
    private var capabilitiesJob: Job? = null
    /** Evita guardar mientras se reconstruyen los selectores desde código. */
    private var binding = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermission(Manifest.permission.CAMERA)) {
                updatePreview()
                refreshCapabilities()
            } else {
                Toast.makeText(requireContext(), R.string.permissions_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        previewView = view.findViewById(R.id.preview)
        statusText = view.findViewById(R.id.status)
        modeGroup = view.findViewById(R.id.modeGroup)
        durationChips = view.findViewById(R.id.durationChips)
        formatGroup = view.findViewById(R.id.formatGroup)
        formatHint = view.findViewById(R.id.formatHint)
        qualityGroup = view.findViewById(R.id.qualityGroup)
        fpsGroup = view.findViewById(R.id.fpsGroup)
        bitrateGroup = view.findViewById(R.id.bitrateGroup)
        videoOptions = view.findViewById(R.id.videoOptions)
        timelapseOptions = view.findViewById(R.id.timelapseOptions)
        audioSwitch = view.findViewById(R.id.audioSwitch)
        frontSwitch = view.findViewById(R.id.frontSwitch)
        stabilizationSwitch = view.findViewById(R.id.stabilizationSwitch)
        torchSwitch = view.findViewById(R.id.torchSwitch)
        intervalInput = view.findViewById(R.id.intervalInput)
        maxPhotosInput = view.findViewById(R.id.maxPhotosInput)
        startStopButton = view.findViewById(R.id.startStopButton)
        pauseButton = view.findViewById(R.id.pauseButton)

        settings = RecordingSettingsStore.load(requireContext())
        loadVaultSettings()
        bindStaticControls()
        bindDurationChips()
        bindBitrate()
        bindFormat()
        refreshCapabilities()

        modeGroup.addOnButtonCheckedListener { _, _, _ -> updateModeOptions() }
        startStopButton.setOnClickListener {
            if (RecordingController.isActive) RecordingController.stop(requireContext()) else startCapture()
        }
        pauseButton.setOnClickListener {
            val context = requireContext()
            if (RecordingController.status.value.isPaused) RecordingController.resume(context)
            else RecordingController.pause(context)
        }
        updateModeOptions()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecordingController.status.collect { render(it) }
            }
        }

        if (savedInstanceState == null) requestMissingPermissions()
    }

    override fun onStop() {
        saveVaultSettings()
        super.onStop()
    }

    override fun onDestroyView() {
        preview?.let { cameraProvider?.unbind(it) }
        preview = null
        super.onDestroyView()
    }

    private fun update(change: RecordingSettings.() -> RecordingSettings) {
        if (binding) return
        settings = settings.change()
        RecordingSettingsStore.save(requireContext(), settings)
    }

    // ---------- Ajustes ----------

    private fun bindStaticControls() {
        audioSwitch.isChecked = settings.audio
        frontSwitch.isChecked = settings.frontCamera
        stabilizationSwitch.isChecked = settings.stabilization
        torchSwitch.isChecked = settings.torch
        audioSwitch.setOnCheckedChangeListener { _, checked -> update { copy(audio = checked) } }
        stabilizationSwitch.setOnCheckedChangeListener { _, checked -> update { copy(stabilization = checked) } }
        torchSwitch.setOnCheckedChangeListener { _, checked -> update { copy(torch = checked) } }
        frontSwitch.setOnCheckedChangeListener { _, checked ->
            update { copy(frontCamera = checked) }
            updatePreview()
            refreshCapabilities()
        }
    }

    private fun bindDurationChips() {
        durationChips.removeAllViews()
        fun chip(text: String, minutes: Int?) = (LayoutInflater.from(requireContext())
            .inflate(R.layout.item_duration_chip, durationChips, false) as Chip).apply {
            id = View.generateViewId()
            this.text = text
            tag = minutes
            durationChips.addView(this)
        }
        val current = settings.durationMinutes
        chip(getString(R.string.duration_unlimited), 0).isChecked = current == 0
        RecordingSettings.PRESET_MINUTES.forEach { minutes ->
            chip(getString(R.string.duration_minutes, minutes), minutes).isChecked = current == minutes
        }
        val isCustom = current != 0 && current !in RecordingSettings.PRESET_MINUTES
        val custom = chip(
            if (isCustom) getString(R.string.duration_minutes, current) else getString(R.string.duration_custom),
            null,
        )
        custom.isChecked = isCustom
        // La duración personalizada se pide al tocar su chip, aunque ya esté elegida.
        custom.setOnClickListener { askCustomDuration() }
        durationChips.setOnCheckedStateChangeListener { group, ids ->
            val minutes = ids.firstOrNull()?.let { group.findViewById<Chip>(it).tag as Int? } ?: return@setOnCheckedStateChangeListener
            update { copy(durationMinutes = minutes) }
        }
    }

    private fun askCustomDuration() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.duration_custom_hint)
            settings.durationMinutes.takeIf { it > 0 }?.let { setText(it.toString()) }
        }
        val container = FrameLayout(requireContext()).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.duration_custom_title)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val minutes = input.text.toString().toIntOrNull()?.coerceIn(1, 600)
                if (minutes != null) update { copy(durationMinutes = minutes) }
                bindDurationChips()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> bindDurationChips() }
            .setOnCancelListener { bindDurationChips() }
            .show()
    }

    private fun bindFormat() {
        fillSegments(
            formatGroup,
            listOf(VideoFormat.MP4_SDR to getString(R.string.format_sdr), VideoFormat.MP4_HDR to getString(R.string.format_hdr)),
            settings.format,
        ) { format ->
            update { copy(format = format) }
            refreshCapabilities()
        }
    }

    private fun bindBitrate() {
        fillSegments(
            bitrateGroup,
            listOf(
                Bitrate.AUTO to getString(R.string.bitrate_auto),
                Bitrate.HIGH to getString(R.string.bitrate_high),
                Bitrate.MEDIUM to getString(R.string.bitrate_medium),
                Bitrate.LOW to getString(R.string.bitrate_low),
            ),
            settings.bitrate,
        ) { update { copy(bitrate = it) } }
    }

    /** Consulta a la cámara qué admite y ajusta calidades, fps, HDR, estabilización y linterna. */
    private fun refreshCapabilities() {
        if (!hasPermission(Manifest.permission.CAMERA)) return
        capabilitiesJob?.cancel()
        capabilitiesJob = viewLifecycleOwner.lifecycleScope.launch {
            val caps = try {
                RecorderCapabilities.query(requireContext(), settings.frontCamera, settings.format)
            } catch (e: Exception) {
                return@launch
            }
            capabilities = caps
            applyCapabilities(caps)
        }
    }

    private fun applyCapabilities(caps: RecorderCapabilities) {
        frontSwitch.isEnabled = caps.hasFrontCamera && !RecordingController.isActive
        // HDR: solo seleccionable si la cámara lo admite.
        val hdrButton = formatGroup.getChildAt(1)
        hdrButton?.isEnabled = caps.hdrSupported && !RecordingController.isActive
        if (!caps.hdrSupported && settings.format == VideoFormat.MP4_HDR) {
            update { copy(format = VideoFormat.MP4_SDR) }
            bindFormat()
        }
        formatHint.setText(if (caps.hdrSupported) R.string.format_hint else R.string.format_hint_no_hdr)

        val qualities = caps.qualities.ifEmpty { listOf(VideoQuality.FHD) }
        if (settings.quality !in qualities) update { copy(quality = qualities.lastOrNull { it.ordinal <= VideoQuality.FHD.ordinal } ?: qualities.first()) }
        fillSegments(qualityGroup, qualities.map { it to it.label }, settings.quality) { update { copy(quality = it) } }

        val fpsOptions = listOf(0) + caps.frameRates
        if (settings.frameRate !in fpsOptions) update { copy(frameRate = 0) }
        fillSegments(
            fpsGroup,
            fpsOptions.map { it to if (it == 0) getString(R.string.frame_rate_auto) else getString(R.string.frame_rate_value, it) },
            settings.frameRate,
        ) { update { copy(frameRate = it) } }

        stabilizationSwitch.isEnabled = caps.stabilizationSupported && !RecordingController.isActive
        stabilizationSwitch.text = if (caps.stabilizationSupported) getString(R.string.stabilization)
        else getString(R.string.not_supported, getString(R.string.stabilization))
        val torchAvailable = caps.hasFlash && !settings.frontCamera
        torchSwitch.isEnabled = torchAvailable && !RecordingController.isActive
        torchSwitch.text = if (torchAvailable) getString(R.string.torch)
        else getString(R.string.not_supported, getString(R.string.torch))
    }

    /** Rellena un selector segmentado con [options] y marca [selected]. */
    private fun <T> fillSegments(
        group: MaterialButtonToggleGroup,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ) {
        binding = true
        group.clearOnButtonCheckedListeners()
        group.removeAllViews()
        options.forEach { (value, label) ->
            val button = LayoutInflater.from(requireContext()).inflate(R.layout.item_segment, group, false) as MaterialButton
            button.id = View.generateViewId()
            button.text = label
            button.tag = value
            group.addView(button)
            if (value == selected) group.check(button.id)
        }
        binding = false
        group.addOnButtonCheckedListener { g, id, checked ->
            @Suppress("UNCHECKED_CAST")
            if (checked) onSelected(g.findViewById<View>(id).tag as T)
        }
    }

    // ---------- Estado ----------

    private fun render(status: CaptureStatus) {
        val running = status != CaptureStatus.Idle
        val paused = status.isPaused
        startStopButton.setText(if (running) R.string.stop else R.string.start)
        startStopButton.setIconResource(if (running) R.drawable.vault_ic_stop else R.drawable.vault_ic_record)
        pauseButton.visibility = if (running) View.VISIBLE else View.GONE
        pauseButton.setText(if (paused) R.string.resume else R.string.pause)

        val controls = listOf(
            modeGroup, durationChips, formatGroup, qualityGroup, fpsGroup, bitrateGroup,
            audioSwitch, frontSwitch, stabilizationSwitch, torchSwitch, intervalInput, maxPhotosInput,
        )
        controls.forEach { it.isEnabled = !running }
        listOf(modeGroup, durationChips, formatGroup, qualityGroup, fpsGroup, bitrateGroup).forEach { group ->
            for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = !running
        }
        if (!running) capabilities?.let { applyCapabilities(it) }

        statusText.visibility = if (running) View.VISIBLE else View.GONE
        previewView.visibility = if (running) View.INVISIBLE else View.VISIBLE
        statusText.text = when {
            paused -> getString(R.string.status_paused)
            status is CaptureStatus.Recording && status.limitMs > 0 ->
                getString(R.string.status_video_limit, formatDuration(status.recordedMs), formatDuration(status.limitMs))
            status is CaptureStatus.Recording -> getString(R.string.status_video_time, formatDuration(status.recordedMs))
            status is CaptureStatus.Timelapse -> getString(R.string.status_timelapse, status.photos, status.intervalSeconds)
            else -> getString(R.string.status_idle)
        }
        if (!running) updatePreview()
    }

    private fun updateModeOptions() {
        val timelapse = modeGroup.checkedButtonId == R.id.modeTimelapse
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
            if (RecordingController.isActive) return@addListener
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
        val context = requireContext()
        if (!RecordingController.canRecord(context)) {
            requestMissingPermissions()
            return
        }
        saveVaultSettings()
        // Liberar la cámara de la vista previa antes de que el servicio la tome.
        preview?.let { cameraProvider?.unbind(it) }
        preview = null

        if (modeGroup.checkedButtonId == R.id.modeTimelapse) {
            RecordingController.startTimelapse(
                context,
                intervalSeconds = intervalInput.text.toString().toIntOrNull() ?: 5,
                maxPhotos = maxPhotosInput.text.toString().toIntOrNull() ?: 0,
                settings = settings,
            )
        } else {
            RecordingController.startVideo(context, CaptureOrigin.VAULT, settings)
        }
    }

    private fun requestMissingPermissions() {
        val wanted = RecordingController.missingPermissions(requireContext(), withAudio = true).toMutableList()
        wanted += mediaPermissions().filterNot { hasPermission(it) }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED

    private fun loadVaultSettings() {
        modeGroup.check(if (prefs.getBoolean("timelapse", false)) R.id.modeTimelapse else R.id.modeVideo)
        intervalInput.setText(prefs.getInt("interval", 5).toString())
        maxPhotosInput.setText(prefs.getInt("max_photos", 0).toString())
    }

    private fun saveVaultSettings() {
        prefs.edit()
            .putBoolean("timelapse", modeGroup.checkedButtonId == R.id.modeTimelapse)
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
