package com.santy.grabacion.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.santy.grabacion.R
import com.santy.grabacion.applySystemBarPadding
import com.santy.grabacion.gate.Destinations

/** Ficha de un espécimen. */
class SpecimenDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_specimen_detail)
        findViewById<View>(R.id.root).applySystemBarPadding()

        val specimen = intent.getStringExtra(Destinations.EXTRA_SPECIMEN_ID)
            ?.let { SpecimenRepository.get(it) }
            ?: return finish()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = specimen.id
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.scientificName).text = specimen.scientificName
        findViewById<TextView>(R.id.commonName).text = specimen.commonName

        val fields = findViewById<LinearLayout>(R.id.fields)
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
}
