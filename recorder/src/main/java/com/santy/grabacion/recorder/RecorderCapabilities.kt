package com.santy.grabacion.recorder

import android.content.Context
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lo que la cámara del teléfono admite de verdad, consultado a CameraX. El vault solo ofrece
 * estas opciones.
 */
data class RecorderCapabilities(
    val hasFrontCamera: Boolean,
    val hdrSupported: Boolean,
    /** Calidades admitidas para el formato pedido. */
    val qualities: List<VideoQuality>,
    /** Fotogramas por segundo fijos admitidos (p. ej. 24, 30, 60). */
    val frameRates: List<Int>,
    val stabilizationSupported: Boolean,
    val hasFlash: Boolean,
) {
    companion object {
        suspend fun query(context: Context, frontCamera: Boolean, format: VideoFormat): RecorderCapabilities {
            val provider = awaitProvider(context)
            val infos = provider.availableCameraInfos
            val hasFront = CameraSelector.DEFAULT_FRONT_CAMERA.filter(infos).isNotEmpty()
            val selector = if (frontCamera && hasFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val info: CameraInfo = selector.filter(infos).firstOrNull()
                ?: return RecorderCapabilities(hasFront, false, emptyList(), emptyList(), false, false)
            val video = Recorder.getVideoCapabilities(info)
            val hdr = DynamicRange.HLG_10_BIT in video.supportedDynamicRanges
            val range = if (format == VideoFormat.MP4_HDR && hdr) DynamicRange.HLG_10_BIT else DynamicRange.SDR
            val qualities = video.getSupportedQualities(range).mapNotNull { it.toVideoQuality() }.sortedBy { it.ordinal }
            val fps = info.supportedFrameRateRanges
                .filter { it.lower == it.upper }
                .map { it.upper }
                .filter { it in listOf(24, 25, 30, 60) }
                .distinct()
                .sorted()
            return RecorderCapabilities(
                hasFrontCamera = hasFront,
                hdrSupported = hdr,
                qualities = qualities,
                frameRates = fps,
                stabilizationSupported = video.isStabilizationSupported,
                hasFlash = info.hasFlashUnit(),
            )
        }

        internal suspend fun awaitProvider(context: Context): ProcessCameraProvider =
            suspendCancellableCoroutine { continuation ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    try {
                        continuation.resume(future.get())
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
    }
}

internal fun VideoQuality.toCameraX(): Quality = when (this) {
    VideoQuality.SD -> Quality.SD
    VideoQuality.HD -> Quality.HD
    VideoQuality.FHD -> Quality.FHD
    VideoQuality.UHD -> Quality.UHD
}

internal fun Quality.toVideoQuality(): VideoQuality? = when (this) {
    Quality.SD -> VideoQuality.SD
    Quality.HD -> VideoQuality.HD
    Quality.FHD -> VideoQuality.FHD
    Quality.UHD -> VideoQuality.UHD
    else -> null
}
