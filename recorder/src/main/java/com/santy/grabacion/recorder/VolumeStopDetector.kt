package com.santy.grabacion.recorder

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * Detiene la grabación con una ráfaga rápida del botón de volumen, aun en segundo plano y con la
 * pantalla apagada. No lee las teclas (Android no lo permite sin permisos especiales): vigila los
 * cambios de volumen del sistema mientras el servicio está activo. Por eso el patrón mueve el
 * volumen de verdad, y una pulsación que no cambia el nivel (volumen ya al máximo o al mínimo en
 * esa dirección) no cuenta.
 *
 * Cuando ocurren [requiredChanges] cambios dentro de [windowMs] ms, llama a [onPattern].
 */
class VolumeStopDetector(
    context: Context,
    private val requiredChanges: Int = 3,
    private val windowMs: Long = 2_000,
    private val onPattern: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    // Flujos cuyo volumen puede cambiar al pulsar el botón, según lo que esté sonando.
    private val streams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_VOICE_CALL,
    )
    private var lastVolumes = IntArray(streams.size)
    private val changeTimes = ArrayDeque<Long>()
    private var registered = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (snapshotChanged()) registerChange()
        }
    }

    fun start() {
        if (registered || audio == null) return
        snapshotChanged() // guarda los niveles actuales como referencia
        changeTimes.clear()
        appContext.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        registered = true
    }

    fun stop() {
        if (!registered) return
        appContext.contentResolver.unregisterContentObserver(observer)
        registered = false
    }

    /** Guarda los volúmenes actuales y dice si alguno cambió respecto a la última lectura. */
    private fun snapshotChanged(): Boolean {
        val manager = audio ?: return false
        var changed = false
        for (i in streams.indices) {
            val volume = try {
                manager.getStreamVolume(streams[i])
            } catch (e: Exception) {
                lastVolumes[i]
            }
            if (volume != lastVolumes[i]) {
                lastVolumes[i] = volume
                changed = true
            }
        }
        return changed
    }

    private fun registerChange() {
        val now = SystemClock.uptimeMillis()
        while (changeTimes.isNotEmpty() && now - changeTimes.first() > windowMs) changeTimes.removeFirst()
        changeTimes.addLast(now)
        if (changeTimes.size >= requiredChanges) {
            changeTimes.clear()
            onPattern()
        }
    }
}
