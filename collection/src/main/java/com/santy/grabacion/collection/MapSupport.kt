package com.santy.grabacion.collection

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.location.Location
import com.google.android.material.color.MaterialColors
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** Ajustes comunes de los mapas de la app (OpenStreetMap). */
fun MapView.configure(interactive: Boolean) {
    setTileSource(TileSourceFactory.MAPNIK)
    setMultiTouchControls(interactive)
    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    isTilesScaledToDpi = true
    controller.setZoom(4.0)
}

/**
 * Capa satelital de Esri (World Imagery). Son fotografías aéreas/satelitales de uso libre con
 * atribución; las teselas se piden como z/y/x, por eso se arma la URL a mano.
 */
val EsriSatellite: ITileSource = object : OnlineTileSourceBase(
    "Esri.WorldImagery", 0, 19, 256, ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Esri, Maxar, Earthstar Geographics",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
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
        title = specimen.catalogCode
        snippet = listOf(specimen.scientificName, specimen.commonName, specimen.collectedOn)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        setOnMarkerClickListener { marker, _ ->
            if (marker.isInfoWindowShown && onOpen != null) onOpen() else marker.showInfoWindow()
            true
        }
    }

/**
 * Capa de "mi ubicación" que dibuja un punto pequeño y discreto con un halo que late suavemente,
 * en vez del icono grande de persona. El latido se detiene solo si el sistema tiene las
 * animaciones desactivadas, para no gastar batería de más.
 */
class PulsingLocationOverlay(
    provider: IMyLocationProvider,
    mapView: MapView,
) : MyLocationNewOverlay(provider, mapView) {

    private val density = mapView.resources.displayMetrics.density
    private val dotRadius = 5f * density
    private val ringRadius = 15f * density
    private val animate = ValueAnimator.areAnimatorsEnabled()
    private val cycleMs = 1_600L
    private val color = MaterialColors.getColor(
        mapView, com.google.android.material.R.attr.colorPrimary, 0xFF3F6DB5.toInt(),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pixel = Point()

    init {
        setDrawAccuracyEnabled(false)
    }

    override fun drawMyLocation(canvas: Canvas, projection: Projection, location: Location) {
        projection.toPixels(GeoPoint(location), pixel)
        val x = pixel.x.toFloat()
        val y = pixel.y.toFloat()
        if (animate) {
            // El anillo crece y se desvanece en cada ciclo: late con suavidad.
            val t = (System.currentTimeMillis() % cycleMs).toFloat() / cycleMs
            paint.color = color
            paint.alpha = (110 * (1f - t)).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, dotRadius + (ringRadius - dotRadius) * t, paint)
        }
        paint.color = Color.WHITE
        paint.alpha = 255
        canvas.drawCircle(x, y, dotRadius + 2f * density, paint)
        paint.color = color
        paint.alpha = 255
        canvas.drawCircle(x, y, dotRadius, paint)
        if (animate) mMapView.postInvalidateDelayed(66)
    }
}
