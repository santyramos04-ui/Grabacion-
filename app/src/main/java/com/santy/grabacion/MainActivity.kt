package com.santy.grabacion

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.santy.grabacion.gate.Destinations
import com.santy.grabacion.gate.VaultCloseButton
import com.santy.grabacion.gate.VaultGate

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Con el vault cerrado (p. ej. al tocar la notificación de grabación) se va al módulo principal.
        if (!VaultGate.isUnlocked(this)) {
            startActivity(Destinations.inventory(this))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(R.id.root).applySystemBarPadding()
        toolbar = findViewById(R.id.toolbar)
        VaultCloseButton.install(toolbar, this)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        if (savedInstanceState == null) {
            show(R.id.nav_camera)
            UpdateManager.autoCheck(this)
        } else {
            updateTitle(bottomNav.selectedItemId)
        }
    }

    private fun show(itemId: Int) {
        val fragment: Fragment = when (itemId) {
            R.id.nav_gallery -> GalleryFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> CameraFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
        updateTitle(itemId)
    }

    private fun updateTitle(itemId: Int) {
        toolbar.title = when (itemId) {
            R.id.nav_gallery -> getString(R.string.nav_gallery)
            R.id.nav_settings -> getString(R.string.nav_settings)
            else -> getString(R.string.app_name)
        }
    }
}
