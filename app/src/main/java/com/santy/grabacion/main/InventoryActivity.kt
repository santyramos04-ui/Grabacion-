package com.santy.grabacion.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var summary: LinearLayout
    private lateinit var chips: ChipGroup
    private var searchQuery = ""
    /** Grupo elegido en los chips, o null para todos. */
    private var groupFilter: String? = null
    private var loadJob: Job? = null
    private val exporter = ExportController(this, { filtered(SpecimenRepository.all(this, searchQuery)) })

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventory)
        findViewById<View>(R.id.root).applySystemBarPadding()
        emptyView = findViewById(R.id.emptyView)
        emptyText = findViewById(R.id.emptyText)
        summary = findViewById(R.id.summary)
        chips = findViewById(R.id.groupChips)
        findViewById<View>(R.id.summaryCard).setOnClickListener { startActivity(Destinations.stats(this)) }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.inventory_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_map -> startActivity(Destinations.collectionMap(this))
                R.id.action_stats -> startActivity(Destinations.stats(this))
                R.id.action_export -> exporter.show()
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

    private fun filtered(specimens: List<Specimen>) =
        groupFilter?.let { group -> specimens.filter { it.group == group } } ?: specimens

    private fun reload() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val all = SpecimenRepository.all(this@InventoryActivity)
            val matching = if (searchQuery.isBlank()) all else SpecimenRepository.all(this@InventoryActivity, searchQuery)
            if (groupFilter != null && all.none { it.group == groupFilter }) groupFilter = null
            val shown = filtered(matching)
            adapter.submitList(shown)
            showSummary(CollectionStats(all))
            showChips(all)
            emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            emptyText.setText(if (all.isEmpty()) R.string.empty_collection else R.string.no_results)
        }
    }

    private fun showSummary(stats: CollectionStats) {
        summary.removeAllViews()
        listOf(
            stats.total to R.string.metric_records,
            stats.species to R.string.metric_species,
            stats.groups to R.string.metric_groups,
            stats.localities to R.string.metric_localities,
        ).forEach { (value, label) ->
            val view = LayoutInflater.from(this).inflate(R.layout.item_metric, summary, false)
            view.findViewById<TextView>(R.id.value).apply {
                text = value.toString()
                textSize = 20f
                setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer))
            }
            view.findViewById<TextView>(R.id.label).apply {
                setText(label)
                setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer))
            }
            summary.addView(view)
        }
    }

    /** Chips de grupo: "Todos" y uno por grupo con su color. */
    private fun showChips(all: List<Specimen>) {
        val groups = all.map { it.group }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
        findViewById<View>(R.id.chipsScroll).visibility = if (groups.size < 2) View.GONE else View.VISIBLE
        chips.setOnCheckedStateChangeListener(null)
        chips.removeAllViews()
        fun chip(label: String, group: String?, color: Int?) = Chip(this).apply {
            id = View.generateViewId()
            text = label
            isCheckable = true
            isChecked = group == groupFilter
            if (color != null) {
                chipIcon = GroupColors.markerIcon(context, color)
                isChipIconVisible = true
            }
            tag = group
        }
        chips.addView(chip(getString(R.string.filter_all), null, null))
        groups.forEach { (group, count) -> chips.addView(chip("$group · $count", group, GroupColors.colorFor(group))) }
        chips.setOnCheckedStateChangeListener { group, checkedIds ->
            groupFilter = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it).tag as String? }
            reload()
        }
    }
}
