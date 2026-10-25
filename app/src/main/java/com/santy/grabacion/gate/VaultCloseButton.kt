package com.santy.grabacion.gate

import android.app.Activity
import android.view.MenuItem
import com.google.android.material.appbar.MaterialToolbar
import com.santy.grabacion.R

/** Añade "Cerrar vault" al menú de la barra superior del vault. */
object VaultCloseButton {
    fun install(toolbar: MaterialToolbar, activity: Activity) {
        toolbar.menu.add(0, R.id.action_close_vault, 0, R.string.close_vault)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_close_vault) {
                VaultGate.close(activity)
                true
            } else {
                false
            }
        }
    }
}
