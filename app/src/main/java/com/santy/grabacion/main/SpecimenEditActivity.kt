package com.santy.grabacion.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputLayout
import com.santy.grabacion.R
import com.santy.grabacion.applySystemBarPadding
import com.santy.grabacion.gate.Destinations
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Formulario para añadir o editar un espécimen. */
class SpecimenEditActivity : AppCompatActivity() {

    private companion object {
        const val STATE_PHOTO = "photo"
        const val STATE_PENDING_CAPTURE = "pending_capture"
        const val PREFS = "coleccion"
        const val KEY_LAST_COLLECTOR = "ultimo_colector"
    }

    private lateinit var photoView: ImageView
    private lateinit var removePhotoButton: View
    private lateinit var scientificLayout: TextInputLayout
    private lateinit var scientific: EditText
    private lateinit var common: EditText
    private lateinit var group: EditText
    private lateinit var date: EditText
    private lateinit var locality: EditText
    private lateinit var collector: EditText
    private lateinit var notes: EditText

    /** Espécimen que se edita, o null si es nuevo. */
    private var original: Specimen? = null
    /** Foto mostrada ahora en el formulario (nombre de archivo). */
    private var photo: String? = null
    /** Archivo que la app de cámara está escribiendo. */
    private var pendingCapture: String? = null
    private var saved = false

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val captured = pendingCapture ?: return@registerForActivityResult
        pendingCapture = null
        if (ok) replacePhoto(captured) else SpecimenPhotos.delete(this, captured)
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                replacePhoto(SpecimenPhotos.import(this@SpecimenEditActivity, uri).name)
            } catch (e: Exception) {
                Toast.makeText(this@SpecimenEditActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_specimen_edit)
        findViewById<View>(R.id.root).applySystemBarPadding()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }

        photoView = findViewById(R.id.photo)
        removePhotoButton = findViewById(R.id.removePhotoButton)
        scientificLayout = findViewById(R.id.scientificNameLayout)
        scientific = findViewById(R.id.scientificNameInput)
        common = findViewById(R.id.commonNameInput)
        group = findViewById(R.id.groupInput)
        date = findViewById(R.id.dateInput)
        locality = findViewById(R.id.localityInput)
        collector = findViewById(R.id.collectorInput)
        notes = findViewById(R.id.notesInput)

        findViewById<View>(R.id.takePhotoButton).setOnClickListener { takePhotoWithPermission() }
        findViewById<View>(R.id.pickPhotoButton).setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        removePhotoButton.setOnClickListener { replacePhoto(null) }
        date.setOnClickListener { pickDate() }
        findViewById<View>(R.id.saveButton).setOnClickListener { save() }

        pendingCapture = savedInstanceState?.getString(STATE_PENDING_CAPTURE)
        val id = intent.getLongExtra(Destinations.EXTRA_SPECIMEN_ID, 0L)
        toolbar.setTitle(if (id == 0L) R.string.add_specimen else R.string.edit_specimen)
        if (id == 0L) {
            if (savedInstanceState == null) {
                date.setText(today())
                collector.setText(prefs().getString(KEY_LAST_COLLECTOR, ""))
            }
            restorePhoto(savedInstanceState)
        } else {
            lifecycleScope.launch {
                val specimen = SpecimenRepository.get(this@SpecimenEditActivity, id) ?: return@launch finish()
                original = specimen
                if (savedInstanceState == null) fill(specimen)
                restorePhoto(savedInstanceState)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PHOTO, photo)
        outState.putString(STATE_PENDING_CAPTURE, pendingCapture)
    }

    override fun onDestroy() {
        // Si se sale sin guardar, se borra la foto nueva que no llegó a usarse.
        if (isFinishing && !saved && photo != original?.photo) SpecimenPhotos.delete(this, photo)
        super.onDestroy()
    }

    private fun restorePhoto(state: Bundle?) {
        photo = if (state != null) state.getString(STATE_PHOTO) else original?.photo
        showPhoto()
    }

    private fun fill(specimen: Specimen) {
        scientific.setText(specimen.scientificName)
        common.setText(specimen.commonName)
        group.setText(specimen.group)
        date.setText(specimen.collectedOn)
        locality.setText(specimen.locality)
        collector.setText(specimen.collector)
        notes.setText(specimen.notes)
    }

    /** Muestra [name] como foto actual y borra la foto anterior si era nueva y no guardada. */
    private fun replacePhoto(name: String?) {
        val previous = photo
        if (previous != null && previous != original?.photo) SpecimenPhotos.delete(this, previous)
        photo = name
        showPhoto()
    }

    private fun showPhoto() {
        val name = photo
        if (name == null) {
            Glide.with(this).clear(photoView)
            photoView.setImageResource(R.drawable.ic_camera)
            photoView.scaleType = ImageView.ScaleType.CENTER
        } else {
            photoView.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(this).load(SpecimenPhotos.file(this, name)).into(photoView)
        }
        removePhotoButton.visibility = if (name == null) View.GONE else View.VISIBLE
    }

    private fun takePhotoWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val file = SpecimenPhotos.newFile(this)
        pendingCapture = file.name
        try {
            takePicture.launch(SpecimenPhotos.uriFor(this, file))
        } catch (e: Exception) {
            pendingCapture = null
            file.delete()
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun pickDate() {
        val format = dateFormat()
        val current = runCatching { format.parse(date.text.toString())?.time }.getOrNull()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.field_date)
            .setSelection(current ?: MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        picker.addOnPositiveButtonClickListener { millis -> date.setText(format.format(Date(millis))) }
        picker.show(supportFragmentManager, "fecha")
    }

    private fun save() {
        val name = scientific.text.toString().trim()
        if (name.isEmpty()) {
            scientificLayout.error = getString(R.string.required_field)
            return
        }
        scientificLayout.error = null
        val specimen = Specimen(
            id = original?.id ?: 0L,
            scientificName = name,
            commonName = common.text.toString().trim(),
            group = group.text.toString().trim(),
            collectedOn = date.text.toString().ifBlank { today() },
            locality = locality.text.toString().trim(),
            collector = collector.text.toString().trim(),
            notes = notes.text.toString().trim(),
            photo = photo,
        )
        lifecycleScope.launch {
            SpecimenRepository.save(this@SpecimenEditActivity, specimen)
            original?.photo?.takeIf { it != photo }?.let { SpecimenPhotos.delete(this@SpecimenEditActivity, it) }
            prefs().edit().putString(KEY_LAST_COLLECTOR, specimen.collector).apply()
            saved = true
            finish()
        }
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Fecha de hoy en la zona horaria del teléfono. */
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Fechas en UTC, igual que el selector de Material. */
    private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}
