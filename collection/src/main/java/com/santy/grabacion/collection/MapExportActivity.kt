package com.santy.grabacion.collection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.santy.grabacion.core.applySystemBarPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Editor previo a la exportación del mapa. Arriba hay un mapa real dentro de un recuadro con la
 * proporción elegida: se puede mover y acercar, encuadrar toda la colección o ir a una coordenada
 * (escribiéndola o manteniendo pulsado el mapa), y se exporta exactamente lo que queda dentro del
 * recuadro. Debajo se eligen capa, proporción, tamaño, formato y nombre.
 *
 * La imagen final se dibuja en un mapa fuera de pantalla con teselas sin escalar y el zoom
 * ajustado, así muestra la misma zona con más detalle cuanto mayor sea el tamaño. No incluye
 * botones ni controles. Los datos llegan por [MapExportData], que prepara el fragmento del mapa.
 */
class MapExportActivity : AppCompatActivity() {

    private companion object {
        const val MAX_SIDE = 4096
        const val MIN_SIDE = 256
        const val PREVIEW_SIDE = 1080
        /** Color del alfiler de la coordenada marcada, distinto de los grupos. */
        const val PIN_COLOR = 0xFF1B1B1B.toInt()
    }

    private lateinit var req: MapExportData.Request
    private lateinit var frame: AspectFrameLayout
    private lateinit var frameMap: MapView
    private lateinit var frameAttribution: TextView
    private lateinit var scrim: View
    private lateinit var centerLabel: TextView
    private lateinit var dimensionsLabel: TextView
    private lateinit var customSizeLayout: TextInputLayout
    private lateinit var customSizeInput: TextInputEditText
    private lateinit var nameInput: TextInputEditText

    private var layerIndex = 0
    private var ratioIndex = 0
    private var longestEdge = 1920
    private var format = CollectionExport.ImageFormat.PNG
    /** Coordenada marcada (se dibuja y se anota en la imagen). */
    private var pin: GeoPoint? = null
    private var pinMarker: Marker? = null
    private var busy = false

    private val density get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = MapExportData.request
        if (request == null) {
            Toast.makeText(this, R.string.map_export_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        req = request
        layerIndex = req.layerIndex
        MapSetup.init(this)
        setContentView(R.layout.activity_map_export)
        findViewById<View>(R.id.root).applySystemBarPadding()
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        frame = findViewById(R.id.frame)
        frameMap = findViewById(R.id.frameMap)
        frameAttribution = findViewById(R.id.frameAttribution)
        scrim = findViewById(R.id.renderScrim)
        centerLabel = findViewById(R.id.centerLabel)
        dimensionsLabel = findViewById(R.id.dimensionsLabel)
        customSizeLayout = findViewById(R.id.customSizeLayout)
        customSizeInput = findViewById(R.id.customSizeInput)
        nameInput = findViewById(R.id.nameInput)

        frame.maxHeightPx = (resources.displayMetrics.heightPixels * 0.42f).roundToInt()
        setUpFrameMap()

        findViewById<View>(R.id.fitButton).setOnClickListener { fitCollection() }
        findViewById<View>(R.id.coordinateButton).setOnClickListener { askCoordinate() }
        findViewById<View>(R.id.previewButton).setOnClickListener { showPreview() }
        findViewById<View>(R.id.cancelButton).setOnClickListener { finish() }
        findViewById<View>(R.id.saveButton).setOnClickListener { save() }

        setUpLayer()
        setUpRatio()
        setUpSize()
        setUpFormat()
        customSizeInput.doAfterChange {
            it.toIntOrNull()?.let { value -> longestEdge = value.coerceIn(MIN_SIDE, MAX_SIDE) }
            updateDimensionsLabel()
        }
        updateDimensionsLabel()
        updateCenterLabel()
    }

    override fun onResume() {
        super.onResume()
        if (::frameMap.isInitialized) frameMap.onResume()
    }

    override fun onPause() {
        if (::frameMap.isInitialized) frameMap.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::frameMap.isInitialized) frameMap.onDetach()
        if (isFinishing) MapExportData.request = null
        super.onDestroy()
    }

    // ---- Mapa del recuadro -------------------------------------------------------------------

    private fun setUpFrameMap() {
        frameMap.configure(interactive = true)
        frameMap.setTileSource(tileSource(layerIndex))
        frameMap.controller.setZoom(req.zoom)
        frameMap.controller.setCenter(GeoPoint(req.centerLat, req.centerLng))
        // Mantener pulsado marca un punto; va al fondo para que los marcadores reciban sus toques.
        frameMap.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let { setPin(it, move = false) }
                return true
            }
        }))
        addDataOverlays(frameMap, 1f)
        frameMap.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                updateCenterLabel()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                updateCenterLabel()
                return false
            }
        })
        updateAttribution()
    }

    /** Polígono y marcadores de la colección; [scale] agranda trazos y alfileres al exportar. */
    private fun addDataOverlays(map: MapView, scale: Float) {
        req.hull?.takeIf { it.size >= 3 }?.let { hull ->
            map.overlays.add(Polygon(map).apply {
                setPoints(hull.map { GeoPoint(it.first, it.second) } + GeoPoint(hull[0].first, hull[0].second))
                fillPaint.color = req.hullColor and 0x00FFFFFF or 0x26000000
                outlinePaint.color = req.hullColor
                outlinePaint.strokeWidth = 3f * density * scale
                setInfoWindow(null)
            })
        }
        req.points.forEach { p ->
            map.overlays.add(staticMarker(map, GeoPoint(p.lat, p.lng), p.color, scale))
        }
    }

    private fun staticMarker(map: MapView, point: GeoPoint, color: Int, scale: Float) = Marker(map).apply {
        position = point
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        icon = GroupColors.markerIcon(this@MapExportActivity, color, scale)
        setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> true }
    }

    private fun fitCollection() {
        val points = req.points.map { GeoPoint(it.lat, it.lng) }
        when {
            points.isEmpty() -> Toast.makeText(this, R.string.map_export_no_points, Toast.LENGTH_LONG).show()
            points.distinct().size == 1 -> {
                frameMap.controller.setZoom(15.0)
                frameMap.controller.setCenter(points[0])
            }
            else -> frameMap.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.3f), true, 48)
        }
    }

    private fun setPin(point: GeoPoint, move: Boolean) {
        pin = point
        pinMarker?.let { frameMap.overlays.remove(it) }
        pinMarker = staticMarker(frameMap, point, PIN_COLOR, 1f).also { frameMap.overlays.add(it) }
        if (move) frameMap.controller.animateTo(point, maxOf(frameMap.zoomLevelDouble, 15.0), 600L)
        frameMap.invalidate()
        updateCenterLabel()
    }

    private fun clearPin() {
        pin = null
        pinMarker?.let { frameMap.overlays.remove(it) }
        pinMarker = null
        frameMap.invalidate()
        updateCenterLabel()
    }

    private fun askCoordinate() {
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText((pin ?: frameMap.mapCenter).let { "${coord(it.latitude)}, ${coord(it.longitude)}" })
            selectAll()
        }
        val field = TextInputLayout(this).apply {
            hint = getString(R.string.map_export_coordinate_hint)
            addView(input)
            val pad = (20 * density).roundToInt()
            setPadding(pad, (8 * density).roundToInt(), pad, 0)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.map_export_coordinate_title)
            .setMessage(R.string.map_export_coordinate_message)
            .setView(field)
            .setPositiveButton(R.string.map_export_coordinate_go, null)
            .setNegativeButton(R.string.cancel, null)
            .apply { if (pin != null) setNeutralButton(R.string.map_export_coordinate_clear) { _, _ -> clearPin() } }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val point = parseCoordinate(input.text?.toString().orEmpty())
                if (point == null) {
                    field.error = getString(R.string.map_export_coordinate_invalid)
                } else {
                    setPin(point, move = true)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    /** Lee "lat, lng" en grados decimales; acepta punto o coma decimal. */
    private fun parseCoordinate(text: String): GeoPoint? {
        fun numbers(pattern: String) = Regex(pattern).findAll(text).map { it.value.replace(',', '.').toDouble() }.toList()
        val values = numbers("-?\\d+(?:\\.\\d+)?").takeIf { it.size == 2 }
            ?: numbers("-?\\d+(?:,\\d+)?").takeIf { it.size == 2 }
            ?: return null
        val (lat, lng) = values
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return GeoPoint(lat, lng)
    }

    private fun updateCenterLabel() {
        val center = frameMap.mapCenter
        val zoom = String.format(Locale.getDefault(), "%.1f", frameMap.zoomLevelDouble)
        val lines = mutableListOf(getString(R.string.map_export_center, coord(center.latitude), coord(center.longitude), zoom))
        pin?.let { lines += getString(R.string.map_export_pin, coord(it.latitude), coord(it.longitude)) }
        centerLabel.text = lines.joinToString("\n")
    }

    private fun updateAttribution() {
        frameAttribution.text = attributionText()
    }

    // ---- Controles -----------------------------------------------------------------------------

    private fun setUpLayer() {
        val group = findViewById<ChipGroup>(R.id.layerGroup)
        group.check(
            when (layerIndex) {
                1 -> R.id.layerTopo
                2 -> R.id.layerSatellite
                else -> R.id.layerStandard
            }
        )
        group.setOnCheckedStateChangeListener { _, checked ->
            layerIndex = when (checked.firstOrNull()) {
                R.id.layerTopo -> 1
                R.id.layerSatellite -> 2
                else -> 0
            }
            frameMap.setTileSource(tileSource(layerIndex))
            updateAttribution()
        }
    }

    private fun setUpRatio() {
        val group = findViewById<ChipGroup>(R.id.ratioGroup)
        group.setOnCheckedStateChangeListener { _, checked ->
            ratioIndex = when (checked.firstOrNull()) {
                R.id.ratio11 -> 1
                R.id.ratio43 -> 2
                R.id.ratio32 -> 3
                R.id.ratio169 -> 4
                else -> 0
            }
            applyRatio()
        }
        group.check(R.id.ratioScreen)
        applyRatio()
    }

    /** Cambia la forma del recuadro conservando el centro del mapa. */
    private fun applyRatio() {
        val ratio = ratioValue()
        if (ratio != frame.ratio) {
            val center = frameMap.mapCenter
            frameMap.doOnNextLayout { frameMap.controller.setCenter(center) }
            frame.ratio = ratio
        }
        updateDimensionsLabel()
    }

    private fun setUpSize() {
        val group = findViewById<ChipGroup>(R.id.sizeGroup)
        group.setOnCheckedStateChangeListener { _, checked ->
            when (checked.firstOrNull()) {
                R.id.size1080 -> { longestEdge = 1080; customSizeLayout.visibility = View.GONE }
                R.id.size2560 -> { longestEdge = 2560; customSizeLayout.visibility = View.GONE }
                R.id.sizeCustom -> {
                    customSizeLayout.visibility = View.VISIBLE
                    customSizeInput.text?.toString()?.toIntOrNull()?.let { longestEdge = it.coerceIn(MIN_SIDE, MAX_SIDE) }
                }
                else -> { longestEdge = 1920; customSizeLayout.visibility = View.GONE }
            }
            updateDimensionsLabel()
        }
        group.check(R.id.size1920)
    }

    private fun setUpFormat() {
        val group = findViewById<ChipGroup>(R.id.formatGroup)
        group.setOnCheckedStateChangeListener { _, checked ->
            format = when (checked.firstOrNull()) {
                R.id.fmtJpeg -> CollectionExport.ImageFormat.JPEG
                R.id.fmtWebp -> CollectionExport.ImageFormat.WEBP
                else -> CollectionExport.ImageFormat.PNG
            }
        }
        group.check(R.id.fmtPng)
    }

    private fun ratioValue(): Float = when (ratioIndex) {
        1 -> 1f
        2 -> 4f / 3f
        3 -> 3f / 2f
        4 -> 16f / 9f
        else -> req.screenRatio.takeIf { it > 0f } ?: (4f / 3f)
    }

    /** Ancho y alto del área de mapa a partir de la proporción y el lado mayor. */
    private fun dims(longest: Int): Pair<Int, Int> {
        val r = ratioValue()
        val (w, h) = if (r >= 1f) longest to (longest / r).roundToInt() else (longest * r).roundToInt() to longest
        return w.coerceIn(MIN_SIDE, MAX_SIDE) to h.coerceIn(MIN_SIDE, MAX_SIDE)
    }

    private fun updateDimensionsLabel() {
        val (w, h) = dims(longestEdge)
        dimensionsLabel.text = getString(R.string.map_export_dimensions, w, h)
    }

    // ---- Vista previa y guardado ---------------------------------------------------------------

    private fun showPreview() {
        if (busy) return
        lifecycleScope.launch {
            val (w, h) = dims(minOf(longestEdge, PREVIEW_SIDE))
            val bitmap = try {
                render(w, h)
            } catch (e: OutOfMemoryError) {
                Toast.makeText(this@MapExportActivity, R.string.map_export_too_big, Toast.LENGTH_LONG).show()
                return@launch
            }
            val image = ImageView(this@MapExportActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = getString(R.string.map_export_preview)
                setImageBitmap(bitmap)
                val pad = (12 * density).roundToInt()
                setPadding(pad, pad, pad, 0)
            }
            MaterialAlertDialogBuilder(this@MapExportActivity)
                .setTitle(R.string.map_export_preview_button)
                .setView(image)
                .setPositiveButton(R.string.map_export_save) { _, _ -> save() }
                .setNegativeButton(R.string.done, null)
                .show()
        }
    }

    private fun save() {
        if (busy) return
        val name = CollectionExport.imageFileName(format, nameInput.text?.toString())
        lifecycleScope.launch {
            try {
                val (w, h) = dims(longestEdge)
                val bitmap = render(w, h)
                val saved = CollectionExport.saveImage(this@MapExportActivity, bitmap, format, name)
                bitmap.recycle()
                Toast.makeText(
                    this@MapExportActivity,
                    getString(R.string.export_saved_message, saved.name, saved.folder.replace("Pictures", "Imágenes")),
                    Toast.LENGTH_LONG,
                ).show()
                CollectionExport.share(this@MapExportActivity, saved)
                finish()
            } catch (e: OutOfMemoryError) {
                Toast.makeText(this@MapExportActivity, R.string.map_export_too_big, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MapExportActivity, getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun tileSource(index: Int): ITileSource = when (index) {
        1 -> TileSourceFactory.OpenTopo
        2 -> EsriSatellite
        else -> TileSourceFactory.MAPNIK
    }

    private fun attributionText(): String = getString(
        when (layerIndex) {
            1 -> R.string.map_attribution_topo
            2 -> R.string.map_attribution_satellite
            else -> R.string.map_attribution
        }
    )

    /**
     * Imagen de [outW] × [outH] px con lo que se ve en el recuadro, más título, leyenda y
     * atribución. En pantalla las teselas se dibujan escaladas a la densidad; aquí se usan sin
     * escalar y se sube el zoom para cubrir exactamente la misma zona con más detalle.
     */
    private suspend fun render(outW: Int, outH: Int): Bitmap {
        busy = true
        scrim.visibility = View.VISIBLE
        try {
            val frameW = frameMap.width.coerceAtLeast(1)
            val center = frameMap.mapCenter
            val source = tileSource(layerIndex)
            var zoom = frameMap.zoomLevelDouble + ln(outW * density / frameW.toDouble()) / ln(2.0)
            var renderW = outW
            var renderH = outH
            val maxZoom = source.maximumZoomLevel.toDouble()
            if (zoom > maxZoom) {
                // Más allá del zoom máximo de la capa no hay más detalle: se dibuja a menor tamaño
                // y se amplía, para conservar el mismo encuadre.
                val factor = 2.0.pow(zoom - maxZoom)
                renderW = (outW / factor).roundToInt().coerceAtLeast(64)
                renderH = (outH / factor).roundToInt().coerceAtLeast(64)
                zoom = maxZoom
            }
            val map = renderMap(source, renderW, renderH, zoom, GeoPoint(center.latitude, center.longitude), renderW.toFloat() / frameW)
            val sized = if (renderW == outW && renderH == outH) map
            else Bitmap.createScaledBitmap(map, outW, outH, true).also { map.recycle() }
            return compose(sized).also { sized.recycle() }
        } finally {
            // El tamaño de tesela de osmdroid es global: se restaura el del mapa en pantalla.
            frameMap.isTilesScaledToDpi = true
            frameMap.invalidate()
            scrim.visibility = View.GONE
            busy = false
        }
    }

    private suspend fun renderMap(source: ITileSource, w: Int, h: Int, zoom: Double, center: GeoPoint, scale: Float): Bitmap {
        val mv = MapView(this)
        mv.setTileSource(source)
        mv.isTilesScaledToDpi = false
        mv.setMultiTouchControls(false)
        mv.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mv.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        mv.layout(0, 0, w, h)
        mv.controller.setZoom(zoom)
        mv.setExpectedCenter(center)
        addDataOverlays(mv, scale)
        pin?.let { mv.overlays.add(staticMarker(mv, it, PIN_COLOR, scale)) }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val tiles = mv.overlayManager.tilesOverlay
        val deadline = SystemClock.uptimeMillis() + 30_000
        var lastLoaded = -1
        var stalledSince = SystemClock.uptimeMillis()
        try {
            while (true) {
                canvas.drawColor(Color.WHITE)
                mv.draw(canvas) // dibuja lo disponible y pide las teselas que falten
                // osmdroid marca la pasada como "terminada" aunque falten teselas (las que aún se
                // descargan cuentan como no encontradas), así que se comprueba que estén todas.
                val states = tiles.tileStates
                val loaded = states.upToDate + states.expired
                if (states.total > 0 && loaded >= states.total) break
                val now = SystemClock.uptimeMillis()
                if (loaded != lastLoaded) {
                    lastLoaded = loaded
                    stalledSince = now
                }
                // Si nada avanza en 8 s (sin conexión o teselas inexistentes) se usa lo que haya.
                if (now > deadline || (loaded > 0 && now - stalledSince > 8_000)) break
                delay(250)
            }
        } finally {
            mv.onDetach()
        }
        return bitmap
    }

    /** Añade título arriba y resumen, punto, leyenda y atribución abajo, en proporción al tamaño. */
    private fun compose(mapBitmap: Bitmap): Bitmap {
        val w = mapBitmap.width
        val unit = w / 1000f
        val titleSize = 28 * unit
        val bodySize = 20 * unit
        val attrSize = 13 * unit
        val pad = 22 * unit
        val line = 30 * unit
        val pinLine = pin?.let { getString(R.string.map_export_pin_line, coord(it.latitude), coord(it.longitude)) }
        val header = pad * 2 + titleSize
        val footer = pad * 2 + line * (req.legend.size + 2 + if (pinLine != null) 1 else 0)
        val result = Bitmap.createBitmap(w, (header + mapBitmap.height + footer).roundToInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF211A14.toInt(); textSize = titleSize; isFakeBoldText = true }
        canvas.drawText(req.title, pad, header - pad, text)
        canvas.drawBitmap(mapBitmap, 0f, header, null)

        text.isFakeBoldText = false
        text.textSize = bodySize
        var y = header + mapBitmap.height + pad + bodySize
        canvas.drawText(req.summary, pad, y, text)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        if (pinLine != null) {
            y += line
            dot.color = PIN_COLOR
            canvas.drawCircle(pad + bodySize * 0.4f, y - bodySize * 0.35f, bodySize * 0.4f, dot)
            canvas.drawText(pinLine, pad + bodySize, y, text)
        }
        req.legend.forEach { entry ->
            y += line
            dot.color = entry.color
            canvas.drawCircle(pad + bodySize * 0.4f, y - bodySize * 0.35f, bodySize * 0.4f, dot)
            canvas.drawText("${entry.label} (${entry.count})", pad + bodySize, y, text)
        }
        text.textSize = attrSize
        text.color = 0xFF666666.toInt()
        canvas.drawText(attributionText(), pad, y + line, text)
        return result
    }

    private fun coord(value: Double) = String.format(Locale.US, "%.5f", value)
}

/** Datos que el mapa prepara para el editor de exportación (traspaso en memoria entre pantallas). */
object MapExportData {
    class Point(val lat: Double, val lng: Double, val color: Int)
    class LegendEntry(val label: String, val count: Int, val color: Int)
    class Request(
        val centerLat: Double,
        val centerLng: Double,
        val zoom: Double,
        val layerIndex: Int,
        val screenRatio: Float,
        val points: List<Point>,
        val hull: List<Pair<Double, Double>>?,
        val hullColor: Int,
        val title: String,
        val summary: String,
        val legend: List<LegendEntry>,
    )

    @Volatile
    var request: Request? = null
}

/** Pequeño ayudante para reaccionar a los cambios de un campo de texto. */
private inline fun TextInputEditText.doAfterChange(crossinline afterChanged: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = afterChanged(s?.toString().orEmpty())
    })
}
