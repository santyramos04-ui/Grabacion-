package com.santy.grabacion.gate

import android.os.SystemClock
import android.view.View

/**
 * Disparador discreto: detecta [requiredTaps] toques dentro de [windowMs] sobre una vista
 * (el logo del splash o del inventario, o la versión en Ajustes → Acerca de).
 */
class UnlockTrigger(
    private val requiredTaps: Int = 5,
    private val windowMs: Long = 2_000,
    private val onTap: (() -> Unit)? = null,
    private val onUnlock: () -> Unit,
) : View.OnClickListener {

    private val taps = ArrayDeque<Long>()

    override fun onClick(view: View) {
        val now = SystemClock.uptimeMillis()
        taps.addLast(now)
        while (taps.isNotEmpty() && now - taps.first() > windowMs) taps.removeFirst()
        onTap?.invoke()
        if (taps.size >= requiredTaps) {
            taps.clear()
            onUnlock()
        }
    }

    companion object {
        /** Convierte [view] en disparador sin sonido ni efecto visual que lo delate. */
        fun attach(view: View, onTap: (() -> Unit)? = null, onUnlock: () -> Unit) {
            view.isSoundEffectsEnabled = false
            view.isHapticFeedbackEnabled = false
            view.setOnClickListener(UnlockTrigger(onTap = onTap, onUnlock = onUnlock))
        }
    }
}
