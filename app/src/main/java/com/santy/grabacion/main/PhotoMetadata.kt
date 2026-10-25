package com.santy.grabacion.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Fecha y coordenadas guardadas en los metadatos EXIF de una foto. */
object PhotoMetadata {

    data class Info(val date: String?, val latitude: Double?, val longitude: Double?, val altitude: Double?) {
        val hasLocation get() = latitude != null && longitude != null
    }

    fun hasMediaLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Android quita las coordenadas de las fotos que entrega a otras apps salvo que se pida el
     * original con el permiso ACCESS_MEDIA_LOCATION. Devuelve las URIs a probar, la original primero.
     */
    fun candidates(context: Context, uri: Uri): List<Uri> {
        if (!hasMediaLocationPermission(context)) return listOf(uri)
        val media = if (uri.authority == MediaStore.AUTHORITY) uri
        else runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
        val original = media?.let { runCatching { MediaStore.setRequireOriginal(it) }.getOrNull() }
        return listOfNotNull(original, uri).distinct()
    }

    suspend fun read(file: File): Info? = withContext(Dispatchers.IO) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val rawDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            // EXIF guarda "yyyy:MM:dd HH:mm:ss".
            val date = rawDate?.takeIf { Regex("""\d{4}:\d{2}:\d{2}.*""").matches(it) }
                ?.substring(0, 10)?.replace(':', '-')
                ?.takeUnless { it.startsWith("0000") }
            val coordinates = FloatArray(2)
            val latLong = if (exif.getLatLong(coordinates) && !(coordinates[0] == 0f && coordinates[1] == 0f)) {
                doubleArrayOf(coordinates[0].toDouble(), coordinates[1].toDouble())
            } else {
                null
            }
            val altitude = if (exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE) != null) {
                exif.getAltitude(Double.NaN).takeUnless { it.isNaN() }
            } else {
                null
            }
            Info(date, latLong?.get(0), latLong?.get(1), altitude).takeIf { it.date != null || it.hasLocation }
        } catch (e: Exception) {
            null
        }
    }
}
