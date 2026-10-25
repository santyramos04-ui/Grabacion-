package com.santy.grabacion.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

/**
 * Interfaz pública de la grabadora: la única forma en que el vault y la colección inician,
 * pausan, reanudan o finalizan una captura. Detrás está CameraService.
 */
object RecordingController {

    val status: StateFlow<CaptureStatus> get() = CameraService.status

    val isActive: Boolean get() = status.value != CaptureStatus.Idle

    /** Permisos que deben estar concedidos antes de iniciar (la cámara es imprescindible). */
    fun missingPermissions(context: Context, withAudio: Boolean): List<String> {
        val wanted = mutableListOf(Manifest.permission.CAMERA)
        if (withAudio) wanted += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        return wanted.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    }

    fun canRecord(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * Inicia un video en segundo plano. Android solo permite iniciarlo mientras la app está
     * visible; después sigue aunque se salga de ella.
     */
    fun startVideo(
        context: Context,
        origin: CaptureOrigin,
        settings: RecordingSettings = RecordingSettingsStore.load(context),
    ): Boolean {
        if (isActive || !canRecord(context)) return false
        val intent = Intent(context, CameraService::class.java)
            .setAction(CameraService.ACTION_START)
            .putExtra(CameraService.EXTRA_MODE, CaptureMode.VIDEO.name)
            .putExtra(CameraService.EXTRA_ORIGIN, origin.name)
            .putExtra(CameraService.EXTRA_SETTINGS, settings.toBundle())
        ContextCompat.startForegroundService(context, intent)
        return true
    }

    /** Inicia un timelapse (solo desde el vault). */
    fun startTimelapse(
        context: Context,
        intervalSeconds: Int,
        maxPhotos: Int,
        settings: RecordingSettings = RecordingSettingsStore.load(context),
    ): Boolean {
        if (isActive || !canRecord(context)) return false
        val intent = Intent(context, CameraService::class.java)
            .setAction(CameraService.ACTION_START)
            .putExtra(CameraService.EXTRA_MODE, CaptureMode.TIMELAPSE.name)
            .putExtra(CameraService.EXTRA_ORIGIN, CaptureOrigin.VAULT.name)
            .putExtra(CameraService.EXTRA_SETTINGS, settings.toBundle())
            .putExtra(CameraService.EXTRA_INTERVAL, intervalSeconds)
            .putExtra(CameraService.EXTRA_MAX_PHOTOS, maxPhotos)
        ContextCompat.startForegroundService(context, intent)
        return true
    }

    fun pause(context: Context) = send(context, CameraService.ACTION_PAUSE)

    fun resume(context: Context) = send(context, CameraService.ACTION_RESUME)

    fun stop(context: Context) = send(context, CameraService.ACTION_STOP)

    private fun send(context: Context, action: String) {
        if (!isActive) return
        context.startService(Intent(context, CameraService::class.java).setAction(action))
    }
}
