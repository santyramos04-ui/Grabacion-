package com.santy.grabacion.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Servicio en primer plano que mantiene la cámara abierta aunque la app no esté visible.
 * Graba video o toma fotos de timelapse mientras el usuario sigue usando el teléfono.
 */
class CameraService : LifecycleService() {

    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL_ID = "captura"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.santy.grabacion.START"
        const val ACTION_STOP = "com.santy.grabacion.STOP"
        const val EXTRA_MODE = "mode"
        const val EXTRA_FRONT = "front"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_MAX_PHOTOS = "max_photos"

        private const val VIDEO_FOLDER = "Movies/Owl"
        private const val PHOTO_FOLDER = "Pictures/Owl"

        private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
        val status: StateFlow<CaptureStatus> = _status

        fun qualityFromName(name: String?): Quality = when (name) {
            "HD" -> Quality.HD
            "UHD" -> Quality.UHD
            else -> Quality.FHD
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var imageCapture: ImageCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var startedAt = 0L
    private var intervalSeconds = 5
    private var maxPhotos = 0
    private var photoCount = 0
    private var sessionFolder = ""

    private val timelapseTick = object : Runnable {
        override fun run() {
            takeTimelapsePhoto()
            handler.postDelayed(this, intervalSeconds * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (_status.value == CaptureStatus.Idle) start(intent)
            ACTION_STOP -> stopCapture()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val mode = CaptureMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: CaptureMode.VIDEO.name)
        val front = intent.getBooleanExtra(EXTRA_FRONT, false)
        val audio = mode == CaptureMode.VIDEO &&
            intent.getBooleanExtra(EXTRA_AUDIO, true) &&
            hasPermission(Manifest.permission.RECORD_AUDIO)
        intervalSeconds = intent.getIntExtra(EXTRA_INTERVAL, 5).coerceAtLeast(1)
        maxPhotos = intent.getIntExtra(EXTRA_MAX_PHOTOS, 0).coerceAtLeast(0)
        startedAt = System.currentTimeMillis()
        photoCount = 0

        _status.value = if (mode == CaptureMode.VIDEO) {
            CaptureStatus.Recording(startedAt)
        } else {
            CaptureStatus.Timelapse(startedAt, 0, intervalSeconds)
        }

        var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (audio) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el servicio en primer plano", e)
            fail("No se pudo iniciar la captura en segundo plano")
            return
        }
        acquireWakeLock()

        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                provider.unbindAll()
                when (mode) {
                    CaptureMode.VIDEO -> startVideo(
                        provider, selector, audio, qualityFromName(intent.getStringExtra(EXTRA_QUALITY))
                    )
                    CaptureMode.TIMELAPSE -> startTimelapse(provider, selector)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al abrir la cámara", e)
                fail("Error al abrir la cámara: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startVideo(provider: ProcessCameraProvider, selector: CameraSelector, audio: Boolean, quality: Quality) {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)
        provider.bindToLifecycle(this, selector, videoCapture)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${timestamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, VIDEO_FOLDER)
            }
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        var pending = recorder.prepareRecording(this, output)
        if (audio) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                    Log.e(TAG, "Error de grabación: ${event.error}", event.cause)
                    toast("La grabación terminó con error (${event.error})")
                } else {
                    toast("Video guardado en $VIDEO_FOLDER")
                }
                endSession()
            }
        }
    }

    private fun startTimelapse(provider: ProcessCameraProvider, selector: CameraSelector) {
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        provider.bindToLifecycle(this, selector, capture)
        imageCapture = capture
        sessionFolder = "$PHOTO_FOLDER/TL_${timestamp()}"
        handler.post(timelapseTick)
    }

    private fun takeTimelapsePhoto() {
        val capture = imageCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${timestamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, sessionFolder)
            }
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                if (imageCapture == null) return
                photoCount++
                _status.value = CaptureStatus.Timelapse(startedAt, photoCount, intervalSeconds)
                updateNotification()
                if (maxPhotos in 1..photoCount) stopCapture()
            }

            override fun onError(e: ImageCaptureException) {
                Log.e(TAG, "No se pudo tomar la foto", e)
            }
        })
    }

    private fun stopCapture() {
        handler.removeCallbacks(timelapseTick)
        val active = recording
        if (active != null) {
            // endSession() se llama cuando llega el evento Finalize y el archivo queda cerrado.
            active.stop()
        } else {
            if (imageCapture != null && photoCount > 0) toast("$photoCount fotos guardadas en $sessionFolder")
            endSession()
        }
    }

    private fun fail(message: String) {
        toast(message)
        endSession()
    }

    private fun endSession() {
        handler.removeCallbacks(timelapseTick)
        imageCapture = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        releaseWakeLock()
        _status.value = CaptureStatus.Idle
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recording?.close()
        recording = null
        handler.removeCallbacks(timelapseTick)
        releaseWakeLock()
        _status.value = CaptureStatus.Idle
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            (packageManager.getLaunchIntentForPackage(packageName) ?: Intent()),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop), stop)
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (val s = _status.value) {
            is CaptureStatus.Timelapse -> builder
                .setContentTitle(getString(R.string.notif_timelapse))
                .setContentText(getString(R.string.notif_timelapse_text, s.photos, s.intervalSeconds))
            else -> builder.setContentTitle(getString(R.string.notif_video))
        }
        return builder.build()
    }

    private fun updateNotification() {
        if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun acquireWakeLock() {
        // Mantiene la CPU activa con la pantalla apagada para que el timelapse no se detenga.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Owl::Captura").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}
