package com.santy.grabacion.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.santy.grabacion.R
import com.santy.grabacion.applySystemBarPadding
import com.santy.grabacion.gate.Destinations
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** Ficha de un espécimen, con opciones para editarlo o eliminarlo. */
class SpecimenDetailActivity : AppCompatActivity() {

    private var specimenId = 0L
    private var specimen: Specimen? = null
    private lateinit var toolbar: MaterialToolbar
    private lateinit var photo: ImageView
    private lateinit var fields: LinearLayout
    private lateinit var map: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        MapSetup.init(this)
        setContentView(R.layout.activity_specimen_detail)
        findViewById<View>(R.id.root).applySystemBarPadding()

        specimenId = intent.getLongExtra(Destinations.EXTRA_SPECIMEN_ID, 0L)
        if (specimenId == 0L) return finish()

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.specimen_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> startActivity(Destinations.editSpecimen(this, specimenId))
                R.id.action_delete -> confirmDelete()
            }
            true
        }
        photo = findViewById(R.id.photo)
        fields = findViewById(R.id.fields)
        map = findViewById(R.id.map)
        map.configure(interactive = false)
        // Vista previa fija dentro de la ficha; para moverse está «Abrir en Maps» o el mapa de la colección.
        map.setOnTouchListener { _, _ -> true }
        findViewById<View>(R.id.openInMapsButton).setOnClickListener {
            specimen?.let { SpecimenLocation.openInMaps(this, it) }
        }
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        lifecycleScope.launch {
            val loaded = SpecimenRepository.get(this@SpecimenDetailActivity, specimenId) ?: return@launch finish()
            specimen = loaded
            show(loaded)
        }
    }

    private fun show(specimen: Specimen) {
        toolbar.title = specimen.catalogCode
        findViewById<TextView>(R.id.scientificName).text = specimen.scientificName
        findViewById<TextView>(R.id.commonName).apply {
            text = specimen.commonName
            visibility = if (specimen.commonName.isBlank()) View.GONE else View.VISIBLE
        }

        val name = specimen.photo
        photo.visibility = if (name == null) View.GONE else View.VISIBLE
        if (name != null) Glide.with(this).load(SpecimenPhotos.file(this, name)).into(photo)

        val description = SpecimenLocation.describe(specimen)
        findViewById<View>(R.id.locationSection).visibility = if (description == null) View.GONE else View.VISIBLE
        if (description != null) {
            findViewById<TextView>(R.id.locationText).text = description
            val point = GeoPoint(specimen.latitude!!, specimen.longitude!!)
            map.overlays.removeAll { it is Marker }
            map.overlays.add(specimenMarker(map, specimen, point, onOpen = null))
            map.controller.setZoom(14.0)
            map.controller.setCenter(point)
            map.invalidate()
        }

        fields.removeAllViews()
        listOf(
            R.string.field_group to specimen.group,
            R.string.field_date to specimen.collectedOn,
            R.string.field_locality to specimen.locality,
            R.string.field_collector to specimen.collector,
            R.string.field_notes to specimen.notes,
        ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_field, fields, false)
            row.findViewById<TextView>(R.id.label).setText(label)
            row.findViewById<TextView>(R.id.value).text = value
            fields.addView(row)
        }
    }

    private fun confirmDelete() {
        val current = specimen ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_specimen_title, current.catalogCode))
            .setMessage(R.string.delete_specimen_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    SpecimenRepository.delete(this@SpecimenDetailActivity, current)
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
