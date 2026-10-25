package com.santy.grabacion.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.santy.grabacion.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exportación de la colección. El CSV usa los nombres de columnas de Darwin Core, el estándar
 * de GBIF, para que se pueda abrir en Excel o subir a plataformas de biodiversidad.
 */
object CollectionExport {

    enum class Format(val extension: String, val mimeType: String, val label: Int) {
        CSV("csv", "text/csv", R.string.export_csv),
        GEOJSON("geojson", "application/geo+json", R.string.export_geojson),
        KML("kml", "application/vnd.google-earth.kml+xml", R.string.export_kml),
    }

    fun fileName(format: Format) =
        "coleccion_owl_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.${format.extension}"

    fun content(format: Format, specimens: List<Specimen>): String = when (format) {
        Format.CSV -> csv(specimens)
        Format.GEOJSON -> geoJson(specimens)
        Format.KML -> kml(specimens)
    }

    private fun csv(specimens: List<Specimen>): String {
        val header = listOf(
            "catalogNumber", "scientificName", "vernacularName", "higherClassification", "eventDate",
            "locality", "recordedBy", "decimalLatitude", "decimalLongitude", "geodeticDatum",
            "coordinateUncertaintyInMeters", "minimumElevationInMeters", "occurrenceRemarks", "basisOfRecord",
        )
        val rows = specimens.map { s ->
            listOf(
                s.catalogCode, s.scientificName, s.commonName, s.group, s.collectedOn, s.locality, s.collector,
                s.latitude?.let { coord(it) } ?: "", s.longitude?.let { coord(it) } ?: "",
                if (s.hasLocation) "WGS84" else "",
                s.accuracy?.let { Math.round(it).toString() } ?: "",
                s.altitude?.let { Math.round(it).toString() } ?: "",
                s.notes, "PreservedSpecimen",
            )
        }
        // BOM para que Excel reconozca las tildes en UTF-8.
        return "﻿" + (listOf(header) + rows).joinToString("\r\n") { row -> row.joinToString(",") { csvField(it) } }
    }

    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\""
        else value

    private fun coord(value: Double) = String.format(Locale.US, "%.6f", value)

    private fun geoJson(specimens: List<Specimen>): String {
        val features = JSONArray()
        specimens.filter { it.hasLocation }.forEach { s ->
            val coordinates = JSONArray().put(s.longitude!!).put(s.latitude!!)
            s.altitude?.let { coordinates.put(it) }
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", coordinates))
                    .put(
                        "properties", JSONObject()
                            .put("catalogNumber", s.catalogCode)
                            .put("scientificName", s.scientificName)
                            .put("vernacularName", s.commonName)
                            .put("group", s.group)
                            .put("eventDate", s.collectedOn)
                            .put("locality", s.locality)
                            .put("recordedBy", s.collector)
                            .put("coordinateUncertaintyInMeters", s.accuracy?.toDouble() ?: JSONObject.NULL)
                            .put("notes", s.notes)
                    )
            )
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString(2)
    }

    private fun kml(specimens: List<Specimen>): String {
        val groups = specimens.map { it.group.ifBlank { "Sin grupo" } }.distinct()
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document>""").append('\n')
            append("<name>Colección OWL</name>\n")
            groups.forEachIndexed { i, group ->
                val argb = GroupColors.colorFor(if (group == "Sin grupo") "" else group)
                // KML usa aabbggrr.
                val kmlColor = String.format(Locale.US, "ff%02x%02x%02x", argb and 0xFF, (argb shr 8) and 0xFF, (argb shr 16) and 0xFF)
                append("<Style id=\"g$i\"><IconStyle><color>$kmlColor</color>")
                append("<Icon><href>http://maps.google.com/mapfiles/kml/paddle/wht-blank.png</href></Icon></IconStyle></Style>\n")
            }
            groups.forEachIndexed { i, group ->
                append("<Folder><name>${xml(group)}</name>\n")
                specimens.filter { it.hasLocation && it.group.ifBlank { "Sin grupo" } == group }.forEach { s ->
                    append("<Placemark><name>${xml(s.catalogCode)} ${xml(s.scientificName)}</name>")
                    append("<styleUrl>#g$i</styleUrl><description>${xml(
                        listOf(s.commonName, s.collectedOn, s.locality, s.collector, s.notes).filter { it.isNotBlank() }.joinToString(" · ")
                    )}</description>")
                    append("<Point><coordinates>${coord(s.longitude!!)},${coord(s.latitude!!)},${s.altitude?.let { Math.round(it) } ?: 0}</coordinates></Point></Placemark>\n")
                }
                append("</Folder>\n")
            }
            append("</Document></kml>\n")
        }
    }

    private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Escribe el archivo en la caché y abre el menú de compartir (Drive, correo, WhatsApp…). */
    suspend fun share(context: Context, format: Format, specimens: List<Specimen>) {
        val file = withContext(Dispatchers.IO) {
            exportFile(context, fileName(format)).apply { writeText(content(format, specimens)) }
        }
        shareFile(context, file, format.mimeType)
    }

    /** Guarda en el destino que el usuario eligió con el selector de archivos. */
    suspend fun saveTo(context: Context, uri: Uri, format: Format, specimens: List<Specimen>) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(content(format, specimens)) }
    }

    suspend fun shareImage(context: Context, bitmap: Bitmap) {
        val file = withContext(Dispatchers.IO) {
            exportFile(context, "mapa_owl_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.png").apply {
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        shareFile(context, file, "image/png")
    }

    private fun exportFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, name)
    }

    private fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_with)))
    }
}
