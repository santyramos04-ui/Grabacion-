package com.santy.grabacion.main

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.santy.grabacion.R
import com.santy.grabacion.applySystemBarPadding
import com.santy.grabacion.gate.Destinations
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** Mapa con todos los especímenes que tienen ubicación GPS. */
class CollectionMapActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private var myLocation: MyLocationNewOverlay? = null
    private var centered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        MapSetup.init(this)
        setContentView(R.layout.activity_collection_map)
        findViewById<View>(R.id.root).applySystemBarPadding()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }

        map = findViewById(R.id.map)
        map.configure(interactive = true)
        if (SpecimenLocation.hasPermission(this)) {
            myLocation = MyLocationNewOverlay(GpsMyLocationProvider(this), map).also { map.overlays.add(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        myLocation?.enableMyLocation()
        lifecycleScope.launch { showSpecimens() }
    }

    override fun onPause() {
        myLocation?.disableMyLocation()
        map.onPause()
        super.onPause()
    }

    private suspend fun showSpecimens() {
        val located = SpecimenRepository.all(this).filter { it.hasLocation }
        map.overlays.removeAll { it is Marker }
        val points = located.map { specimen ->
            val point = GeoPoint(specimen.latitude!!, specimen.longitude!!)
            map.overlays.add(specimenMarker(map, specimen, point) {
                startActivity(Destinations.specimen(this, specimen.id))
            })
            point
        }
        map.invalidate()
        findViewById<View>(R.id.emptyText).visibility = if (points.isEmpty()) View.VISIBLE else View.GONE
        if (!centered && points.isNotEmpty()) {
            centered = true
            map.post {
                if (points.size == 1) {
                    map.controller.setZoom(15.0)
                    map.controller.setCenter(points[0])
                } else {
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.3f), false, 64)
                }
            }
        }
    }
}

/** Ajustes comunes de los mapas de la app (OpenStreetMap). */
fun MapView.configure(interactive: Boolean) {
    setTileSource(TileSourceFactory.MAPNIK)
    setMultiTouchControls(interactive)
    zoomController.setVisibility(
        if (interactive) CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT
        else CustomZoomButtonsController.Visibility.NEVER
    )
    isTilesScaledToDpi = true
    controller.setZoom(4.0)
}

/**
 * Marcador de un espécimen: el primer toque muestra código y nombre; tocarlo de nuevo
 * ejecuta [onOpen].
 */
fun specimenMarker(map: MapView, specimen: Specimen, point: GeoPoint, onOpen: (() -> Unit)?): Marker =
    Marker(map).apply {
        position = point
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = "${specimen.catalogCode} · ${specimen.scientificName}"
        snippet = listOf(specimen.commonName, specimen.collectedOn).filter { it.isNotBlank() }.joinToString(" · ")
        setOnMarkerClickListener { marker, _ ->
            if (marker.isInfoWindowShown && onOpen != null) onOpen() else marker.showInfoWindow()
            true
        }
    }
