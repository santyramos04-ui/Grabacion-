package com.santy.grabacion.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Detecta dos gestos ocultos y deliberados sobre la misma zona:
 * - [requiredTaps] toques cortos dentro de [windowMs] → [onSequence].
 * - Mantener pulsado el mismo punto [longHoldMs] ms sin mover → [onLongHold] (si se indica).
 *
 * Un arrastre, un scroll, un toque largo que suelta antes de tiempo o un segundo dedo reinician
 * el gesto, para que el uso normal no lo active.
 */
class TapSequenceDetector(
    context: Context,
    private val requiredTaps: Int = 5,
    private val windowMs: Long = 2_000,
    /** Tiempo para el gesto de mantener pulsado; 0 lo desactiva. */
    private val longHoldMs: Long = 0,
    private val onLongHold: (() -> Unit)? = null,
    private val onSequence: () -> Unit,
) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    /** Radio de la zona donde deben caer todos los toques (unos 56 dp). */
    private val zoneRadius = 56 * context.resources.displayMetrics.density

    private data class Tap(val time: Long, val x: Float, val y: Float)

    private val taps = ArrayDeque<Tap>()
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var valid = false

    private val handler = Handler(Looper.getMainLooper())
    private val holdEnabled = longHoldMs > 0 && onLongHold != null
    private var holdFired = false
    private val holdRunnable = Runnable {
        if (valid) {
            holdFired = true
            valid = false
            taps.clear()
            onLongHold?.invoke()
        }
    }

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                downX = event.x
                downY = event.y
                valid = true
                holdFired = false
                if (holdEnabled) handler.postDelayed(holdRunnable, longHoldMs)
            }
            MotionEvent.ACTION_POINTER_DOWN -> reset()
            MotionEvent.ACTION_MOVE ->
                if (valid && hypot(event.x - downX, event.y - downY) > touchSlop) reset()
            MotionEvent.ACTION_CANCEL -> reset()
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdRunnable)
                if (!holdFired && valid && event.eventTime - downTime <= maxPressMs) {
                    addTap(Tap(event.eventTime, event.x, event.y))
                } else if (!holdFired) {
                    taps.clear()
                }
                valid = false
            }
        }
    }

    private fun addTap(tap: Tap) {
        while (taps.isNotEmpty() && tap.time - taps.first().time > windowMs) taps.removeFirst()
        val first = taps.firstOrNull()
        if (first != null && hypot(tap.x - first.x, tap.y - first.y) > zoneRadius) taps.clear()
        taps.addLast(tap)
        if (taps.size >= requiredTaps) {
            taps.clear()
            onSequence()
        }
    }

    fun reset() {
        handler.removeCallbacks(holdRunnable)
        taps.clear()
        valid = false
    }
}

/**
 * Contenedor que observa los toques que pasan por él sin interceptarlos: los botones, listas y
 * campos de su interior siguen funcionando con normalidad.
 */
class TapSequenceLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    var detector: TapSequenceDetector? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        detector?.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }
}
