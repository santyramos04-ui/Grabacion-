package com.santy.grabacion.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import kotlin.math.max

private fun Context.dp(value: Float) = value * resources.displayMetrics.density

/**
 * Barras horizontales con etiqueta, barra y valor. Cada fila puede tener su propio color
 * (p. ej. el del grupo).
 */
class BarListView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    data class Bar(val label: String, val value: Int, val color: Int? = null, val italic: Boolean = false)

    init {
        orientation = VERTICAL
    }

    fun setBars(bars: List<Bar>, total: Int) {
        removeAllViews()
        val maxValue = max(1, bars.maxOfOrNull { it.value } ?: 1)
        val defaultColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val track = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)
        bars.forEach { bar ->
            val row = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(0, context.dp(4f).toInt(), 0, context.dp(4f).toInt())
            }
            val header = LinearLayout(context).apply { orientation = HORIZONTAL }
            header.addView(TextView(context).apply {
                text = bar.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                if (bar.italic) setTypeface(typeface, android.graphics.Typeface.ITALIC)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(TextView(context).apply {
                val percent = if (total > 0) Math.round(bar.value * 100f / total) else 0
                text = "${bar.value} ($percent %)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            row.addView(header)
            row.addView(BarView(context, bar.value / maxValue.toFloat(), bar.color ?: defaultColor, track),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(8f).toInt()).apply {
                    topMargin = context.dp(3f).toInt()
                })
            addView(row)
        }
    }

    private class BarView(context: Context, private val fraction: Float, color: Int, trackColor: Int) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = trackColor }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
            val w = max(height.toFloat(), width * fraction)
            canvas.drawRoundRect(0f, 0f, w, height.toFloat(), r, r, fill)
        }
    }
}

/** Columnas verticales (p. ej. registros por mes) con etiquetas abreviadas debajo. */
class ColumnChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var values: List<Int> = emptyList()
    private var labels: List<String> = emptyList()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setData(values: List<Int>, labels: List<String>) {
        this.values = values
        this.labels = labels
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), context.dp(170f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        barPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        textPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        gridPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        textPaint.textSize = context.dp(12f)
        val labelSpace = context.dp(30f)
        val top = context.dp(16f)
        val chartHeight = height - labelSpace - top
        val maxValue = max(1, values.max())
        val slot = width / values.size.toFloat()
        val barWidth = slot * 0.62f
        canvas.drawLine(0f, top + chartHeight, width.toFloat(), top + chartHeight, gridPaint)
        // Con muchas columnas solo se rotulan algunas para que no se encimen.
        val labelEvery = max(1, (values.size + 11) / 12)
        values.forEachIndexed { i, value ->
            val cx = slot * i + slot / 2
            val h = chartHeight * value / maxValue
            if (value > 0) {
                val r = context.dp(3f)
                canvas.drawRoundRect(cx - barWidth / 2, top + chartHeight - h, cx + barWidth / 2, top + chartHeight, r, r, barPaint)
                if (values.size <= 18) canvas.drawText(value.toString(), cx, top + chartHeight - h - context.dp(3f), textPaint)
            }
            if (i % labelEvery == 0) canvas.drawText(labels.getOrElse(i) { "" }, cx, height - context.dp(12f), textPaint)
        }
    }
}

/** Línea de valores acumulados (curva de acumulación de especies). */
class LineChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var values: List<Int> = emptyList()
    private var reference: Double? = null
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** [reference] dibuja una línea horizontal punteada, p. ej. la riqueza estimada Chao1. */
    fun setData(values: List<Int>, reference: Double?) {
        this.values = values
        this.reference = reference
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), context.dp(180f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val tertiary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary)
        linePaint.color = primary
        linePaint.strokeWidth = context.dp(2.5f)
        fillPaint.color = primary and 0x00FFFFFF or 0x30000000
        refPaint.color = tertiary
        refPaint.strokeWidth = context.dp(1.5f)
        refPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(context.dp(6f), context.dp(4f)), 0f)
        textPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        textPaint.textSize = context.dp(12f)

        val left = context.dp(28f)
        val bottomSpace = context.dp(20f)
        val top = context.dp(12f)
        val chartW = width - left - context.dp(8f)
        val chartH = height - bottomSpace - top
        val maxY = max(values.max().toDouble(), reference ?: 0.0).coerceAtLeast(1.0)
        fun x(i: Int) = left + if (values.size == 1) chartW / 2 else chartW * i / (values.size - 1)
        fun y(v: Double) = top + chartH - (chartH * v / maxY).toFloat()

        val line = Path()
        val area = Path()
        values.forEachIndexed { i, v ->
            if (i == 0) { line.moveTo(x(i), y(v.toDouble())); area.moveTo(x(i), y(0.0)); area.lineTo(x(i), y(v.toDouble())) }
            else { line.lineTo(x(i), y(v.toDouble())); area.lineTo(x(i), y(v.toDouble())) }
        }
        area.lineTo(x(values.lastIndex), y(0.0))
        area.close()
        canvas.drawPath(area, fillPaint)
        canvas.drawPath(line, linePaint)
        reference?.let {
            canvas.drawLine(left, y(it), left + chartW, y(it), refPaint)
            textPaint.color = tertiary
            canvas.drawText("Chao1 ≈ ${"%.1f".format(it)}", left + context.dp(4f), y(it) - context.dp(4f), textPaint)
            textPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        canvas.drawText(maxY.toInt().toString(), 0f, top + context.dp(8f), textPaint)
        canvas.drawText("0", 0f, top + chartH, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${values.size} registros", left + chartW, height - context.dp(4f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }
}
