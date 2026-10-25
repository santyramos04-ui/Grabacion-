package com.santy.grabacion.collection

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.santy.grabacion.core.UnlockTrigger
import com.santy.grabacion.core.VaultGate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Pestaña Colección: resumen, filtro por grupo y especímenes en lista o galería. */
class InventoryFragment : CollectionTab(R.layout.fragment_inventory) {

    private companion object {
        const val PREFS = "coleccion"
        const val KEY_GRID = "vista_galeria"
    }

    private val adapter = SpecimenAdapter { startActivity(Destinations.specimen(requireContext(), it.id)) }
    private lateinit var list: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var summary: LinearLayout
    private lateinit var chips: ChipGroup
    private var searchQuery = ""
    /** Grupo elegido en los chips, o null para todos. */
    private var groupFilter: String? = null
    private var loadJob: Job? = null
    private val exporter = ExportController(this, { filtered(SpecimenRepository.all(requireContext(), searchQuery)) })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.list)
        emptyView = view.findViewById(R.id.emptyView)
        emptyText = view.findViewById(R.id.emptyText)
        summary = view.findViewById(R.id.summary)
        chips = view.findViewById(R.id.groupChips)
        view.findViewById<View>(R.id.summaryCard).setOnClickListener {
            (activity as? CollectionActivity)?.select(R.id.nav_stats)
        }

        toolbar = view.findViewById(R.id.toolbar)
        toolbar.inflateMenu(R.menu.inventory_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_view_mode -> setGrid(!adapter.grid)
                R.id.action_export -> exporter.show()
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
        UnlockTrigger.attach(view.findViewById(R.id.toolbarLogo)) { VaultGate.unlock(requireActivity()) }

        list.adapter = adapter
        setGrid(prefs().getBoolean(KEY_GRID, false))
    }

    private fun prefs() = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun setGrid(grid: Boolean) {
        adapter.grid = grid
        list.layoutManager = if (grid) GridLayoutManager(requireContext(), 2) else LinearLayoutManager(requireContext())
        list.setPadding(if (grid) dp(11) else 0, list.paddingTop, if (grid) dp(11) else 0, list.paddingBottom)
        toolbar.menu.findItem(R.id.action_view_mode).apply {
            setIcon(if (grid) R.drawable.ic_view_list else R.drawable.ic_view_grid)
            setTitle(if (grid) R.string.view_list else R.string.view_grid)
        }
        prefs().edit().putBoolean(KEY_GRID, grid).apply()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun filtered(specimens: List<Specimen>) =
        groupFilter?.let { group -> specimens.filter { it.group == group } } ?: specimens

    override fun reload() {
        if (view == null) return
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            val all = SpecimenRepository.all(context)
            val matching = if (searchQuery.isBlank()) all else SpecimenRepository.all(context, searchQuery)
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
            val metric = LayoutInflater.from(requireContext()).inflate(R.layout.item_metric, summary, false)
            metric.findViewById<TextView>(R.id.value).apply {
                text = value.toString()
                textSize = 20f
            }
            metric.findViewById<TextView>(R.id.label).setText(label)
            summary.addView(metric)
        }
    }

    /** Chips de grupo: "Todos" y uno por grupo con su color. */
    private fun showChips(all: List<Specimen>) {
        val groups = all.map { it.group }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
        requireView().findViewById<View>(R.id.chipsScroll).visibility = if (groups.size < 2) View.GONE else View.VISIBLE
        chips.setOnCheckedStateChangeListener(null)
        chips.removeAllViews()
        fun chip(label: String, group: String?, color: Int?) = Chip(requireContext()).apply {
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
        groups.forEach { (group, count) ->
            chips.addView(chip(getString(R.string.chip_count, group, count), group, GroupColors.colorFor(group)))
        }
        chips.setOnCheckedStateChangeListener { group, checkedIds ->
            groupFilter = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it).tag as String? }
            reload()
        }
    }
}
