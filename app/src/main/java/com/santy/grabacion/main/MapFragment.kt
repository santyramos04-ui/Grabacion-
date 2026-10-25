package com.santy.grabacion.main

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.drawToBitmap
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.santy.grabacion.R
import com.santy.grabacion.gate.Destinations
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale

/**
 * Mapa de la colección: marcadores con el color del grupo, filtro por grupo, capa estándar o
 * topográfica, polígono de la extensión de presencia (EOO) y resumen de lo que se muestra.
 */
class MapFragment : CollectionTab(R.layout.fragment_map) {

    private companion object {
        const val PREFS = "mapa"
        const val KEY_LAYER = "capa"
        const val KEY_HULL = "poligono"
        const val NO_GROUP = "Sin grupo"
    }

    private lateinit var map: MapView
    private lateinit var chips: ChipGroup
    private lateinit var metrics: LinearLayout
    private lateinit var detail: TextView
    private var myLocation: MyLocationNewOverlay? = null

    private var all: List<Specimen> = emptyList()
    /** Grupos ocultos por el filtro. */
    private val hidden = mutableSetOf<String>()
    private var showHull = true
    private var centered = false

    private val layers: List<Pair<Int, ITileSource>> = listOf(
        R.string.map_layer_standard to TileSourceFactory.MAPNIK,
        R.string.map_layer_topo to TileSourceFactory.OpenTopo,
    )

    private val exporter = ExportController(this, { visible() }, { buildMapImage() })

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (SpecimenLocation.hasPermission(requireContext())) centerOnMe()
        else Toast.makeText(requireContext(), R.string.location_no_permission, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapSetup.init(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        chips = view.findViewById(R.id.groupChips)
        metrics = view.findViewById(R.id.metrics)
        detail = view.findViewById(R.id.mapDetail)
        map = view.findViewById(R.id.map)
        map.configure(interactive = true)
        map.setTileSource(layers.getOrNull(prefs().getInt(KEY_LAYER, 0))?.second ?: TileSourceFactory.MAPNIK)
        showHull = prefs().getBoolean(KEY_HULL, true)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.map_menu)
        toolbar.menu.findItem(R.id.action_hull).isChecked = showHull
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_layers -> chooseLayer()
                R.id.action_hull -> {
                    showHull = !item.isChecked
                    item.isChecked = showHull
                    prefs().edit().putBoolean(KEY_HULL, showHull).apply()
                    redraw()
                }
                R.id.action_export -> exporter.show()
            }
            true
        }

        view.findViewById<View>(R.id.fitButton).setOnClickListener { fit(visible()) }
        view.findViewById<View>(R.id.myLocationButton).setOnClickListener {
            if (SpecimenLocation.hasPermission(requireContext())) centerOnMe()
            else locationPermission.launch(SpecimenLocation.PERMISSIONS)
        }
        if (SpecimenLocation.hasPermission(requireContext())) ensureMyLocationOverlay()
    }

    private fun ensureMyLocationOverlay(): MyLocationNewOverlay =
        myLocation ?: MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), map).also {
            myLocation = it
            it.enableMyLocation()
            redraw()
        }

    /** Centra el mapa en la posición actual; si el GPS aún no la tiene, la pide. */
    private fun centerOnMe() {
        val overlay = ensureMyLocationOverlay()
        overlay.myLocation?.let {
            map.controller.animateTo(it, 16.0, 600L)
            return
        }
        Toast.makeText(requireContext(), R.string.location_searching, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val fix = SpecimenLocation.current(requireContext())
            if (fix == null) {
                Toast.makeText(requireContext(), R.string.location_failed, Toast.LENGTH_LONG).show()
            } else {
                map.controller.animateTo(GeoPoint(fix.latitude, fix.longitude), 16.0, 600L)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) resumeMap()
    }

    override fun onPause() {
        pauseMap()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (hidden) pauseMap() else resumeMap()
        super.onHiddenChanged(hidden)
    }

    private fun resumeMap() {
        map.onResume()
        myLocation?.enableMyLocation()
    }

    private fun pauseMap() {
        myLocation?.disableMyLocation()
        map.onPause()
    }

    override fun reload() {
        if (view == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            all = SpecimenRepository.all(requireContext()).filter { it.hasLocation }
            buildChips()
            redraw()
            if (!centered && all.isNotEmpty()) {
                centered = true
                map.post { fit(visible()) }
            }
        }
    }

    private fun groupOf(specimen: Specimen) = specimen.group.ifBlank { NO_GROUP }

    private fun visible() = all.filter { groupOf(it) !in hidden }

    private fun buildChips() {
        chips.removeAllViews()
        all.groupingBy { groupOf(it) }.eachCount().entries.sortedByDescending { it.value }.forEach { (group, count) ->
            val color = GroupColors.colorFor(if (group == NO_GROUP) "" else group)
            chips.addView(Chip(requireContext()).apply {
                text = getString(R.string.chip_count, group, count)
                isCheckable = true
                isChecked = group !in hidden
                chipIcon = GroupColors.markerIcon(context, color)
                isChipIconVisible = true
                checkedIcon = null
                chipStrokeWidth = 0f
                chipBackgroundColor = ColorStateList.valueOf(color and 0x00FFFFFF or 0x33000000)
                alpha = if (isChecked) 1f else 0.45f
                setOnCheckedChangeListener { chip, checked ->
                    chip.alpha = if (checked) 1f else 0.45f
                    if (checked) hidden -= group else hidden += group
                    redraw()
                }
            })
        }
    }

    private fun redraw() {
        map.overlays.clear()
        val shown = visible()
        val stats = CollectionStats(shown)
        val geo = stats.geo()
        if (showHull && geo != null && geo.hull.size >= 3) {
            map.overlays.add(Polygon(map).apply {
                setPoints(geo.hull.map { GeoPoint(it.first, it.second) } + GeoPoint(geo.hull[0].first, geo.hull[0].second))
                val primary = com.google.android.material.color.MaterialColors.getColor(
                    map, com.google.android.material.R.attr.colorPrimary
                )
                fillPaint.color = primary and 0x00FFFFFF or 0x26000000
                outlinePaint.color = primary
                outlinePaint.strokeWidth = 3 * resources.displayMetrics.density
                setInfoWindow(null)
            })
        }
        myLocation?.let { map.overlays.add(it) }
        shown.forEach { specimen ->
            map.overlays.add(specimenMarker(map, specimen, GeoPoint(specimen.latitude!!, specimen.longitude!!)) {
                startActivity(Destinations.specimen(requireContext(), specimen.id))
            })
        }
        map.invalidate()
        requireView().findViewById<View>(R.id.emptyText).visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        showSummary(stats, geo)
    }

    private fun showSummary(stats: CollectionStats, geo: CollectionStats.Geo?) {
        metrics.removeAllViews()
        listOf(
            stats.total.toString() to getString(R.string.metric_points),
            stats.species.toString() to getString(R.string.metric_species),
            (geo?.let { "${fmt(it.eooKm2)} km²" } ?: "—") to getString(R.string.metric_eoo),
            (geo?.let { "${fmt(it.maxDistanceKm)} km" } ?: "—") to getString(R.string.metric_max_distance),
        ).forEach { (value, label) ->
            val view = LayoutInflater.from(requireContext()).inflate(R.layout.item_metric, metrics, false)
            view.findViewById<TextView>(R.id.value).apply {
                text = value
                textSize = 17f
            }
            view.findViewById<TextView>(R.id.label).text = label
            metrics.addView(view)
        }
        val altitude = stats.altitude()
        detail.text = listOfNotNull(
            altitude?.let { getString(R.string.map_altitude_range, meters(it.min), meters(it.max)) },
            geo?.let { getString(R.string.map_centroid, coord(it.centroidLat), coord(it.centroidLng)) },
        ).joinToString("\n")
        detail.visibility = if (detail.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun fit(specimens: List<Specimen>) {
        val points = specimens.map { GeoPoint(it.latitude!!, it.longitude!!) }
        when {
            points.isEmpty() -> Unit
            points.size == 1 || points.distinct().size == 1 -> {
                map.controller.setZoom(15.0)
                map.controller.setCenter(points[0])
            }
            else -> map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.3f), true, 64)
        }
    }

    private fun chooseLayer() {
        val current = prefs().getInt(KEY_LAYER, 0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.map_layer)
            .setSingleChoiceItems(layers.map { getString(it.first) }.toTypedArray(), current) { dialog, which ->
                prefs().edit().putInt(KEY_LAYER, which).apply()
                map.setTileSource(layers[which].second)
                requireView().findViewById<TextView>(R.id.attribution).setText(
                    if (which == 1) R.string.map_attribution_topo else R.string.map_attribution
                )
                dialog.dismiss()
            }
            .show()
    }

    /** Imagen para exportar: el mapa tal como se ve, con título, leyenda y atribución. */
    private fun buildMapImage(): Bitmap? {
        if (map.width == 0 || map.height == 0) return null
        val mapBitmap = map.drawToBitmap()
        val density = resources.displayMetrics.density
        val shown = visible()
        val legend = shown.groupingBy { groupOf(it) }.eachCount().entries.sortedByDescending { it.value }
        val pad = 16 * density
        val line = 22 * density
        val header = 56 * density
        val footer = pad * 2 + line * (legend.size + 2)
        val result = Bitmap.createBitmap(mapBitmap.width, (header + mapBitmap.height + footer).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF211A14.toInt(); textSize = 20 * density; isFakeBoldText = true }
        canvas.drawText(getString(R.string.map_export_title), pad, header - 20 * density, text)
        canvas.drawBitmap(mapBitmap, 0f, header, null)
        text.isFakeBoldText = false
        text.textSize = 14 * density
        var y = header + mapBitmap.height + pad + line * 0.7f
        val stats = CollectionStats(shown)
        val geo = stats.geo()
        canvas.drawText(
            getString(R.string.map_export_summary, stats.total, stats.species, geo?.let { fmt(it.eooKm2) } ?: "0"),
            pad, y, text,
        )
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        legend.forEach { (group, count) ->
            y += line
            dot.color = GroupColors.colorFor(if (group == NO_GROUP) "" else group)
            canvas.drawCircle(pad + 6 * density, y - 5 * density, 6 * density, dot)
            canvas.drawText("$group ($count)", pad + 20 * density, y, text)
        }
        text.textSize = 11 * density
        text.color = 0xFF666666.toInt()
        canvas.drawText(getString(R.string.map_attribution), pad, y + line, text)
        return result
    }

    private fun prefs() = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun fmt(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

    private fun meters(value: Double) = String.format(Locale.getDefault(), "%,.0f m", value)

    private fun coord(value: Double) = String.format(Locale.US, "%.4f", value)
}

