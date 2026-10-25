package com.santy.grabacion.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.santy.grabacion.R
import com.santy.grabacion.applySystemBarPadding
import com.santy.grabacion.gate.Destinations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Formulario para añadir o editar un espécimen. Al escribir el nombre común sugiere especies
 * de iNaturalist observadas en la localidad indicada.
 */
class SpecimenEditActivity : AppCompatActivity() {

    private companion object {
        const val STATE_PHOTO = "photo"
        const val STATE_PENDING_CAPTURE = "pending_capture"
        const val STATE_LOCATION = "location"
        const val PREFS = "coleccion"
        const val KEY_LAST_COLLECTOR = "ultimo_colector"
        const val KEY_ASKED_MEDIA_LOCATION = "pidio_ubicacion_fotos"
    }

    private lateinit var photoView: ImageView
    private lateinit var removePhotoButton: View
    private lateinit var scientificLayout: TextInputLayout
    private lateinit var scientific: EditText
    private lateinit var commonLayout: TextInputLayout
    private lateinit var common: MaterialAutoCompleteTextView
    private lateinit var group: EditText
    private lateinit var date: EditText
    private lateinit var locality: EditText
    private lateinit var collector: EditText
    private lateinit var notes: EditText
    private lateinit var locationText: TextView
    private lateinit var removeLocationButton: View

    /** Punto de colecta: latitud, longitud, altitud (o NaN) y precisión (o NaN). */
    private var location: DoubleArray? = null
    private var locationJob: Job? = null

    /** Espécimen que se edita, o null si es nuevo. */
    private var original: Specimen? = null
    /** Foto mostrada ahora en el formulario (nombre de archivo). */
    private var photo: String? = null
    /** Archivo que la app de cámara está escribiendo. */
    private var pendingCapture: String? = null
    private var saved = false
    private val suggestions by lazy { SuggestionAdapter(this) }
    private var suggestJob: Job? = null
    /** Evita buscar de nuevo cuando el texto lo pone la propia sugerencia elegida. */
    private var applyingSuggestion = false

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val captured = pendingCapture ?: return@registerForActivityResult
        pendingCapture = null
        if (ok) replacePhoto(captured) else SpecimenPhotos.delete(this, captured)
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val file = SpecimenPhotos.import(this@SpecimenEditActivity, PhotoMetadata.candidates(this@SpecimenEditActivity, uri))
                replacePhoto(file.name)
                PhotoMetadata.read(file)?.let { applyMetadata(it) }
            } catch (e: Exception) {
                Toast.makeText(this@SpecimenEditActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Pide (una vez) el permiso para leer la ubicación de las fotos y luego abre el selector. */
    private val mediaLocationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pickPhoto.launch(arrayOf("image/*"))
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (SpecimenLocation.hasPermission(this)) captureLocation() else locationText.setText(R.string.location_no_permission)
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
        commonLayout = findViewById(R.id.commonNameLayout)
        common = findViewById(R.id.commonNameInput)
        group = findViewById(R.id.groupInput)
        date = findViewById(R.id.dateInput)
        locality = findViewById(R.id.localityInput)
        collector = findViewById(R.id.collectorInput)
        notes = findViewById(R.id.notesInput)
        locationText = findViewById(R.id.locationText)
        removeLocationButton = findViewById(R.id.removeLocationButton)
        findViewById<View>(R.id.refreshLocationButton).setOnClickListener { requestLocation() }
        removeLocationButton.setOnClickListener { setLocation(null) }

        findViewById<View>(R.id.takePhotoButton).setOnClickListener { takePhotoWithPermission() }
        findViewById<View>(R.id.pickPhotoButton).setOnClickListener {
            if (!PhotoMetadata.hasMediaLocationPermission(this) && !prefs().getBoolean(KEY_ASKED_MEDIA_LOCATION, false)) {
                prefs().edit().putBoolean(KEY_ASKED_MEDIA_LOCATION, true).apply()
                mediaLocationPermission.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            } else {
                pickPhoto.launch(arrayOf("image/*"))
            }
        }
        removePhotoButton.setOnClickListener { replacePhoto(null) }
        common.setAdapter(suggestions)
        common.doAfterTextChanged { text -> if (common.hasFocus() && !applyingSuggestion) scheduleSuggestions(text.toString()) }
        common.setOnItemClickListener { _, _, position, _ -> applySuggestion(suggestions.getItem(position)) }
        date.setOnClickListener { pickDate() }
        findViewById<View>(R.id.saveButton).setOnClickListener { save() }

        pendingCapture = savedInstanceState?.getString(STATE_PENDING_CAPTURE)
        if (savedInstanceState != null) setLocation(savedInstanceState.getDoubleArray(STATE_LOCATION))
        val id = intent.getLongExtra(Destinations.EXTRA_SPECIMEN_ID, 0L)
        toolbar.setTitle(if (id == 0L) R.string.add_specimen else R.string.edit_specimen)
        if (id == 0L) {
            if (savedInstanceState == null) {
                date.setText(today())
                collector.setText(prefs().getString(KEY_LAST_COLLECTOR, ""))
                // Espécimen nuevo: se toma el punto de colecta automáticamente.
                requestLocation()
            }
            restorePhoto(savedInstanceState)
        } else {
            lifecycleScope.launch {
                val specimen = SpecimenRepository.get(this@SpecimenEditActivity, id) ?: return@launch finish()
                original = specimen
                toolbar.subtitle = specimen.catalogCode
                if (savedInstanceState == null) {
                    fill(specimen)
                    setLocation(
                        if (specimen.latitude != null && specimen.longitude != null) {
                            doubleArrayOf(
                                specimen.latitude, specimen.longitude,
                                specimen.altitude ?: Double.NaN, specimen.accuracy?.toDouble() ?: Double.NaN,
                            )
                        } else {
                            null
                        }
                    )
                }
                restorePhoto(savedInstanceState)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PHOTO, photo)
        outState.putString(STATE_PENDING_CAPTURE, pendingCapture)
        outState.putDoubleArray(STATE_LOCATION, location)
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

    private fun requestLocation() {
        if (SpecimenLocation.hasPermission(this)) captureLocation() else locationPermission.launch(SpecimenLocation.PERMISSIONS)
    }

    /** Toma el punto GPS y, si la localidad está vacía, la llena con el nombre del lugar. */
    private fun captureLocation() {
        locationJob?.cancel()
        locationText.setText(R.string.location_searching)
        locationJob = lifecycleScope.launch {
            val fix = SpecimenLocation.current(this@SpecimenEditActivity)
            if (fix == null) {
                if (location == null) locationText.setText(R.string.location_failed)
                else showLocation()
                Toast.makeText(this@SpecimenEditActivity, R.string.location_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            setLocation(
                doubleArrayOf(
                    fix.latitude, fix.longitude,
                    if (fix.hasAltitude()) fix.altitude else Double.NaN,
                    if (fix.hasAccuracy()) fix.accuracy.toDouble() else Double.NaN,
                )
            )
            if (locality.text.isNullOrBlank()) {
                SpecimenLocation.placeName(this@SpecimenEditActivity, fix.latitude, fix.longitude)
                    ?.let { if (locality.text.isNullOrBlank()) locality.setText(it) }
            }
        }
    }

    /** Usa la fecha y el punto guardados en la foto elegida. */
    private fun applyMetadata(info: PhotoMetadata.Info) {
        val applied = mutableListOf<String>()
        info.date?.let {
            date.setText(it)
            applied += getString(R.string.field_date).lowercase()
        }
        if (info.hasLocation) {
            locationJob?.cancel()
            setLocation(doubleArrayOf(info.latitude!!, info.longitude!!, info.altitude ?: Double.NaN, Double.NaN))
            applied += getString(R.string.field_location).lowercase()
            if (locality.text.isNullOrBlank()) {
                lifecycleScope.launch {
                    SpecimenLocation.placeName(this@SpecimenEditActivity, info.latitude!!, info.longitude!!)
                        ?.let { if (locality.text.isNullOrBlank()) locality.setText(it) }
                }
            }
        }
        if (applied.isNotEmpty()) {
            Toast.makeText(this, getString(R.string.metadata_applied, applied.joinToString(" y ")), Toast.LENGTH_LONG).show()
        }
    }

    private fun setLocation(value: DoubleArray?) {
        if (value == null) locationJob?.cancel()
        location = value
        showLocation()
    }

    private fun showLocation() {
        val value = location
        if (value == null) {
            locationText.setText(R.string.location_none)
        } else {
            locationText.text = SpecimenLocation.describe(
                value[0], value[1], value[2].takeUnless { it.isNaN() }, value[3].takeUnless { it.isNaN() }?.toFloat()
            )
        }
        removeLocationButton.visibility = if (value == null) View.GONE else View.VISIBLE
    }

    /** Busca sugerencias medio segundo después de que el usuario deja de escribir. */
    private fun scheduleSuggestions(text: String) {
        suggestJob?.cancel()
        if (text.trim().length < 3) {
            suggestions.submit(emptyList())
            commonLayout.helperText = getString(R.string.suggestions_helper)
            return
        }
        suggestJob = lifecycleScope.launch {
            delay(400)
            commonLayout.helperText = getString(R.string.searching_suggestions)
            try {
                val point = location
                val result = INaturalist.suggest(text.trim(), locality.text.toString(), point?.get(0), point?.get(1))
                suggestions.submit(result.suggestions)
                commonLayout.helperText = when {
                    result.suggestions.isEmpty() -> getString(R.string.no_suggestions)
                    !result.searchedNearby -> getString(R.string.suggestions_global)
                    result.area != null -> getString(R.string.suggestions_near, result.area)
                    else -> getString(R.string.suggestions_near_point, INaturalist.RADIUS_KM)
                }
                if (result.suggestions.isNotEmpty() && common.hasFocus()) common.showDropDown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                commonLayout.helperText = getString(R.string.suggestions_offline)
            }
        }
    }

    /** Rellena nombre común, científico y grupo con la especie elegida. */
    private fun applySuggestion(suggestion: INaturalist.Suggestion) {
        suggestJob?.cancel()
        applyingSuggestion = true
        common.setText(suggestion.commonName.ifBlank { suggestion.scientificName }, false)
        applyingSuggestion = false
        common.dismissDropDown()
        scientific.setText(suggestion.scientificName)
        scientificLayout.error = null
        group.setText(suggestion.iconicGroup)
        commonLayout.helperText = getString(R.string.suggestions_helper)
        lifecycleScope.launch {
            val order = try {
                INaturalist.group(suggestion.taxonId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (order != null) group.setText(order)
        }
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
            catalogCode = original?.catalogCode ?: "",
            scientificName = name,
            commonName = common.text.toString().trim(),
            group = group.text.toString().trim(),
            collectedOn = date.text.toString().ifBlank { today() },
            locality = locality.text.toString().trim(),
            collector = collector.text.toString().trim(),
            notes = notes.text.toString().trim(),
            photo = photo,
            latitude = location?.get(0),
            longitude = location?.get(1),
            altitude = location?.get(2)?.takeUnless { it.isNaN() },
            accuracy = location?.get(3)?.takeUnless { it.isNaN() }?.toFloat(),
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
