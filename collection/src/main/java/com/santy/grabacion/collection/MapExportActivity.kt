package com.santy.grabacion.collection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.santy.grabacion.core.applySystemBarPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * Editor previo a la exportación del mapa: antes de guardar, se puede elegir la proporción, el
 * tamaño (dimensiones), el detalle, el formato y el nombre, con una vista previa real. La imagen
 * se dibuja en un mapa fuera de pantalla (sin botones ni controles) a la resolución elegida, así
 * mantiene la calidad y los elementos del mapa (marcadores, polígono, título, leyenda y
 * atribución). Los datos a dibujar llegan por [MapExportData], que prepara el fragmento del mapa.
 */
class MapExportActivity : AppCompatActivity() {

    private lateinit var req: MapExportData.Request
    private lateinit var preview: ImageView
    private lateinit var progress: CircularProgressIndicator
    private lateinit var dimensionsLabel: TextView
    private lateinit var customSizeLayout: TextInputLayout
    private lateinit var customSizeInput: TextInputEditText
    private lateinit var nameInput: TextInputEditText

    private var ratioIndex = 0
    private var longestEdge = 1920
    private var renderZoom = 0.0
    private var format = CollectionExport.ImageFormat.PNG

    private var previewJob: Job? = null
    private var saving = false

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
        renderZoom = req.zoom
        MapSetup.init(this)
        setContentView(R.layout.activity_map_export)
        findViewById<View>(R.id.root).applySystemBarPadding()

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        preview = findViewById(R.id.preview)
        progress = findViewById(R.id.previewProgress)
        dimensionsLabel = findViewById(R.id.dimensionsLabel)
        customSizeLayout = findViewById(R.id.customSizeLayout)
        customSizeInput = findViewById(R.id.customSizeInput)
        nameInput = findViewById(R.id.nameInput)

        setUpRatio()
        setUpSize()
        setUpDetail()
        setUpFormat()

        customSizeInput.doAfterChange {
            it.toIntOrNull()?.let { value -> longestEdge = value.coerceIn(256, 4096) }
            refresh()
        }

        findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener { save() }

        updateDimensionsLabel()
        schedulePreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) MapExportData.request = null
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
            refresh()
        }
        group.check(R.id.ratioScreen)
    }

    private fun setUpSize() {
        val group = findViewById<ChipGroup>(R.id.sizeGroup)
        group.setOnCheckedStateChangeListener { _, checked ->
            when (checked.firstOrNull()) {
                R.id.size1080 -> { longestEdge = 1080; customSizeLayout.visibility = View.GONE }
                R.id.size2560 -> { longestEdge = 2560; customSizeLayout.visibility = View.GONE }
                R.id.sizeCustom -> {
                    customSizeLayout.visibility = View.VISIBLE
                    customSizeInput.text?.toString()?.toIntOrNull()?.let { longestEdge = it.coerceIn(256, 4096) }
                }
                else -> { longestEdge = 1920; customSizeLayout.visibility = View.GONE }
            }
            refresh()
        }
        group.check(R.id.size1920)
    }

    private fun setUpDetail() {
        val label = findViewById<TextView>(R.id.detailLabel)
        fun update() {
            val diff = Math.round(renderZoom - req.zoom).toInt()
            label.text = getString(R.string.map_export_detail_value, if (diff > 0) "+$diff" else diff.toString())
        }
        findViewById<View>(R.id.detailLess).setOnClickListener {
            renderZoom = (renderZoom - 1).coerceAtLeast(req.zoom - 2)
            update(); schedulePreview()
        }
        findViewById<View>(R.id.detailMore).setOnClickListener {
            renderZoom = (renderZoom + 1).coerceAtMost(req.zoom + 3).coerceAtMost(19.0)
            update(); schedulePreview()
        }
        update()
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
        val pair = if (r >= 1f) longest to Math.round(longest / r) else Math.round(longest * r) to longest
        return pair.first.coerceIn(256, 4096) to pair.second.coerceIn(256, 4096)
    }

    private fun updateDimensionsLabel() {
        val (w, h) = dims(longestEdge)
        dimensionsLabel.text = getString(R.string.map_export_dimensions, w, h)
    }

    private fun refresh() {
        updateDimensionsLabel()
        schedulePreview()
    }

    private fun schedulePreview() {
        if (saving) return
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(280)
            progress.visibility = View.VISIBLE
            // La vista previa usa una resolución menor para ser rápida, con la misma composición.
            val (fullW, fullH) = dims(longestEdge)
            val previewLongest = minOf(maxOf(fullW, fullH), 960)
            val (pw, ph) = dims(previewLongest)
            val bitmap = render(pw, ph)
            preview.setImageBitmap(bitmap)
            progress.visibility = View.GONE
        }
    }

    private fun save() {
        if (saving) return
        saving = true
        previewJob?.cancel()
        progress.visibility = View.VISIBLE
        val name = CollectionExport.imageFileName(format, nameInput.text?.toString())
        lifecycleScope.launch {
            try {
                val (w, h) = dims(longestEdge)
                val bitmap = render(w, h)
                val saved = CollectionExport.saveImage(this@MapExportActivity, bitmap, format, name)
                progress.visibility = View.GONE
                Toast.makeText(
                    this@MapExportActivity,
                    getString(R.string.export_saved_message, saved.name, saved.folder.replace("Pictures", "Imágenes")),
                    Toast.LENGTH_LONG,
                ).show()
                CollectionExport.share(this@MapExportActivity, saved)
                finish()
            } catch (e: Exception) {
                progress.visibility = View.GONE
                saving = false
                Toast.makeText(this@MapExportActivity, getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun tileSource(): ITileSource = when (req.layerIndex) {
        1 -> TileSourceFactory.OpenTopo
        2 -> EsriSatellite
        else -> TileSourceFactory.MAPNIK
    }

    private fun attributionText(): String = getString(
        when (req.layerIndex) {
            1 -> R.string.map_attribution_topo
            2 -> R.string.map_attribution_satellite
            else -> R.string.map_attribution
        }
    )

    /** Dibuja el mapa fuera de pantalla al tamaño pedido y le añade título, leyenda y atribución. */
    private suspend fun render(mapW: Int, mapH: Int): Bitmap = withContext(Dispatchers.Main) {
        val mapBitmap = renderMap(mapW, mapH)
        compose(mapBitmap)
    }

    private suspend fun renderMap(mapW: Int, mapH: Int): Bitmap {
        val mv = MapView(this)
        mv.setTileSource(tileSource())
        mv.isTilesScaledToDpi = false
        mv.setMultiTouchControls(false)
        mv.setUseDataConnection(true)
        mv.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mv.measure(
            View.MeasureSpec.makeMeasureSpec(mapW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(mapH, View.MeasureSpec.EXACTLY),
        )
        mv.layout(0, 0, mapW, mapH)
        mv.controller.setZoom(renderZoom)
        mv.setExpectedCenter(GeoPoint(req.centerLat, req.centerLng))

        req.hull?.let { hull ->
            if (hull.size >= 3) mv.overlays.add(Polygon(mv).apply {
                setPoints(hull.map { GeoPoint(it.first, it.second) } + GeoPoint(hull[0].first, hull[0].second))
                fillPaint.color = req.hullColor and 0x00FFFFFF or 0x26000000
                outlinePaint.color = req.hullColor
                outlinePaint.strokeWidth = 3f * density
                setInfoWindow(null)
            })
        }
        req.points.forEach { p ->
            mv.overlays.add(Marker(mv).apply {
                position = GeoPoint(p.lat, p.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = GroupColors.markerIcon(this@MapExportActivity, p.color)
                setInfoWindow(null)
            })
        }

        val bitmap = Bitmap.createBitmap(mapW, mapH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val tiles = mv.overlayManager.tilesOverlay
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (true) {
            canvas.drawColor(Color.WHITE)
            mv.draw(canvas) // dibuja lo disponible y pide las teselas que falten
            val states = tiles.tileStates
            val done = states != null && states.total > 0 && states.isDone
            if (done || SystemClock.uptimeMillis() > deadline) break
            delay(220)
        }
        mv.onDetach()
        return bitmap
    }

    /** Añade título arriba y resumen, leyenda y atribución abajo, en proporción al tamaño. */
    private fun compose(mapBitmap: Bitmap): Bitmap {
        val w = mapBitmap.width
        val unit = w / 1000f
        val titleSize = 28 * unit
        val bodySize = 20 * unit
        val attrSize = 13 * unit
        val pad = 22 * unit
        val line = 30 * unit
        val header = pad * 2 + titleSize
        val footer = pad * 2 + line * (req.legend.size + 2)
        val result = Bitmap.createBitmap(w, (header + mapBitmap.height + footer).toInt(), Bitmap.Config.ARGB_8888)
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
