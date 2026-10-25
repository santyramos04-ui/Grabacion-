package com.santy.grabacion.main

import android.graphics.Bitmap
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.santy.grabacion.R
import kotlinx.coroutines.launch

/**
 * Exportación compartida por el inventario, el mapa y las estadísticas: el archivo siempre se
 * guarda en el teléfono y después se ofrece compartirlo o abrirlo.
 */
class ExportController(
    private val fragment: Fragment,
    private val specimens: suspend () -> List<Specimen>,
    /** Captura del mapa, si la pantalla tiene uno. */
    private val mapImage: (() -> Bitmap?)? = null,
) {
    fun show() {
        val context = fragment.requireContext()
        val formats = CollectionExport.Format.entries
        val labels = formats.map { context.getString(it.label) }.toMutableList()
        if (mapImage != null) labels += context.getString(R.string.export_map_image)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.export_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= formats.size) export { CollectionExport.saveImage(context, mapImage?.invoke() ?: error("Mapa no disponible")) }
                else export { CollectionExport.save(context, formats[which], specimens()) }
            }
            .show()
    }

    private fun export(block: suspend () -> CollectionExport.Saved) {
        val context = fragment.requireContext()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val saved = try {
                block()
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
                return@launch
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.export_saved)
                .setMessage(context.getString(R.string.export_saved_message, saved.name, saved.folder.replace("Download", "Descargas").replace("Pictures", "Imágenes")))
                .setPositiveButton(R.string.action_share) { _, _ -> CollectionExport.share(context, saved) }
                .setNeutralButton(R.string.export_open) { _, _ ->
                    if (!CollectionExport.open(context, saved)) {
                        Toast.makeText(context, R.string.export_no_app, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.done, null)
                .show()
        }
    }
}
