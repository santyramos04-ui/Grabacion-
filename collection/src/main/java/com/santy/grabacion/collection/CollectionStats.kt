package com.santy.grabacion.collection

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Estadísticas de un conjunto de especímenes. Las especies se cuentan por nombre científico
 * (sin distinguir mayúsculas); cada espécimen cuenta como un individuo para los índices.
 */
class CollectionStats(val specimens: List<Specimen>) {

    data class Count(val label: String, val value: Int)

    data class Geo(
        val points: Int,
        val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double,
        val centroidLat: Double, val centroidLng: Double,
        /** Mayor distancia entre dos puntos, en km. */
        val maxDistanceKm: Double,
        /** Extensión de presencia (EOO): área del polígono convexo mínimo, en km². */
        val eooKm2: Double,
        /** Vértices del polígono convexo (lat, lng), en orden. */
        val hull: List<Pair<Double, Double>>,
    )

    val total = specimens.size

    private val speciesCounts: Map<String, Int> = specimens
        .groupingBy { it.scientificName.trim().lowercase(Locale.ROOT) }
        .eachCount()

    /** Nombre científico tal como se escribió la primera vez, por clave normalizada. */
    private val speciesNames: Map<String, String> = specimens
        .associateBy({ it.scientificName.trim().lowercase(Locale.ROOT) }, { it.scientificName.trim() })

    val species = speciesCounts.size
    val groups = specimens.map { it.group.ifBlank { "Sin grupo" } }.distinct().size
    val localities = specimens.map { it.locality.trim() }.filter { it.isNotEmpty() }.distinct().size
    val collectors = specimens.map { it.collector.trim() }.filter { it.isNotEmpty() }.distinct().size
    val withLocation = specimens.count { it.hasLocation }
    val withPhoto = specimens.count { it.photo != null }

    fun percent(part: Int) = if (total == 0) 0 else Math.round(part * 100f / total)

    fun byGroup(): List<Count> = specimens.groupingBy { it.group.ifBlank { "Sin grupo" } }.eachCount()
        .map { Count(it.key, it.value) }
        .sortedByDescending { it.value }

    fun topSpecies(limit: Int = 10): List<Count> = speciesCounts
        .map { Count(speciesNames.getValue(it.key), it.value) }
        .sortedByDescending { it.value }
        .take(limit)

    fun topLocalities(limit: Int = 5): List<Count> = specimens.map { it.locality.trim() }
        .filter { it.isNotEmpty() }
        .groupingBy { it }.eachCount()
        .map { Count(it.key, it.value) }
        .sortedByDescending { it.value }
        .take(limit)

    /** Registros por mes (yyyy-MM), del primero al último mes con datos, incluidos los meses vacíos. */
    fun byMonth(): List<Count> {
        val months = specimens.mapNotNull { it.collectedOn.takeIf { d -> d.length >= 7 }?.substring(0, 7) }
        if (months.isEmpty()) return emptyList()
        val counts = months.groupingBy { it }.eachCount()
        val (firstYear, firstMonth) = months.min().split('-').map { it.toInt() }
        val (lastYear, lastMonth) = months.max().split('-').map { it.toInt() }
        val result = mutableListOf<Count>()
        var year = firstYear
        var month = firstMonth
        while (year < lastYear || (year == lastYear && month <= lastMonth)) {
            val key = String.format(Locale.US, "%04d-%02d", year, month)
            result += Count(key, counts[key] ?: 0)
            month++
            if (month > 12) { month = 1; year++ }
        }
        return result
    }

    /** Especies acumuladas tras cada registro, en orden de fecha de colecta. */
    fun accumulationCurve(): List<Int> {
        val seen = mutableSetOf<String>()
        return specimens.sortedWith(compareBy({ it.collectedOn }, { it.id }))
            .map { seen += it.scientificName.trim().lowercase(Locale.ROOT); seen.size }
    }

    /** Especies con un solo registro (F1) y con dos (F2). */
    val singletons = speciesCounts.values.count { it == 1 }
    val doubletons = speciesCounts.values.count { it == 2 }

    /** Riqueza estimada Chao1 (corregida por sesgo). */
    val chao1: Double
        get() = if (species == 0) 0.0
        else species + singletons * (singletons - 1) / (2.0 * (doubletons + 1))

    /** Índice de Shannon H' (logaritmo natural). */
    val shannon: Double
        get() = if (total == 0) 0.0 else -speciesCounts.values.sumOf { n ->
            val p = n.toDouble() / total
            p * ln(p)
        }

    /** Índice de diversidad de Simpson 1 − D (probabilidad de que dos registros sean de especies distintas). */
    val simpson: Double
        get() = if (total < 2) 0.0 else 1.0 - speciesCounts.values.sumOf { n -> n * (n - 1.0) } / (total * (total - 1.0))

    /** Equidad de Pielou J' = H' / ln(S). */
    val pielou: Double?
        get() = if (species < 2) null else shannon / ln(species.toDouble())

    /** Completitud del inventario: especies observadas / Chao1. */
    val completeness: Double?
        get() = if (chao1 <= 0) null else species / chao1

    data class Altitude(val min: Double, val max: Double, val median: Double, val count: Int)

    fun altitude(): Altitude? {
        val values = specimens.mapNotNull { it.altitude }.sorted()
        if (values.isEmpty()) return null
        val mid = values.size / 2
        val median = if (values.size % 2 == 1) values[mid] else (values[mid - 1] + values[mid]) / 2
        return Altitude(values.first(), values.last(), median, values.size)
    }

    /** Registros por franjas de 500 m de altitud. */
    fun altitudeBands(bandMeters: Int = 500): List<Count> {
        val values = specimens.mapNotNull { it.altitude }
        if (values.isEmpty()) return emptyList()
        val first = (values.min() / bandMeters).toInt()
        val last = (values.max() / bandMeters).toInt()
        val counts = values.groupingBy { (it / bandMeters).toInt() }.eachCount()
        return (first..last).map { band ->
            Count("${band * bandMeters}–${(band + 1) * bandMeters} m", counts[band] ?: 0)
        }
    }

    fun geo(): Geo? {
        val points = specimens.filter { it.hasLocation }.map { it.latitude!! to it.longitude!! }
        if (points.isEmpty()) return null
        val hull = convexHull(points.distinct())
        var maxDistance = 0.0
        // Los puntos más lejanos entre sí siempre son vértices del polígono convexo.
        for (i in hull.indices) for (j in i + 1 until hull.size) {
            maxDistance = maxOf(maxDistance, haversineKm(hull[i], hull[j]))
        }
        return Geo(
            points = points.size,
            minLat = points.minOf { it.first }, maxLat = points.maxOf { it.first },
            minLng = points.minOf { it.second }, maxLng = points.maxOf { it.second },
            centroidLat = points.sumOf { it.first } / points.size,
            centroidLng = points.sumOf { it.second } / points.size,
            maxDistanceKm = maxDistance,
            eooKm2 = if (hull.size >= 3) areaKm2(hull) else 0.0,
            hull = hull,
        )
    }

    companion object {
        private const val EARTH_RADIUS_KM = 6371.0088

        fun haversineKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val lat1 = Math.toRadians(a.first)
            val lat2 = Math.toRadians(b.first)
            val dLat = lat2 - lat1
            val dLng = Math.toRadians(b.second - a.second)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
            return 2 * EARTH_RADIUS_KM * asin(sqrt(h))
        }

        /** Polígono convexo mínimo (cadena monótona de Andrew) sobre (lat, lng). */
        fun convexHull(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
            if (points.size < 3) return points
            val sorted = points.sortedWith(compareBy({ it.second }, { it.first }))
            fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>) =
                (a.second - o.second) * (b.first - o.first) - (a.first - o.first) * (b.second - o.second)
            val lower = mutableListOf<Pair<Double, Double>>()
            for (p in sorted) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.lastIndex)
                lower += p
            }
            val upper = mutableListOf<Pair<Double, Double>>()
            for (p in sorted.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.lastIndex)
                upper += p
            }
            return lower.dropLast(1) + upper.dropLast(1)
        }

        /** Área de un polígono pequeño proyectado localmente (equirectangular), en km². */
        fun areaKm2(polygon: List<Pair<Double, Double>>): Double {
            val lat0 = Math.toRadians(polygon.sumOf { it.first } / polygon.size)
            val xy = polygon.map { (lat, lng) ->
                Math.toRadians(lng) * cos(lat0) * EARTH_RADIUS_KM to Math.toRadians(lat) * EARTH_RADIUS_KM
            }
            var sum = 0.0
            for (i in xy.indices) {
                val (x1, y1) = xy[i]
                val (x2, y2) = xy[(i + 1) % xy.size]
                sum += x1 * y2 - x2 * y1
            }
            return kotlin.math.abs(sum) / 2
        }
    }
}
