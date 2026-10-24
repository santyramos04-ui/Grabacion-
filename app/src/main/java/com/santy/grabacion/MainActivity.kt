package com.santy.grabacion

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(R.id.root).applySystemBarPadding()
        toolbar = findViewById(R.id.toolbar)

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
