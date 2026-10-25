package com.santy.grabacion.collection

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Specimen(
    val id: Long = 0,
    /** Código de catálogo: abreviatura del grupo + número, p. ej. COL-0003. Lo asigna [SpecimenRepository]. */
    val catalogCode: String = "",
    val scientificName: String,
    val commonName: String = "",
    val group: String = "",
    /** Fecha de colecta en formato yyyy-MM-dd. */
    val collectedOn: String,
    val locality: String = "",
    val collector: String = "",
    val notes: String = "",
    /** Nombre del archivo de la foto dentro de [SpecimenPhotos.dir], o null. */
    val photo: String? = null,
    /** Coordenadas WGS84 del punto de colecta, si se tomaron. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Altitud en metros sobre el nivel del mar, si el GPS la dio. */
    val altitude: Double? = null,
    /** Precisión horizontal en metros. */
    val accuracy: Float? = null,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null
}

/** Código de catálogo: 3 letras del grupo sin tildes (Coleoptera → COL) o IND si no hay grupo. */
object CatalogCode {
    private const val UNDETERMINED = "IND"

    fun prefixFor(group: String): String {
        val letters = Normalizer.normalize(group, Normalizer.Form.NFD)
            .filter { it.isLetter() && it.code < 128 }
            .uppercase(Locale.ROOT)
        return when {
            letters.isEmpty() -> UNDETERMINED
            letters.length < 3 -> letters.padEnd(3, 'X')
            else -> letters.take(3)
        }
    }

    fun prefixOf(code: String) = code.substringBefore('-')

    fun format(prefix: String, number: Int) = String.format(Locale.US, "%s-%04d", prefix, number)

    /** Siguiente código libre para [prefix], según los códigos ya usados en la base de datos. */
    fun next(db: SQLiteDatabase, prefix: String): String {
        val max = db.rawQuery(
            "SELECT catalog_code FROM specimens WHERE catalog_code LIKE ?", arrayOf("$prefix-%")
        ).use { cursor ->
            var highest = 0
            while (cursor.moveToNext()) {
                val number = cursor.getString(0)?.substringAfter('-')?.toIntOrNull() ?: 0
                if (number > highest) highest = number
            }
            highest
        }
        return format(prefix, max + 1)
    }
}

/** Base de datos local de la colección (archivo coleccion.db en el almacenamiento privado). */
private class SpecimenDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "coleccion.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE specimens (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                catalog_code TEXT,
                scientific_name TEXT NOT NULL,
                common_name TEXT NOT NULL DEFAULT '',
                group_name TEXT NOT NULL DEFAULT '',
                collected_on TEXT NOT NULL,
                locality TEXT NOT NULL DEFAULT '',
                collector TEXT NOT NULL DEFAULT '',
                notes TEXT NOT NULL DEFAULT '',
                photo TEXT,
                latitude REAL,
                longitude REAL,
                altitude REAL,
                accuracy REAL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX specimens_catalog_code ON specimens (catalog_code)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Versión 1 numeraba con OWL-<id>; se asigna el código por grupo a los registros existentes.
            db.execSQL("ALTER TABLE specimens ADD COLUMN catalog_code TEXT")
            db.execSQL("CREATE UNIQUE INDEX specimens_catalog_code ON specimens (catalog_code)")
            db.rawQuery("SELECT _id, group_name FROM specimens ORDER BY _id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val code = CatalogCode.next(db, CatalogCode.prefixFor(cursor.getString(1)))
                    db.execSQL("UPDATE specimens SET catalog_code = ? WHERE _id = ?", arrayOf(code, cursor.getLong(0)))
                }
            }
        }
        if (oldVersion < 3) {
            for (column in listOf("latitude", "longitude", "altitude", "accuracy")) {
                db.execSQL("ALTER TABLE specimens ADD COLUMN $column REAL")
            }
        }
    }
}

object SpecimenRepository {
    private const val TABLE = "specimens"

    @Volatile
    private var database: SpecimenDatabase? = null

    private fun db(context: Context): SQLiteDatabase =
        (database ?: synchronized(this) {
            database ?: SpecimenDatabase(context).also { database = it }
        }).writableDatabase

    /** Todos los especímenes, del más reciente al más antiguo, filtrados por [query] si no está vacío. */
    suspend fun all(context: Context, query: String = ""): List<Specimen> = withContext(Dispatchers.IO) {
        val filter = query.trim()
        val (selection, args) = if (filter.isEmpty()) {
            null to null
        } else {
            val like = "%$filter%"
            "catalog_code LIKE ? OR scientific_name LIKE ? OR common_name LIKE ? OR group_name LIKE ? OR locality LIKE ?" to
                arrayOf(like, like, like, like, like)
        }
        db(context).query(TABLE, null, selection, args, null, null, "collected_on DESC, _id DESC")
            .use { cursor -> buildList<Specimen> { while (cursor.moveToNext()) add(cursor.toSpecimen()) } }
    }

    suspend fun get(context: Context, id: Long): Specimen? = withContext(Dispatchers.IO) {
        db(context).query(TABLE, null, "_id = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) it.toSpecimen() else null }
    }

    /**
     * Inserta si [specimen] no tiene id; si lo tiene, lo actualiza. Asigna un código nuevo al
     * crear el registro o cuando el cambio de grupo cambia su abreviatura. Devuelve el id.
     */
    suspend fun save(context: Context, specimen: Specimen): Long = withContext(Dispatchers.IO) {
        val db = db(context)
        val prefix = CatalogCode.prefixFor(specimen.group)
        val values = ContentValues().apply {
            put("scientific_name", specimen.scientificName)
            put("common_name", specimen.commonName)
            put("group_name", specimen.group)
            put("collected_on", specimen.collectedOn)
            put("locality", specimen.locality)
            put("collector", specimen.collector)
            put("notes", specimen.notes)
            put("photo", specimen.photo)
            put("latitude", specimen.latitude)
            put("longitude", specimen.longitude)
            put("altitude", specimen.altitude)
            put("accuracy", specimen.accuracy)
        }
        db.beginTransaction()
        try {
            val id = if (specimen.id == 0L) {
                values.put("catalog_code", CatalogCode.next(db, prefix))
                values.put("created_at", System.currentTimeMillis())
                db.insertOrThrow(TABLE, null, values)
            } else {
                if (CatalogCode.prefixOf(specimen.catalogCode) != prefix) {
                    values.put("catalog_code", CatalogCode.next(db, prefix))
                }
                db.update(TABLE, values, "_id = ?", arrayOf(specimen.id.toString()))
                specimen.id
            }
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    suspend fun delete(context: Context, specimen: Specimen) = withContext(Dispatchers.IO) {
        db(context).delete(TABLE, "_id = ?", arrayOf(specimen.id.toString()))
        SpecimenPhotos.delete(context, specimen.photo)
    }

    private fun Cursor.toSpecimen() = Specimen(
        id = getLong(getColumnIndexOrThrow("_id")),
        catalogCode = getString(getColumnIndexOrThrow("catalog_code")) ?: "",
        scientificName = getString(getColumnIndexOrThrow("scientific_name")),
        commonName = getString(getColumnIndexOrThrow("common_name")),
        group = getString(getColumnIndexOrThrow("group_name")),
        collectedOn = getString(getColumnIndexOrThrow("collected_on")),
        locality = getString(getColumnIndexOrThrow("locality")),
        collector = getString(getColumnIndexOrThrow("collector")),
        notes = getString(getColumnIndexOrThrow("notes")),
        photo = getString(getColumnIndexOrThrow("photo")),
        latitude = getDoubleOrNull("latitude"),
        longitude = getDoubleOrNull("longitude"),
        altitude = getDoubleOrNull("altitude"),
        accuracy = getDoubleOrNull("accuracy")?.toFloat(),
    )

    private fun Cursor.getDoubleOrNull(column: String): Double? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getDouble(index)
    }
}
