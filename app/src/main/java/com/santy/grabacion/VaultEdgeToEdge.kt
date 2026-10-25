package com.santy.grabacion

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Borde a borde con iconos claros: el vault es siempre oscuro, así que no se deja que el sistema
 * elija el estilo de las barras según el modo claro/oscuro del teléfono.
 */
fun ComponentActivity.enableVaultEdgeToEdge() = enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
)
