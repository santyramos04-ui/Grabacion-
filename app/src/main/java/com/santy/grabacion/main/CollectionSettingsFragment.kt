package com.santy.grabacion.main

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.santy.grabacion.BuildConfig
import com.santy.grabacion.R
import com.santy.grabacion.UpdateManager
import com.santy.grabacion.gate.UnlockTrigger
import com.santy.grabacion.gate.VaultGate

/** Ajustes del módulo principal: actualizaciones y "Acerca de". */
class CollectionSettingsFragment : Fragment(R.layout.fragment_collection_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.checkUpdatesButton).setOnClickListener {
            UpdateManager.check(requireActivity() as AppCompatActivity, silent = false)
        }
        view.findViewById<MaterialSwitch>(R.id.autoUpdateSwitch).apply {
            isChecked = UpdateManager.isAutoCheckEnabled(context)
            setOnCheckedChangeListener { _, checked -> UpdateManager.setAutoCheckEnabled(context, checked) }
        }

        val version = view.findViewById<TextView>(R.id.versionText)
        version.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
        UnlockTrigger.attach(version) { VaultGate.unlock(requireActivity()) }
    }
}
