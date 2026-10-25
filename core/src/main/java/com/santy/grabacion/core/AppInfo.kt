package com.santy.grabacion.core

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

/** Versión de la app instalada, leída del sistema para que cualquier módulo pueda usarla. */
object AppInfo {
    /** Repositorio de GitHub donde se publican las actualizaciones. */
    const val UPDATE_REPO = "santyramos04-ui/Grabacion-"

    var versionName: String = "?"
        private set
    var versionCode: Long = 0
        private set

    fun init(context: Context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        versionName = info.versionName ?: "?"
        versionCode = PackageInfoCompat.getLongVersionCode(info)
    }

    /** User-Agent para los servicios web (iNaturalist, OpenStreetMap). */
    fun userAgent(context: Context) = "owl/$versionName (${context.packageName})"
}
