package com.santy.grabacion.main

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.santy.grabacion.R
import com.santy.grabacion.UpdateManager
import com.santy.grabacion.applySystemBarPadding
import com.santy.grabacion.gate.Destinations
import com.santy.grabacion.gate.UnlockTrigger
import com.santy.grabacion.gate.VaultGate

/** Pantalla de inicio del módulo principal: lista de especímenes de la colección. */
class InventoryActivity : AppCompatActivity() {

    private val adapter = SpecimenAdapter { startActivity(Destinations.specimen(this, it.id)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventory)
        findViewById<View>(R.id.root).applySystemBarPadding()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.inventory_menu)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                startActivity(Destinations.mainSettings(this))
                true
            } else {
                false
            }
        }
        UnlockTrigger.attach(findViewById(R.id.toolbarLogo)) { VaultGate.unlock(this) }

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        list.adapter = adapter

        findViewById<View>(R.id.addButton).setOnClickListener {
            startActivity(Destinations.addSpecimen(this))
        }

        if (savedInstanceState == null) UpdateManager.autoCheck(this)
    }

    override fun onResume() {
        super.onResume()
        adapter.submitList(SpecimenRepository.all())
    }
}
