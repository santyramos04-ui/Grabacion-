package com.santy.grabacion.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Actualizaciones desde la propia app: cada compilación de GitHub Actions publica una versión
 * "build-N" con el APK, y la app la instala si N es mayor que su versionCode.
 */
object UpdateManager {
    private const val PREFS = "actualizaciones"
    private const val KEY_AUTO = "auto"
    private const val KEY_LAST_CHECK = "ultima_revision"
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    data class Release(val versionCode: Int, val name: String, val notes: String, val apkUrl: String)

    fun isAutoCheckEnabled(context: Context) = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()

    /** Revisión silenciosa al abrir la app: solo avisa si hay una versión nueva. */
    fun autoCheck(activity: AppCompatActivity) {
        if (!isAutoCheckEnabled(activity)) return
        val now = System.currentTimeMillis()
        if (now - prefs(activity).getLong(KEY_LAST_CHECK, 0) < AUTO_CHECK_INTERVAL_MS) return
        prefs(activity).edit().putLong(KEY_LAST_CHECK, now).apply()
        check(activity, silent = true)
    }

    fun check(activity: AppCompatActivity, silent: Boolean) {
        if (!silent) toast(activity, activity.getString(R.string.checking_updates))
        activity.lifecycleScope.launch {
            val release = try {
                latestRelease()
            } catch (e: Exception) {
                if (!silent) toast(activity, activity.getString(R.string.update_failed, e.message))
                return@launch
            }
            if (release == null || release.versionCode <= AppInfo.versionCode) {
                if (!silent) toast(activity, activity.getString(R.string.up_to_date))
                return@launch
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(
                    activity.getString(R.string.update_available, release.name, AppInfo.versionName, release.notes)
                )
                .setPositiveButton(R.string.update_now) { _, _ -> downloadAndInstall(activity, release) }
                .setNegativeButton(R.string.later, null)
                .show()
        }
    }

    private fun downloadAndInstall(activity: AppCompatActivity, release: Release) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            toast(activity, activity.getString(R.string.allow_installs))
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        activity.lifecycleScope.launch {
            val progress = ProgressDialog(activity, activity.getString(R.string.downloading_update, 0), null)
            try {
                val apk = download(activity, release.apkUrl) { percent ->
                    progress.setTitle(activity.getString(R.string.downloading_update, percent))
                    progress.setProgress(percent)
                }
                progress.dismiss()
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                progress.dismiss()
                toast(activity, activity.getString(R.string.update_failed, e.message))
            }
        }
    }

    private suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
        val connection = URL("https://api.github.com/repos/${AppInfo.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return@withContext null
                else -> throw IOException("HTTP ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val versionCode = tag.removePrefix("build-").toIntOrNull() ?: return@withContext null
            val assets = json.getJSONArray("assets")
            val apkUrl = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?.getString("browser_download_url")
                ?: return@withContext null
            Release(versionCode, json.optString("name", tag), json.optString("body", ""), apkUrl)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun download(context: Context, url: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "owl-update.apk")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${connection.responseCode}")
                }
                val total = connection.contentLengthLong
                var copied = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                val percent = (copied * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            file
        }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
