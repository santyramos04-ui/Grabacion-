package com.santy.grabacion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        view.findViewById<TextView>(R.id.versionText).text =
            getString(R.string.version_label, BuildConfig.VERSION_NAME)

        view.findViewById<Button>(R.id.checkUpdatesButton).setOnClickListener {
            UpdateManager.check(requireActivity() as AppCompatActivity, silent = false)
        }

        view.findViewById<MaterialSwitch>(R.id.autoUpdateSwitch).apply {
            isChecked = UpdateManager.isAutoCheckEnabled(context)
            setOnCheckedChangeListener { _, checked -> UpdateManager.setAutoCheckEnabled(context, checked) }
        }

        view.findViewById<Button>(R.id.batteryButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        view.findViewById<Button>(R.id.githubButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}")))
        }
    }
}
