package com.santy.grabacion.gate

import android.content.Context
import android.content.Intent
import com.santy.grabacion.MainActivity
import com.santy.grabacion.main.InventoryActivity
import com.santy.grabacion.main.MainSettingsActivity
import com.santy.grabacion.main.SpecimenEditActivity
import com.santy.grabacion.main.SpecimenDetailActivity

/** Destinos de navegación centralizados de ambos módulos. */
object Destinations {
    const val EXTRA_SPECIMEN_ID = "specimen_id"

    // Módulo principal
    fun inventory(context: Context) = Intent(context, InventoryActivity::class.java)
    fun specimen(context: Context, id: Long) =
        Intent(context, SpecimenDetailActivity::class.java).putExtra(EXTRA_SPECIMEN_ID, id)
    fun addSpecimen(context: Context) = Intent(context, SpecimenEditActivity::class.java)
    fun editSpecimen(context: Context, id: Long) =
        Intent(context, SpecimenEditActivity::class.java).putExtra(EXTRA_SPECIMEN_ID, id)
    fun mainSettings(context: Context) = Intent(context, MainSettingsActivity::class.java)

    // Vault: la app de cámara, cuya entrada sigue siendo MainActivity.
    fun vault(context: Context) = Intent(context, MainActivity::class.java)
}
