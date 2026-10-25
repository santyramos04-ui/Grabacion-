package com.santy.grabacion.main

import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

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
