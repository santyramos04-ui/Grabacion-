package com.santy.grabacion.main

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Fotos de especímenes. Viven en el almacenamiento privado de la app (files/specimens),
 * separadas de la galería del teléfono y de los archivos del vault.
 */
object SpecimenPhotos {

    fun dir(context: Context): File = File(context.filesDir, "specimens").apply { mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    fun newFile(context: Context): File = File(dir(context), "${UUID.randomUUID()}.jpg")

    /** URI para que la app de cámara escriba la foto en [file]. */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Copia una imagen elegida de la galería a un archivo propio. */
    suspend fun import(context: Context, source: Uri): File = withContext(Dispatchers.IO) {
        val target = newFile(context)
        context.contentResolver.openInputStream(source)!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        target
    }

    fun delete(context: Context, name: String?) {
        if (name != null) file(context, name).delete()
    }
}
