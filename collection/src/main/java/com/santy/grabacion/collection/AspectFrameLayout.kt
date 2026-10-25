package com.santy.grabacion.collection

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Recuadro que mantiene la proporción [ratio] (ancho / alto) usando todo el ancho disponible,
 * sin pasar de [maxHeightPx]; si la altura no alcanza, se estrecha manteniendo la forma.
 */
class AspectFrameLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    var ratio: Float = 4f / 3f
        set(value) {
            if (value > 0f && value != field) {
                field = value
                requestLayout()
            }
        }

    var maxHeightPx: Int = Int.MAX_VALUE
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = MeasureSpec.getSize(widthMeasureSpec)
        var height = (width / ratio).roundToInt()
        if (height > maxHeightPx) {
            height = maxHeightPx
            width = (height * ratio).roundToInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
