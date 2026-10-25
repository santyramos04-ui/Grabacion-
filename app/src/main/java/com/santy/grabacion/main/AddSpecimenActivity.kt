package com.santy.grabacion.main

import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputLayout
import com.santy.grabacion.R
import com.santy.grabacion.applySystemBarPadding

/** Formulario mínimo para añadir un espécimen al inventario en memoria. */
class AddSpecimenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_specimen)
        findViewById<View>(R.id.root).applySystemBarPadding()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }

        val scientificLayout = findViewById<TextInputLayout>(R.id.scientificNameLayout)
        val scientific = findViewById<EditText>(R.id.scientificNameInput)
        val common = findViewById<EditText>(R.id.commonNameInput)
        val group = findViewById<EditText>(R.id.groupInput)
        val locality = findViewById<EditText>(R.id.localityInput)
        val notes = findViewById<EditText>(R.id.notesInput)

        findViewById<View>(R.id.saveButton).setOnClickListener {
            val name = scientific.text.toString().trim()
            if (name.isEmpty()) {
                scientificLayout.error = getString(R.string.required_field)
                return@setOnClickListener
            }
            SpecimenRepository.add(
                name,
                common.text.toString().trim(),
                group.text.toString().trim(),
                locality.text.toString().trim(),
                notes.text.toString().trim(),
            )
            finish()
        }
    }
}
