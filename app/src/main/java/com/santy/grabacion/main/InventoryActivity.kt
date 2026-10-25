package com.santy.grabacion.main

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Pantalla de inicio del módulo principal: lista de especímenes de la colección. */
class InventoryActivity : AppCompatActivity() {

    private val adapter = SpecimenAdapter { startActivity(Destinations.specimen(this, it.id)) }
    private lateinit var emptyText: TextView
    private var searchQuery = ""
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventory)
        findViewById<View>(R.id.root).applySystemBarPadding()
        emptyText = findViewById(R.id.emptyText)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.inventory_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_map -> startActivity(Destinations.collectionMap(this))
                R.id.action_settings -> startActivity(Destinations.mainSettings(this))
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        (toolbar.menu.findItem(R.id.action_search).actionView as SearchView).apply {
            queryHint = getString(R.string.search_hint)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String) = false
                override fun onQueryTextChange(text: String): Boolean {
                    searchQuery = text
                    reload()
                    return true
                }
            })
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
        reload()
    }

    private fun reload() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val specimens = SpecimenRepository.all(this@InventoryActivity, searchQuery)
            adapter.submitList(specimens)
            emptyText.visibility = if (specimens.isEmpty()) View.VISIBLE else View.GONE
            emptyText.setText(if (searchQuery.isBlank()) R.string.empty_collection else R.string.no_results)
        }
    }
}
