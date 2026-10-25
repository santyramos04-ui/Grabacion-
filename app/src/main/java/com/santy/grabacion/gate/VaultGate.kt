package com.santy.grabacion.gate

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * Estado persistido del vault. Se guarda en preferencias privadas de la app ("vault_gate"),
 * excluidas del respaldo en la nube (ver res/xml/backup_rules.xml y data_extraction_rules.xml).
 */
object VaultGate {
    const val PREFS_NAME = "vault_gate"
    private const val KEY_UNLOCKED = "vault_unlocked"

    fun isUnlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UNLOCKED, false)

    /** Activa el flag y abre el vault como única tarea (Atrás no vuelve al inventario). */
    fun unlock(activity: Activity) {
        setUnlocked(activity, true)
        activity.startActivity(
            Destinations.vault(activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }

    /** Desactiva el flag y vuelve al módulo principal. */
    fun close(activity: Activity) {
        setUnlocked(activity, false)
        activity.startActivity(
            Destinations.inventory(activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }

    private fun setUnlocked(context: Context, unlocked: Boolean) {
        // commit() y no apply(): el valor debe estar escrito antes de cambiar de módulo.
        prefs(context).edit().putBoolean(KEY_UNLOCKED, unlocked).commit()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
