package com.santy.grabacion.recorder

enum class CaptureMode { VIDEO, TIMELAPSE }

/** Estado actual del servicio de captura, observado por la pantalla principal. */
sealed interface CaptureStatus {
    data object Idle : CaptureStatus
    data class Recording(val startedAt: Long) : CaptureStatus
    data class Timelapse(val startedAt: Long, val photos: Int, val intervalSeconds: Int) : CaptureStatus
}
