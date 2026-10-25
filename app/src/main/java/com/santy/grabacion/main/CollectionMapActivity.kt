package com.santy.grabacion.main

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.drawToBitmap
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.santy.grabacion.R
import com.santy.grabacion.applySystemBarPadding
import com.santy.grabacion.gate.Destinations
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
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
class CollectionMapActivity : AppCompatActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        MapSetup.init(this)
        setContentView(R.layout.activity_collection_map)
        findViewById<View>(R.id.root).applySystemBarPadding()

        chips = findViewById(R.id.groupChips)
        metrics = findViewById(R.id.metrics)
        detail = findViewById(R.id.mapDetail)
        map = findViewById(R.id.map)
        map.configure(interactive = true)
        map.setTileSource(layers.getOrNull(prefs().getInt(KEY_LAYER, 0))?.second ?: TileSourceFactory.MAPNIK)
        showHull = prefs().getBoolean(KEY_HULL, true)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }
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

        findViewById<View>(R.id.fitButton).setOnClickListener { fit(visible()) }
        findViewById<View>(R.id.myLocationButton).setOnClickListener {
            val point = myLocation?.myLocation
            if (point != null) {
                map.controller.animateTo(point, 15.0, 600L)
            } else {
                Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show()
            }
        }
        if (SpecimenLocation.hasPermission(this)) {
            myLocation = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        } else {
            findViewById<View>(R.id.myLocationButton).visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        myLocation?.enableMyLocation()
        lifecycleScope.launch {
            all = SpecimenRepository.all(this@CollectionMapActivity).filter { it.hasLocation }
            buildChips()
            redraw()
            if (!centered && all.isNotEmpty()) {
                centered = true
                map.post { fit(visible()) }
            }
        }
    }

    override fun onPause() {
        myLocation?.disableMyLocation()
        map.onPause()
        super.onPause()
    }

    private fun groupOf(specimen: Specimen) = specimen.group.ifBlank { NO_GROUP }

    private fun visible() = all.filter { groupOf(it) !in hidden }

    private fun buildChips() {
        chips.removeAllViews()
        all.groupingBy { groupOf(it) }.eachCount().entries.sortedByDescending { it.value }.forEach { (group, count) ->
            val color = GroupColors.colorFor(if (group == NO_GROUP) "" else group)
            chips.addView(Chip(this).apply {
                text = "$group · $count"
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
                fillPaint.color = 0x22C0752B
                outlinePaint.color = 0xFFC0752B.toInt()
                outlinePaint.strokeWidth = 3 * resources.displayMetrics.density
                setInfoWindow(null)
            })
        }
        myLocation?.let { map.overlays.add(it) }
        shown.forEach { specimen ->
            map.overlays.add(specimenMarker(map, specimen, GeoPoint(specimen.latitude!!, specimen.longitude!!)) {
                startActivity(Destinations.specimen(this, specimen.id))
            })
        }
        map.invalidate()
        findViewById<View>(R.id.emptyText).visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
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
            val view = LayoutInflater.from(this).inflate(R.layout.item_metric, metrics, false)
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
        ).joinToString(" · ")
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.map_layer)
            .setSingleChoiceItems(layers.map { getString(it.first) }.toTypedArray(), current) { dialog, which ->
                prefs().edit().putInt(KEY_LAYER, which).apply()
                map.setTileSource(layers[which].second)
                findViewById<TextView>(R.id.attribution).setText(
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

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun fmt(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

    private fun meters(value: Double) = String.format(Locale.getDefault(), "%,.0f m", value)

    private fun coord(value: Double) = String.format(Locale.US, "%.4f", value)
}

/** Ajustes comunes de los mapas de la app (OpenStreetMap). */
fun MapView.configure(interactive: Boolean) {
    setTileSource(TileSourceFactory.MAPNIK)
    setMultiTouchControls(interactive)
    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    isTilesScaledToDpi = true
    controller.setZoom(4.0)
}

/**
 * Marcador de un espécimen con el color de su grupo: el primer toque muestra código y nombre;
 * tocarlo de nuevo ejecuta [onOpen].
 */
fun specimenMarker(map: MapView, specimen: Specimen, point: GeoPoint, onOpen: (() -> Unit)?): Marker =
    Marker(map).apply {
        position = point
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        icon = GroupColors.markerIcon(map.context, GroupColors.colorFor(specimen.group))
        title = "${specimen.catalogCode} · ${specimen.scientificName}"
        snippet = listOf(specimen.commonName, specimen.collectedOn).filter { it.isNotBlank() }.joinToString(" · ")
        setOnMarkerClickListener { marker, _ ->
            if (marker.isInfoWindowShown && onOpen != null) onOpen() else marker.showInfoWindow()
            true
        }
    }
