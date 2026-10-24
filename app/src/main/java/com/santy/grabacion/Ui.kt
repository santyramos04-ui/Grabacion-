package com.santy.grabacion

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Deja espacio para la barra de estado, la de navegación y el teclado. */
fun View.applySystemBarPadding() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun fileTimestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

/** Miniatura cuadrada para las cuadrículas de la galería. */
class SquareImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth)
    }
}

/** Diálogo con barra de progreso para exportaciones y descargas. */
class ProgressDialog(context: Context, title: CharSequence, onCancel: (() -> Unit)?) {
    private val bar = LinearProgressIndicator(context).apply { max = 100 }
    private val dialog: AlertDialog

    init {
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(content)
            .setCancelable(false)
        if (onCancel != null) builder.setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
        dialog = builder.show()
    }

    fun setTitle(title: CharSequence) = dialog.setTitle(title)

    fun setProgress(percent: Int) = bar.setProgressCompat(percent, true)

    fun dismiss() = dialog.dismiss()
}
