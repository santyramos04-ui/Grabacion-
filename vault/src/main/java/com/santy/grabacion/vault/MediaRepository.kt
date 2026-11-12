package com.santy.grabacion.vault

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.santy.grabacion.core.PrivateMedia
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoInfo(val uri: Uri, val name: String, val durationMs: Long)

data class PhotoInfo(val uri: Uri, val name: String)

/** Una carpeta TL_... con las fotos de un timelapse. */
data class TimelapseSession(val path: String, val title: String, val cover: Uri, val count: Int)

/**
 * Videos y timelapses del vault. Lo nuevo se guarda en el espacio privado de OWL (PrivateMedia),
 * que la galería del teléfono no muestra. Lo que grabaste con versiones anteriores seguía en la
 * galería (Movies/Owl, Pictures/Owl): también se lista aquí para que no desaparezca.
 */
object MediaRepository {
    const val VIDEO_DIR = "Movies/Owl/"
    const val PHOTO_DIR = "Pictures/Owl/"

    // ---- Videos --------------------------------------------------------------------------------

    suspend fun videos(context: Context): List<VideoInfo> = withContext(Dispatchers.IO) {
        val private = PrivateMedia.videosDir(context).listFiles { f -> f.isFile && f.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { VideoInfo(PrivateMedia.uriFor(context, it), it.name, videoDuration(it)) }
            ?: emptyList()
        private + legacyVideos(context)
    }

    private fun videoDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    /** Videos que quedaron en la galería del teléfono con versiones anteriores de la app. */
    private fun legacyVideos(context: Context): List<VideoInfo> {
        val result = mutableListOf<VideoInfo>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$VIDEO_DIR%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val duration = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) {
                result += VideoInfo(
                    ContentUris.withAppendedId(collection, c.getLong(id)),
                    c.getString(name) ?: "",
                    c.getLong(duration)
                )
            }
        }
        return result
    }

    // ---- Timelapses ----------------------------------------------------------------------------

    suspend fun sessions(context: Context): List<TimelapseSession> = withContext(Dispatchers.IO) {
        val private = PrivateMedia.timelapseDir(context).listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.name }
            ?.mapNotNull { dir ->
                val photos = dir.listFiles { f -> f.isFile && f.extension == "jpg" }?.sortedBy { it.name }
                if (photos.isNullOrEmpty()) null
                else TimelapseSession(dir.absolutePath, sessionTitle(dir.name), PrivateMedia.uriFor(context, photos.first()), photos.size)
            }
            ?: emptyList()
        private + legacySessions(context)
    }

    private fun legacySessions(context: Context): List<TimelapseSession> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val groups = LinkedHashMap<String, MutableList<Uri>>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$PHOTO_DIR%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val path = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext()) {
                groups.getOrPut(c.getString(path) ?: PHOTO_DIR) { mutableListOf() } +=
                    ContentUris.withAppendedId(collection, c.getLong(id))
            }
        }
        return groups.map { (path, uris) -> TimelapseSession(path, sessionTitle(path), uris.first(), uris.size) }
    }

    suspend fun sessionPhotos(context: Context, path: String): List<PhotoInfo> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (dir.isDirectory) {
            return@withContext dir.listFiles { f -> f.isFile && f.extension == "jpg" }
                ?.sortedBy { it.name }
                ?.map { PhotoInfo(PrivateMedia.uriFor(context, it), it.name) }
                ?: emptyList()
        }
        // Timelapse heredado en la galería.
        val result = mutableListOf<PhotoInfo>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(path),
            "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                result += PhotoInfo(ContentUris.withAppendedId(collection, c.getLong(id)), c.getString(name) ?: "")
            }
        }
        result
    }

    /** De "TL_20261024_173000_123" o ".../Owl/TL_.../" a "24/10/2026 17:30". */
    fun sessionTitle(path: String): String {
        val folder = path.trimEnd('/').substringAfterLast('/')
        return try {
            val date = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).parse(folder.removePrefix("TL_"))
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(date!!)
        } catch (e: Exception) {
            folder
        }
    }

    // ---- Guardar y exportar --------------------------------------------------------------------

    /** Mueve un video exportado (edición o timelapse) al espacio privado y devuelve su URI. */
    suspend fun saveVideo(context: Context, file: File, displayName: String): Uri = withContext(Dispatchers.IO) {
        val target = File(PrivateMedia.videosDir(context), displayName)
        if (!file.renameTo(target)) {
            file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            file.delete()
        }
        PrivateMedia.uriFor(context, target)
    }

    /** Copia un video del espacio privado a la galería del teléfono (Movies/Owl), a petición. */
    suspend fun exportToGallery(context: Context, uri: Uri, displayName: String): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, VIDEO_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        resolver.openOutputStream(target)!!.use { out ->
            resolver.openInputStream(uri)!!.use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(target, values, null, null)
        true
    }

    fun share(context: Context, uris: List<Uri>, mimeType: String) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mimeType
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_with)))
    }
}

/**
 * Borra archivos. Los del espacio privado de OWL se borran directamente (con una confirmación en
 * la app); los que quedaron en la galería usan la confirmación del sistema (Android 11+).
 */
class MediaDeleter(private val activity: ComponentActivity, private val onDeleted: () -> Unit) {
    private var pending: List<Uri> = emptyList()

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) done() else deleteDirectly(pending)
    }

    fun delete(uris: List<Uri>, confirmMessage: String) {
        if (uris.isEmpty()) return
        pending = uris
        if (uris.all { PrivateMedia.isPrivate(activity, it) }) {
            MaterialAlertDialogBuilder(activity)
                .setMessage(confirmMessage)
                .setPositiveButton(R.string.action_delete) { _, _ -> deletePrivate(uris) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = MediaStore.createDeleteRequest(activity.contentResolver, uris)
            launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            MaterialAlertDialogBuilder(activity)
                .setMessage(confirmMessage)
                .setPositiveButton(R.string.action_delete) { _, _ -> deleteDirectly(uris) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun deletePrivate(uris: List<Uri>) {
        val dirs = mutableSetOf<File>()
        uris.forEach { uri ->
            PrivateMedia.fileFor(activity, uri)?.let { file ->
                file.parentFile?.let { dirs += it }
                file.delete()
            }
        }
        // Borra la carpeta del timelapse si quedó vacía.
        dirs.forEach { if (it.listFiles()?.isEmpty() != false) it.delete() }
        done()
    }

    private fun deleteDirectly(uris: List<Uri>) {
        try {
            uris.forEach { activity.contentResolver.delete(it, null, null) }
            done()
        } catch (e: RecoverableSecurityException) {
            launcher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
        } catch (e: SecurityException) {
            Toast.makeText(activity, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun done() {
        Toast.makeText(activity, R.string.deleted, Toast.LENGTH_SHORT).show()
        onDeleted()
    }
}
