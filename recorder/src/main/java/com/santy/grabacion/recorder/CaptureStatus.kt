package com.santy.grabacion.recorder

enum class CaptureMode { VIDEO, TIMELAPSE }

/** Quién inició la captura: cambia solo si se muestran avisos visibles al terminar. */
enum class CaptureOrigin { VAULT, COLLECTION }

/**
 * Estado real del servicio de captura, publicado por CameraService y observado por las
 * pantallas y la notificación. Nunca se adelanta a lo que confirma CameraX.
 */
sealed interface CaptureStatus {
    data object Idle : CaptureStatus

    data class Recording(
        val startedAt: Long,
        val origin: CaptureOrigin,
        /** true cuando CameraX confirmó la pausa. */
        val paused: Boolean = false,
        /** Tiempo realmente grabado, sin pausas. */
        val recordedMs: Long = 0,
        /** Límite configurado en ms; 0 = sin límite. */
        val limitMs: Long = 0,
    ) : CaptureStatus

    data class Timelapse(
        val startedAt: Long,
        val photos: Int,
        val intervalSeconds: Int,
        val paused: Boolean = false,
    ) : CaptureStatus
}

val CaptureStatus.isPaused: Boolean
    get() = (this as? CaptureStatus.Recording)?.paused ?: (this as? CaptureStatus.Timelapse)?.paused ?: false
