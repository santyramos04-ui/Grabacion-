package com.santy.grabacion.collection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.util.Locale

/** Color estable por grupo taxonómico, igual en la lista, la ficha, las gráficas y el mapa. */
object GroupColors {
    // Paleta de 10 tonos distinguibles; todos superan contraste 4.5:1 con texto blanco.
    private val palette = intArrayOf(
        0xFFA35F1F.toInt(), // ocre
        0xFF2E7D5B.toInt(), // verde bosque
        0xFF3F6DB5.toInt(), // azul
        0xFFB23A48.toInt(), // carmín
        0xFF7B5EA7.toInt(), // violeta
        0xFF176F7D.toInt(), // turquesa
        0xFF7A5F14.toInt(), // oliva
        0xFFB03F67.toInt(), // rosa
        0xFF5B6B7A.toInt(), // pizarra
        0xFF4F6B26.toInt(), // musgo
    )

    // Grupos frecuentes con color fijo; el resto se asigna por el nombre.
    private val known = mapOf(
        "lepidoptera" to 0, "coleoptera" to 1, "hymenoptera" to 3, "diptera" to 2, "hemiptera" to 4,
        "odonata" to 5, "orthoptera" to 6, "araneae" to 7, "plantae" to 9, "aves" to 2,
        "amphibia" to 5, "reptilia" to 6, "mammalia" to 8, "fungi" to 4,
    )

    fun colorFor(group: String): Int {
        val key = group.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return 0xFF6B6B6B.toInt()
        val index = known[key] ?: Math.floorMod(key.hashCode(), palette.size)
        return palette[index]
    }

    /** Alfiler del mapa con el color del grupo y borde blanco. */
    fun markerIcon(context: Context, color: Int): Drawable {
        val density = context.resources.displayMetrics.density
        val width = (26 * density).toInt()
        val height = (36 * density).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val r = width / 2f
        val stroke = 2.5f * density
        val path = Path().apply {
            addCircle(r, r, r - stroke, Path.Direction.CW)
            moveTo(r - r * 0.55f, r + r * 0.6f)
            lineTo(r, height - stroke)
            lineTo(r + r * 0.55f, r + r * 0.6f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = Color.WHITE
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(r, r, r * 0.32f, paint)
        return BitmapDrawable(context.resources, bitmap)
    }
}
