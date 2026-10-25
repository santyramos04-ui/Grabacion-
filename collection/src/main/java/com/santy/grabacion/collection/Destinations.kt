package com.santy.grabacion.collection

import android.content.Context
import android.content.Intent

/** Destinos de navegación internos de la colección (los del vault pasan por core.Routes). */
object Destinations {
    const val EXTRA_SPECIMEN_ID = "specimen_id"

    // Módulo principal
    /** Pantalla principal de la Colección (pestaña Colección). */
    fun inventory(context: Context) = Intent(context, CollectionActivity::class.java)
    fun specimen(context: Context, id: Long) =
        Intent(context, SpecimenDetailActivity::class.java).putExtra(EXTRA_SPECIMEN_ID, id)
    fun addSpecimen(context: Context) = Intent(context, SpecimenEditActivity::class.java)
    fun editSpecimen(context: Context, id: Long) =
        Intent(context, SpecimenEditActivity::class.java).putExtra(EXTRA_SPECIMEN_ID, id)
    /** Editor previo a la exportación de la imagen del mapa (datos en MapExportData). */
    fun mapExport(context: Context) = Intent(context, MapExportActivity::class.java)

}
