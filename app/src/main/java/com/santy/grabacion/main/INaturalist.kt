package com.santy.grabacion.main

import com.santy.grabacion.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Sugerencias de especies con la API pública de iNaturalist (sin cuenta). Busca primero las
 * especies observadas cerca del punto GPS (o en el lugar de la localidad si no hay GPS) y, si
 * hay pocas, completa con resultados globales.
 */
object INaturalist {
    private const val API = "https://api.inaturalist.org/v1"
    private const val MAX_SUGGESTIONS = 10
    const val RADIUS_KM = 30

    data class Place(val id: Long, val name: String)

    data class Suggestion(
        val taxonId: Long,
        val scientificName: String,
        val commonName: String,
        val iconicGroup: String,
        /** Observaciones en el lugar buscado, o null si es un resultado global. */
        val nearbyObservations: Int?,
    )

    /** [area] describe dónde se buscó: el nombre del lugar, o null si se usó el punto GPS. */
    data class Result(val searchedNearby: Boolean, val area: String?, val suggestions: List<Suggestion>)

    private val placeCache = mutableMapOf<String, Place?>()

    suspend fun suggest(name: String, locality: String, latitude: Double?, longitude: Double?): Result =
        withContext(Dispatchers.IO) {
            val usePoint = latitude != null && longitude != null
            val place = if (usePoint) null else resolvePlace(locality)
            val area = when {
                usePoint -> "&lat=$latitude&lng=$longitude&radius=$RADIUS_KM"
                place != null -> "&place_id=${place.id}"
                else -> null
            }
            val local = if (area != null) nearby(name, area) else emptyList()
            val global = if (local.size < 3) global(name, place?.id) else emptyList()
            val seen = local.map { it.taxonId }.toSet()
            Result(
                searchedNearby = local.isNotEmpty(),
                area = place?.name,
                suggestions = (local + global.filter { it.taxonId !in seen }).take(MAX_SUGGESTIONS),
            )
        }

    /** Grupo taxonómico para la colección: el orden si existe, si no la clase o el reino. */
    suspend fun group(taxonId: Long): String? = withContext(Dispatchers.IO) {
        val taxon = get("/taxa/$taxonId?locale=es").getJSONArray("results").optJSONObject(0)
            ?: return@withContext null
        val ancestors = taxon.optJSONArray("ancestors") ?: return@withContext null
        val byRank = (0 until ancestors.length())
            .map { ancestors.getJSONObject(it) }
            .associate { it.optString("rank") to it.optString("name") }
        listOf("order", "class", "phylum", "kingdom").firstNotNullOfOrNull { byRank[it]?.takeIf(String::isNotBlank) }
    }

    /**
     * Lugar de iNaturalist para el texto de la localidad. Prueba el texto completo y luego la
     * última parte ("Reserva Río Claro, Antioquia" → "Antioquia"), que suele ser la región.
     */
    private fun resolvePlace(locality: String): Place? {
        val text = locality.trim()
        if (text.length < 3) return null
        synchronized(placeCache) { if (text in placeCache) return placeCache[text] }
        val candidates = listOf(text, text.substringAfterLast(',').trim()).distinct().filter { it.length >= 3 }
        val place = candidates.firstNotNullOfOrNull { query ->
            get("/places/autocomplete?q=${encode(query)}").getJSONArray("results").optJSONObject(0)?.let {
                Place(it.getLong("id"), it.optString("display_name", it.optString("name")))
            }
        }
        synchronized(placeCache) { placeCache[text] = place }
        return place
    }

    /** [area] son los parámetros de zona: "&place_id=…" o "&lat=…&lng=…&radius=…". */
    private fun nearby(name: String, area: String): List<Suggestion> {
        val json = get(
            "/observations/species_counts?q=${encode(name)}&search_on=names$area&locale=es&per_page=$MAX_SUGGESTIONS"
        )
        val results = json.getJSONArray("results")
        return (0 until results.length()).mapNotNull { i ->
            val item = results.getJSONObject(i)
            item.optJSONObject("taxon")?.toSuggestion(item.optInt("count"))
        }
    }

    private fun global(name: String, placeId: Long?): List<Suggestion> {
        val preferred = placeId?.let { "&preferred_place_id=$it" } ?: ""
        val json = get("/taxa/autocomplete?q=${encode(name)}&locale=es&is_active=true&per_page=$MAX_SUGGESTIONS$preferred")
        val results = json.getJSONArray("results")
        return (0 until results.length()).map { results.getJSONObject(it).toSuggestion(null) }
    }

    private fun JSONObject.toSuggestion(count: Int?) = Suggestion(
        taxonId = getLong("id"),
        scientificName = optString("name"),
        commonName = optString("preferred_common_name").takeUnless { it == "null" } ?: "",
        iconicGroup = optString("iconic_taxon_name").takeUnless { it == "null" } ?: "",
        nearbyObservations = count,
    )

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    private fun get(path: String): JSONObject {
        val connection = URL(API + path).openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "owl/${BuildConfig.VERSION_NAME} (Android)")
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("iNaturalist respondió ${connection.responseCode}")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    fun format(count: Int): String = String.format(Locale.getDefault(), "%,d", count)
}
