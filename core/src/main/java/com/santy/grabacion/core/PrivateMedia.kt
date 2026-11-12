package com.santy.grabacion.core

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Espacio privado de la app para los videos y fotos grabados: una carpeta propia de OWL que la
 * galería del teléfono no muestra y que otras apps no pueden ver. Se comparte o reproduce a
 * través de FileProvider. Al desinstalar la app, este espacio se borra con ella.
 *
 * Tanto la grabadora (que escribe aquí) como el vault (que lo lee) usan estos mismos caminos.
 */
object PrivateMedia {
    private const val ROOT = "OwlMedia"
    /** Nombre de la raíz en res/xml/file_paths.xml (external-files-path). */
    private const val PROVIDER_ROOT = "owl_media"

    fun authority(context: Context) = "${context.packageName}.files"

    private fun base(context: Context) =
        File(context.getExternalFilesDir(null) ?: context.filesDir, ROOT)

    fun videosDir(context: Context) = File(base(context), "Videos").apply { mkdirs() }

    fun timelapseDir(context: Context) = File(base(context), "Timelapse").apply { mkdirs() }

    /** URI para reproducir, compartir o mostrar un archivo del espacio privado. */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    fun isPrivate(context: Context, uri: Uri): Boolean =
        uri.scheme == "content" && uri.authority == authority(context)

    /** Devuelve el archivo que representa un [uri] del espacio privado, o null si no lo es. */
    fun fileFor(context: Context, uri: Uri): File? {
        if (!isPrivate(context, uri)) return null
        val segments = uri.pathSegments
        if (segments.isEmpty() || segments[0] != PROVIDER_ROOT) return null
        return File(base(context), segments.drop(1).joinToString(File.separator))
    }
}
