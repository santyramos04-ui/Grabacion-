package com.santy.grabacion

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/** Exporta videos con Media3 Transformer: recortes rápidos y timelapses a partir de fotos. */
@OptIn(UnstableApi::class)
class VideoExporter(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var output: File? = null

    /** Recorta, quita el audio y/o rota un video. */
    fun exportEdit(
        source: Uri,
        startMs: Long,
        endMs: Long,
        removeAudio: Boolean,
        rotationDegrees: Int,
        onProgress: (Int) -> Unit,
        onDone: (Result<File>) -> Unit,
    ) {
        val mediaItem = MediaItem.Builder()
            .setUri(source)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()
        val videoEffects = if (rotationDegrees % 360 != 0) {
            // ScaleAndRotateTransformation gira en sentido antihorario; se invierte para rotar a la derecha.
            listOf(ScaleAndRotateTransformation.Builder().setRotationDegrees(360f - rotationDegrees).build())
        } else {
            emptyList()
        }
        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(removeAudio)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()
        start(Composition.Builder(EditedMediaItemSequence(edited)).build(), onProgress, onDone)
    }

    /** Une las fotos de un timelapse en un video, una foto por fotograma. */
    fun exportTimelapse(
        photos: List<Uri>,
        framesPerSecond: Int,
        onProgress: (Int) -> Unit,
        onDone: (Result<File>) -> Unit,
    ) {
        val frameMs = (1000L / framesPerSecond).coerceAtLeast(1)
        val effects = Effects(emptyList(), listOf(Presentation.createForHeight(1080)))
        val items = photos.map { uri ->
            EditedMediaItem.Builder(
                MediaItem.Builder().setUri(uri).setImageDurationMs(frameMs).build()
            )
                .setFrameRate(framesPerSecond)
                .setEffects(effects)
                .build()
        }
        start(Composition.Builder(EditedMediaItemSequence(items)).build(), onProgress, onDone)
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        transformer?.cancel()
        transformer = null
        output?.delete()
        output = null
    }

    private fun start(composition: Composition, onProgress: (Int) -> Unit, onDone: (Result<File>) -> Unit) {
        cancel()
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "owl_${fileTimestamp()}.mp4")
        output = file

        val newTransformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacksAndMessages(null)
                    transformer = null
                    output = null
                    onDone(Result.success(file))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    handler.removeCallbacksAndMessages(null)
                    transformer = null
                    output = null
                    file.delete()
                    onDone(Result.failure(exportException))
                }
            })
            .build()
        transformer = newTransformer
        newTransformer.start(composition, file.absolutePath)

        val holder = ProgressHolder()
        handler.post(object : Runnable {
            override fun run() {
                val current = transformer ?: return
                if (current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress)
                }
                handler.postDelayed(this, 300)
            }
        })
    }
}
