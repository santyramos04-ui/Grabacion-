package com.santy.grabacion.main

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.santy.grabacion.BuildConfig
import com.santy.grabacion.R
import com.santy.grabacion.UpdateManager
import com.santy.grabacion.applySystemBarPadding
import com.santy.grabacion.gate.UnlockTrigger
import com.santy.grabacion.gate.VaultGate

/** Ajustes del módulo principal: actualizaciones y "Acerca de". */
class MainSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_settings)
        findViewById<View>(R.id.root).applySystemBarPadding()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.checkUpdatesButton).setOnClickListener {
            UpdateManager.check(this, silent = false)
        }
        findViewById<MaterialSwitch>(R.id.autoUpdateSwitch).apply {
            isChecked = UpdateManager.isAutoCheckEnabled(context)
            setOnCheckedChangeListener { _, checked -> UpdateManager.setAutoCheckEnabled(context, checked) }
        }

        val version = findViewById<TextView>(R.id.versionText)
        version.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
        UnlockTrigger.attach(version) { VaultGate.unlock(this) }
    }
}
