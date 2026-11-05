package com.santy.grabacion.recorder

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Botón de Ajustes rápidos (el panel que se baja desde arriba) para empezar o detener la
 * grabación sin abrir la app. Usa los ajustes guardados en el vault. Muestra su estado: activo
 * mientras graba. Para iniciar, lanza [RecorderLaunchActivity] porque Android exige que la app
 * esté visible un instante; para detener, basta con avisar al servicio.
 */
class RecorderTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (RecordingController.isActive) {
            RecordingController.stop(this)
            updateTile()
        } else {
            val intent = RecorderLaunchActivity.intent(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = RecordingController.isActive
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.contentDescription = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (active) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }
}
