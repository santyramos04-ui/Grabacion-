package com.santy.grabacion.main

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Specimen(
    val id: String,
    val scientificName: String,
    val commonName: String,
    val group: String,
    val collectedOn: String,
    val locality: String,
    val collector: String,
    val notes: String,
)

/**
 * Inventario en memoria con datos de ejemplo. Lo añadido se pierde al cerrar la app;
 * la base de datos llegará en una fase posterior.
 */
object SpecimenRepository {
    private val items = mutableListOf(
        Specimen(
            "MHN-0001", "Morpho peleides", "Mariposa morfo azul", "Lepidoptera",
            "2026-03-14", "Reserva Río Claro, Antioquia", "S. Ramos",
            "Macho adulto, alas completas. Capturado con red entomológica al borde del río."
        ),
        Specimen(
            "MHN-0002", "Dynastes hercules", "Escarabajo hércules", "Coleoptera",
            "2026-04-02", "Bosque de niebla, Caldas", "S. Ramos",
            "Macho con cuerno torácico de 4 cm. Montado en alfiler n.º 5."
        ),
        Specimen(
            "MHN-0003", "Atta cephalotes", "Hormiga arriera", "Hymenoptera",
            "2026-04-19", "Jardín Botánico, Medellín", "S. Ramos",
            "Serie de 12 obreras en alcohol al 70 %."
        ),
        Specimen(
            "MHN-0004", "Heliconia rostrata", "Platanillo", "Plantae",
            "2026-05-07", "Vereda La Esperanza, Antioquia", "S. Ramos",
            "Inflorescencia prensada; bráctea roja con margen amarillo."
        ),
        Specimen(
            "MHN-0005", "Oophaga histrionica", "Rana arlequín", "Amphibia",
            "2026-06-11", "Chocó biogeográfico", "S. Ramos",
            "Solo registro fotográfico; especie protegida, no colectada."
        ),
        Specimen(
            "MHN-0006", "Megaphobema robustum", "Tarántula colombiana", "Arachnida",
            "2026-07-23", "Piedemonte llanero, Meta", "S. Ramos",
            "Muda completa de hembra subadulta."
        ),
        Specimen(
            "MHN-0007", "Passiflora edulis", "Maracuyá", "Plantae",
            "2026-08-30", "Finca El Recreo, Valle del Cauca", "S. Ramos",
            "Rama con flor y fruto inmaduro para herbario."
        ),
    )

    fun all(): List<Specimen> = items.sortedByDescending { it.collectedOn }

    fun get(id: String): Specimen? = items.firstOrNull { it.id == id }

    fun add(scientificName: String, commonName: String, group: String, locality: String, notes: String): Specimen {
        val specimen = Specimen(
            id = "TMP-" + UUID.randomUUID().toString().take(8).uppercase(Locale.US),
            scientificName = scientificName,
            commonName = commonName,
            group = group,
            collectedOn = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
            locality = locality,
            collector = "",
            notes = notes,
        )
        items += specimen
        return specimen
    }
}
