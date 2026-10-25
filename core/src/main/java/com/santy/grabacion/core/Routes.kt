package com.santy.grabacion.core

import android.content.Context
import android.content.Intent

/**
 * Puerta de comunicación entre módulos: el vault y la colección no se conocen entre sí. La app
 * (módulo :app) instala aquí cómo abrir cada uno al arrancar (ver OwlApplication).
 */
object Routes {
    interface Provider {
        /** Pantalla principal de la colección biológica. */
        fun collection(context: Context): Intent

        /** Pantalla principal del vault. */
        fun vault(context: Context): Intent
    }

    private var provider: Provider? = null

    fun install(provider: Provider) {
        this.provider = provider
    }

    fun collection(context: Context): Intent = requireProvider().collection(context)

    fun vault(context: Context): Intent = requireProvider().vault(context)

    private fun requireProvider() = provider ?: error("Routes.install() no se llamó en OwlApplication")
}
