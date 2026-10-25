package com.santy.grabacion.collection

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.santy.grabacion.core.AppInfo
import org.osmdroid.config.Configuration
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Ubicación del punto de colecta con el GPS del teléfono, sin servicios de Google. */
object SpecimenLocation {
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private const val GPS_TIMEOUT_MS = 25_000L
    private const val NETWORK_TIMEOUT_MS = 10_000L
    private const val MAX_LAST_KNOWN_AGE_MS = 5 * 60_000L

    fun hasPermission(context: Context) = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Posición actual: intenta el GPS, luego la red y por último la última posición conocida
     * si es reciente. Devuelve null si no hay permiso o no se pudo ubicar.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = manager.getProviders(true)
        if (LocationManager.GPS_PROVIDER in providers) {
            withTimeoutOrNull(GPS_TIMEOUT_MS) { single(manager, LocationManager.GPS_PROVIDER) }?.let { return it }
        }
        if (LocationManager.NETWORK_PROVIDER in providers) {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) { single(manager, LocationManager.NETWORK_PROVIDER) }?.let { return it }
        }
        val now = System.currentTimeMillis()
        return providers.mapNotNull { manager.getLastKnownLocation(it) }
            .filter { now - it.time < MAX_LAST_KNOWN_AGE_MS }
            .minByOrNull { it.accuracy }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun single(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, Executor { it.run() }) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } else {
                val listener = LocationListener { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }

    /** Nombre legible del lugar ("Guatapé, Antioquia") a partir de las coordenadas, si hay geocodificador. */
    @Suppress("DEPRECATION")
    suspend fun placeName(context: Context, latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            val address = Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)?.firstOrNull()
                ?: return@withContext null
            listOfNotNull(address.subLocality ?: address.locality ?: address.subAdminArea, address.adminArea)
                .distinct()
                .joinToString(", ")
                .ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    fun describe(specimen: Specimen): String? {
        val lat = specimen.latitude ?: return null
        val lng = specimen.longitude ?: return null
        return describe(lat, lng, specimen.altitude, specimen.accuracy)
    }

    fun describe(latitude: Double, longitude: Double, altitude: Double?, accuracy: Float?): String =
        listOfNotNull(
            String.format(Locale.US, "%.5f, %.5f", latitude, longitude),
            altitude?.let { String.format(Locale.getDefault(), "%,.0f m s. n. m.", it) },
            accuracy?.let { String.format(Locale.getDefault(), "±%.0f m", it) },
        ).joinToString("\n")

    /** Abre la ubicación en la app de mapas del teléfono. */
    fun openInMaps(context: Context, specimen: Specimen) {
        val lat = specimen.latitude ?: return
        val lng = specimen.longitude ?: return
        val label = Uri.encode("${specimen.catalogCode} ${specimen.scientificName}")
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)")
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), null))
    }
}

/** Configuración de osmdroid: caché privada y User-Agent propio, como pide OpenStreetMap. */
object MapSetup {
    private var done = false

    fun init(context: Context) {
        if (done) return
        val config = Configuration.getInstance()
        val app = context.applicationContext
        config.load(app, app.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        config.userAgentValue = AppInfo.userAgent(app)
        val base = File(app.cacheDir, "osmdroid")
        config.osmdroidBasePath = base
        config.osmdroidTileCache = File(base, "tiles")
        done = true
    }
}
