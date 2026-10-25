package com.santy.grabacion.collection

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.santy.grabacion.core.AppInfo
import com.santy.grabacion.core.TapSequenceDetector
import com.santy.grabacion.core.TapSequenceLayout
import com.santy.grabacion.core.UpdateManager
import com.santy.grabacion.core.VaultGate

/**
 * Ajustes del módulo principal: actualizaciones y "Acerca de". Cinco toques rápidos en
 * cualquier parte de esta pantalla abren el vault (acceso oculto, sin ningún botón visible).
 */
class CollectionSettingsFragment : Fragment(R.layout.fragment_collection_settings) {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val content = super.onCreateView(inflater, container, savedInstanceState)!!
        return TapSequenceLayout(requireContext()).apply {
            addView(content)
            detector = TapSequenceDetector(requireContext()) { VaultGate.unlock(requireActivity()) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.checkUpdatesButton).setOnClickListener {
            UpdateManager.check(requireActivity() as AppCompatActivity, silent = false)
        }
        view.findViewById<MaterialSwitch>(R.id.autoUpdateSwitch).apply {
            isChecked = UpdateManager.isAutoCheckEnabled(context)
            setOnCheckedChangeListener { _, checked -> UpdateManager.setAutoCheckEnabled(context, checked) }
        }

        val version = view.findViewById<TextView>(R.id.versionText)
        version.text = getString(R.string.version_label, AppInfo.versionName)
    }
}
