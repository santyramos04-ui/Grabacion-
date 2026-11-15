package com.santy.grabacion.recorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Detiene la grabación con una ráfaga rápida del botón de volumen, con la app abierta, en segundo
 * plano o cerrada, mientras haya una captura en curso. Con la pantalla apagada muchos teléfonos
 * ignoran el botón de volumen si no suena nada, y entonces no hay cambio que detectar.
 *
 * No lee las teclas (Android no lo permite sin permisos especiales): vigila los cambios de
 * volumen del sistema mientras el servicio está activo. Por eso el patrón mueve el volumen de
 * verdad, y una pulsación que no cambia el nivel (volumen ya al máximo o al mínimo en
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

    // Respaldo: algunos sistemas guardan el volumen en Settings con retraso o agrupando cambios.
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (snapshotChanged()) registerChange()
        }
    }

    // Aviso inmediato que Android envía en cada cambio de volumen. Ambos caminos pasan por
    // snapshotChanged(), así que un mismo cambio nunca se cuenta dos veces.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (snapshotChanged()) registerChange()
        }
    }

    fun start() {
        if (registered || audio == null) return
        snapshotChanged() // guarda los niveles actuales como referencia
        changeTimes.clear()
        appContext.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    fun stop() {
        if (!registered) return
        appContext.contentResolver.unregisterContentObserver(observer)
        appContext.unregisterReceiver(receiver)
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

    private companion object {
        /** Difusión del sistema en cada cambio de volumen (no forma parte del SDK público). */
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
