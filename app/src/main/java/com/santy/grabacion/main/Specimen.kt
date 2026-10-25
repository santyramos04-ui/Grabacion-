package com.santy.grabacion.main

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class Specimen(
    val id: Long = 0,
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
) {
    val catalogNumber: String get() = String.format(Locale.US, "OWL-%04d", id)
}

/** Base de datos local de la colección (archivo coleccion.db en el almacenamiento privado). */
private class SpecimenDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "coleccion.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE specimens (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                scientific_name TEXT NOT NULL,
                common_name TEXT NOT NULL DEFAULT '',
                group_name TEXT NOT NULL DEFAULT '',
                collected_on TEXT NOT NULL,
                locality TEXT NOT NULL DEFAULT '',
                collector TEXT NOT NULL DEFAULT '',
                notes TEXT NOT NULL DEFAULT '',
                photo TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
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
            "scientific_name LIKE ? OR common_name LIKE ? OR group_name LIKE ? OR locality LIKE ?" to
                arrayOf(like, like, like, like)
        }
        db(context).query(TABLE, null, selection, args, null, null, "collected_on DESC, _id DESC")
            .use { cursor -> buildList<Specimen> { while (cursor.moveToNext()) add(cursor.toSpecimen()) } }
    }

    suspend fun get(context: Context, id: Long): Specimen? = withContext(Dispatchers.IO) {
        db(context).query(TABLE, null, "_id = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) it.toSpecimen() else null }
    }

    /** Inserta si [specimen] no tiene id; si lo tiene, lo actualiza. Devuelve el id. */
    suspend fun save(context: Context, specimen: Specimen): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("scientific_name", specimen.scientificName)
            put("common_name", specimen.commonName)
            put("group_name", specimen.group)
            put("collected_on", specimen.collectedOn)
            put("locality", specimen.locality)
            put("collector", specimen.collector)
            put("notes", specimen.notes)
            put("photo", specimen.photo)
        }
        if (specimen.id == 0L) {
            values.put("created_at", System.currentTimeMillis())
            db(context).insertOrThrow(TABLE, null, values)
        } else {
            db(context).update(TABLE, values, "_id = ?", arrayOf(specimen.id.toString()))
            specimen.id
        }
    }

    suspend fun delete(context: Context, specimen: Specimen) = withContext(Dispatchers.IO) {
        db(context).delete(TABLE, "_id = ?", arrayOf(specimen.id.toString()))
        SpecimenPhotos.delete(context, specimen.photo)
    }

    private fun Cursor.toSpecimen() = Specimen(
        id = getLong(getColumnIndexOrThrow("_id")),
        scientificName = getString(getColumnIndexOrThrow("scientific_name")),
        commonName = getString(getColumnIndexOrThrow("common_name")),
        group = getString(getColumnIndexOrThrow("group_name")),
        collectedOn = getString(getColumnIndexOrThrow("collected_on")),
        locality = getString(getColumnIndexOrThrow("locality")),
        collector = getString(getColumnIndexOrThrow("collector")),
        notes = getString(getColumnIndexOrThrow("notes")),
        photo = getString(getColumnIndexOrThrow("photo")),
    )
}
