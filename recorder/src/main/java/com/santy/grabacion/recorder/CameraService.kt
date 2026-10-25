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
import android.util.Range
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
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
 * Graba video (con pausa, reanudación y límite de duración) o toma fotos de timelapse. Se
 * controla solo a través de [RecordingController]; su estado real se publica en [status].
 */
class CameraService : LifecycleService() {

    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL_ID = "captura"
        private const val NOTIFICATION_ID = 1

        internal const val ACTION_START = "com.santy.grabacion.START"
        internal const val ACTION_STOP = "com.santy.grabacion.STOP"
        internal const val ACTION_PAUSE = "com.santy.grabacion.PAUSE"
        internal const val ACTION_RESUME = "com.santy.grabacion.RESUME"
        internal const val EXTRA_MODE = "mode"
        internal const val EXTRA_ORIGIN = "origin"
        internal const val EXTRA_SETTINGS = "settings"
        internal const val EXTRA_INTERVAL = "interval"
        internal const val EXTRA_MAX_PHOTOS = "max_photos"

        private const val VIDEO_FOLDER = "Movies/Owl"
        private const val PHOTO_FOLDER = "Pictures/Owl"

        private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
        val status: StateFlow<CaptureStatus> = _status
    }

    private val handler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var imageCapture: ImageCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var origin = CaptureOrigin.VAULT
    private var settings = RecordingSettings()
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
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopCapture()
            else -> if (_status.value == CaptureStatus.Idle) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val mode = runCatching { CaptureMode.valueOf(intent.getStringExtra(EXTRA_MODE)!!) }.getOrDefault(CaptureMode.VIDEO)
        origin = runCatching { CaptureOrigin.valueOf(intent.getStringExtra(EXTRA_ORIGIN)!!) }.getOrDefault(CaptureOrigin.VAULT)
        settings = RecordingSettings.fromBundle(intent.getBundleExtra(EXTRA_SETTINGS))
        val audio = mode == CaptureMode.VIDEO && settings.audio && hasPermission(Manifest.permission.RECORD_AUDIO)
        intervalSeconds = intent.getIntExtra(EXTRA_INTERVAL, 5).coerceAtLeast(1)
        maxPhotos = intent.getIntExtra(EXTRA_MAX_PHOTOS, 0).coerceAtLeast(0)
        startedAt = System.currentTimeMillis()
        photoCount = 0

        _status.value = if (mode == CaptureMode.VIDEO) {
            CaptureStatus.Recording(startedAt, origin, limitMs = settings.durationLimitMs)
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

        val selector = if (settings.frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                provider.unbindAll()
                when (mode) {
                    CaptureMode.VIDEO -> startVideo(provider, selector, audio)
                    CaptureMode.TIMELAPSE -> startTimelapse(provider, selector)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al abrir la cámara", e)
                fail("Error al abrir la cámara: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Configura el video con lo que la cámara admite de verdad. Si una combinación avanzada
     * (HDR, fps, estabilización) no se puede usar, vuelve a la configuración básica.
     */
    private fun buildVideoCapture(info: androidx.camera.core.CameraInfo?, advanced: Boolean): VideoCapture<Recorder> {
        val recorderBuilder = Recorder.Builder()
            .setAspectRatio(settings.aspectRatio.toCameraX())
            .setQualitySelector(
                QualitySelector.from(settings.quality.toCameraX(), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
        if (advanced) settings.targetBitrate()?.let { recorderBuilder.setTargetVideoEncodingBitRate(it) }
        val builder = VideoCapture.Builder(recorderBuilder.build())
        if (advanced && info != null) {
            val capabilities = Recorder.getVideoCapabilities(info)
            if (settings.format == VideoFormat.MP4_HDR && DynamicRange.HLG_10_BIT in capabilities.supportedDynamicRanges) {
                builder.setDynamicRange(DynamicRange.HLG_10_BIT)
            }
            if (settings.frameRate > 0 &&
                info.supportedFrameRateRanges.any { it.lower == settings.frameRate && it.upper == settings.frameRate }
            ) {
                builder.setTargetFrameRate(Range(settings.frameRate, settings.frameRate))
            }
            if (settings.stabilization && capabilities.isStabilizationSupported) {
                builder.setVideoStabilizationEnabled(true)
            }
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun startVideo(provider: ProcessCameraProvider, selector: CameraSelector, audio: Boolean) {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull()
        var videoCapture = buildVideoCapture(info, advanced = true)
        val camera = try {
            provider.bindToLifecycle(this, selector, videoCapture)
        } catch (e: Exception) {
            Log.w(TAG, "Configuración avanzada no admitida; se usa la básica", e)
            provider.unbindAll()
            videoCapture = buildVideoCapture(info, advanced = false)
            provider.bindToLifecycle(this, selector, videoCapture)
        }
        if (settings.torch && !settings.frontCamera && camera.cameraInfo.hasFlashUnit()) {
            camera.cameraControl.enableTorch(true)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${timestamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, VIDEO_FOLDER)
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        var pending = videoCapture.output.prepareRecording(this, output)
        if (audio) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event -> onVideoEvent(event) }
    }

    /** El estado (pausa, tiempo grabado, fin) se toma de los eventos que confirma CameraX. */
    private fun onVideoEvent(event: VideoRecordEvent) {
        val current = _status.value as? CaptureStatus.Recording
        val recordedMs = event.recordingStats.recordedDurationNanos / 1_000_000
        when (event) {
            is VideoRecordEvent.Pause -> current?.let {
                _status.value = it.copy(paused = true, recordedMs = recordedMs)
                updateNotification()
            }
            is VideoRecordEvent.Resume -> current?.let {
                _status.value = it.copy(paused = false, recordedMs = recordedMs)
                updateNotification()
            }
            is VideoRecordEvent.Status -> current?.let {
                _status.value = it.copy(recordedMs = recordedMs)
                // La duración configurada cuenta solo el tiempo realmente grabado.
                if (it.limitMs > 0 && recordedMs >= it.limitMs) recording?.stop()
            }
            is VideoRecordEvent.Finalize -> {
                recording = null
                if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE &&
                    event.error != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                ) {
                    Log.e(TAG, "Error de grabación: ${event.error}", event.cause)
                    notifyUser("La grabación terminó con error (${event.error})")
                } else {
                    notifyUser("Video guardado en $VIDEO_FOLDER")
                }
                endSession()
            }
            else -> Unit
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
            put(MediaStore.MediaColumns.RELATIVE_PATH, sessionFolder)
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                if (imageCapture == null) return
                photoCount++
                val paused = (_status.value as? CaptureStatus.Timelapse)?.paused ?: false
                _status.value = CaptureStatus.Timelapse(startedAt, photoCount, intervalSeconds, paused)
                updateNotification()
                if (maxPhotos in 1..photoCount) stopCapture()
            }

            override fun onError(e: ImageCaptureException) {
                Log.e(TAG, "No se pudo tomar la foto", e)
            }
        })
    }

    private fun pause() {
        when (val current = _status.value) {
            // El estado cambia cuando CameraX confirma la pausa (evento Pause).
            is CaptureStatus.Recording -> if (!current.paused) recording?.pause()
            is CaptureStatus.Timelapse -> if (!current.paused) {
                handler.removeCallbacks(timelapseTick)
                _status.value = current.copy(paused = true)
                updateNotification()
            }
            CaptureStatus.Idle -> Unit
        }
    }

    private fun resume() {
        when (val current = _status.value) {
            is CaptureStatus.Recording -> if (current.paused) recording?.resume()
            is CaptureStatus.Timelapse -> if (current.paused) {
                _status.value = current.copy(paused = false)
                handler.postDelayed(timelapseTick, intervalSeconds * 1000L)
                updateNotification()
            }
            CaptureStatus.Idle -> Unit
        }
    }

    private fun stopCapture() {
        handler.removeCallbacks(timelapseTick)
        val active = recording
        if (active != null) {
            // endSession() se llama cuando llega el evento Finalize y el archivo queda cerrado.
            active.stop()
        } else {
            if (imageCapture != null && photoCount > 0) notifyUser("$photoCount fotos guardadas en $sessionFolder")
            endSession()
        }
    }

    private fun fail(message: String) {
        notifyUser(message)
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

    /**
     * Notificación discreta: un punto como icono, el texto «Procesando» y los botones para pausar
     * o reanudar y para finalizar. No aparece en la pantalla de bloqueo ni hace sonido.
     */
    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // Crear el canal con el mismo id solo actualiza su nombre; conserva los ajustes del usuario.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0,
            (packageManager.getLaunchIntentForPackage(packageName) ?: Intent()),
            PendingIntent.FLAG_IMMUTABLE
        )
        fun service(requestCode: Int, action: String) = PendingIntent.getService(
            this, requestCode, Intent(this, CameraService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
        )
        val current = _status.value
        val paused = current.isPaused
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dot)
            .setContentTitle(getString(R.string.notif_processing))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                getString(if (paused) R.string.action_resume else R.string.action_pause),
                service(2, if (paused) ACTION_RESUME else ACTION_PAUSE),
            )
            .addAction(0, getString(R.string.action_finish), service(1, ACTION_STOP))

        if (paused) {
            builder.setContentText(getString(R.string.notif_paused)).setShowWhen(false).setUsesChronometer(false)
        } else {
            val recordedMs = (current as? CaptureStatus.Recording)?.recordedMs ?: 0L
            val since = if (current is CaptureStatus.Recording) System.currentTimeMillis() - recordedMs else startedAt
            builder.setWhen(since).setUsesChronometer(true).setShowWhen(true)
        }
        if (current is CaptureStatus.Timelapse) {
            builder.setContentText(resources.getQuantityString(R.plurals.notif_photos, current.photos, current.photos))
        }
        return builder.build()
    }

    private fun updateNotification() {
        if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) return
        if (_status.value == CaptureStatus.Idle) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun acquireWakeLock() {
        // Mantiene la CPU activa con la pantalla apagada para que la captura no se detenga.
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

    /** Los avisos solo se muestran en capturas del vault; las iniciadas con el gesto son silenciosas. */
    private fun notifyUser(message: String) {
        if (origin == CaptureOrigin.VAULT) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        else Log.i(TAG, message)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}
