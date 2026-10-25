package com.santy.grabacion

import android.app.Application
import android.content.Context
import com.santy.grabacion.collection.CollectionActivity
import com.santy.grabacion.core.AppInfo
import com.santy.grabacion.core.Routes
import com.santy.grabacion.vault.VaultActivity

/**
 * Une los módulos en una sola app: es el único lugar que conoce a la vez la colección y el
 * vault, y les dice a los demás cómo abrir cada uno (core.Routes).
 */
class OwlApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppInfo.init(this)
        Routes.install(object : Routes.Provider {
            override fun collection(context: Context) = CollectionActivity.intent(context)
            override fun vault(context: Context) = VaultActivity.intent(context)
        })
    }
}
