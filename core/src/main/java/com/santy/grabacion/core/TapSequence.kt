package com.santy.grabacion.core

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Detecta una secuencia de toques rápidos y deliberados: [requiredTaps] toques cortos, cada uno
 * a menos de [windowMs] del primero y todos en la misma zona. Un arrastre, un scroll, un toque
 * largo o un segundo dedo reinician la cuenta, para que el uso normal no la active.
 */
class TapSequenceDetector(
    context: Context,
    private val requiredTaps: Int = 5,
    private val windowMs: Long = 2_000,
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

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                downX = event.x
                downY = event.y
                valid = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> reset()
            MotionEvent.ACTION_MOVE ->
                if (valid && hypot(event.x - downX, event.y - downY) > touchSlop) reset()
            MotionEvent.ACTION_CANCEL -> reset()
            MotionEvent.ACTION_UP -> {
                if (valid && event.eventTime - downTime <= maxPressMs) addTap(Tap(event.eventTime, event.x, event.y))
                else taps.clear()
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
