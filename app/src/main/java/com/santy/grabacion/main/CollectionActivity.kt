package com.santy.grabacion.main

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.santy.grabacion.R
import com.santy.grabacion.UpdateManager
import com.santy.grabacion.applySystemBarPadding
import com.santy.grabacion.gate.Destinations

/**
 * Pantalla principal de la Colección: barra inferior con Colección, Mapa, Añadir (botón
 * central), Estadísticas y Ajustes. Las pestañas se conservan al cambiar entre ellas.
 */
class CollectionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TAB = "tab"
    }

    private lateinit var nav: BottomNavigationView

    private val backToInventory = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            nav.selectedItemId = R.id.nav_inventory
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collection)
        findViewById<View>(R.id.root).applySystemBarPadding()

        nav = findViewById(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_add) {
                addSpecimen()
                false
            } else {
                show(item.itemId)
                true
            }
        }
        findViewById<View>(R.id.addButton).setOnClickListener { addSpecimen() }
        onBackPressedDispatcher.addCallback(this, backToInventory)

        if (savedInstanceState == null) {
            nav.selectedItemId = intent.getIntExtra(EXTRA_TAB, R.id.nav_inventory)
            UpdateManager.autoCheck(this)
        } else {
            backToInventory.isEnabled = nav.selectedItemId != R.id.nav_inventory
        }
    }

    /** Cambia de pestaña desde un fragmento (p. ej. el resumen del inventario abre Estadísticas). */
    fun select(itemId: Int) {
        nav.selectedItemId = itemId
    }

    private fun addSpecimen() = startActivity(Destinations.addSpecimen(this))

    private fun show(itemId: Int) {
        val tag = "tab_$itemId"
        val manager = supportFragmentManager
        val transaction = manager.beginTransaction().setReorderingAllowed(true)
        manager.fragments.filter { it.tag?.startsWith("tab_") == true && it.tag != tag }.forEach { transaction.hide(it) }
        val existing = manager.findFragmentByTag(tag)
        if (existing != null) transaction.show(existing) else transaction.add(R.id.container, create(itemId), tag)
        transaction.commit()
        backToInventory.isEnabled = itemId != R.id.nav_inventory
    }

    private fun create(itemId: Int): Fragment = when (itemId) {
        R.id.nav_map -> MapFragment()
        R.id.nav_stats -> StatsFragment()
        R.id.nav_settings -> CollectionSettingsFragment()
        else -> InventoryFragment()
    }
}

/** Fragmento de pestaña que recarga sus datos al volver a mostrarse. */
abstract class CollectionTab(layout: Int) : Fragment(layout) {
    abstract fun reload()

    override fun onResume() {
        super.onResume()
        if (!isHidden) reload()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) reload()
    }
}
