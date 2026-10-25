package com.santy.grabacion.main

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.santy.grabacion.R
import kotlinx.coroutines.launch

/**
 * Diálogo de exportación compartido por el inventario y el mapa. Debe crearse como propiedad
 * de la actividad (registra los selectores de archivo antes de onStart).
 */
class ExportController(
    private val activity: AppCompatActivity,
    private val specimens: suspend () -> List<Specimen>,
    /** Captura del mapa, si la pantalla tiene uno. */
    private val mapImage: (() -> Bitmap?)? = null,
) {
    private val saveLaunchers = CollectionExport.Format.entries.associateWith { format ->
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
            if (uri == null) return@registerForActivityResult
            activity.lifecycleScope.launch {
                try {
                    CollectionExport.saveTo(activity, uri, format, specimens())
                    Toast.makeText(activity, R.string.export_saved, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(activity, activity.getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun show() {
        val formats = CollectionExport.Format.entries
        val labels = formats.map { activity.getString(it.label) }.toMutableList()
        if (mapImage != null) labels += activity.getString(R.string.export_map_image)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.export_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= formats.size) shareMapImage() else chooseAction(formats[which])
            }
            .show()
    }

    private fun chooseAction(format: CollectionExport.Format) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(format.label)
            .setItems(arrayOf(activity.getString(R.string.action_share), activity.getString(R.string.export_save_device))) { _, which ->
                if (which == 0) {
                    activity.lifecycleScope.launch {
                        try {
                            CollectionExport.share(activity, format, specimens())
                        } catch (e: Exception) {
                            Toast.makeText(activity, activity.getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    saveLaunchers.getValue(format).launch(CollectionExport.fileName(format))
                }
            }
            .show()
    }

    private fun shareMapImage() {
        val bitmap = mapImage?.invoke() ?: return
        activity.lifecycleScope.launch { CollectionExport.shareImage(activity, bitmap) }
    }
}
