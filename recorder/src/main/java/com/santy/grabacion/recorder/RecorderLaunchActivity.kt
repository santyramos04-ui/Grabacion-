package com.santy.grabacion.recorder

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Pantalla invisible que inicia (o detiene) la grabación desde fuera de la app: la usa el botón
 * de Ajustes rápidos. Android exige que la app esté visible un instante para encender la cámara;
 * esta actividad cumple ese requisito y se cierra enseguida, sin mostrar nada. Si falta el
 * permiso de cámara lo pide una sola vez.
 */
class RecorderLaunchActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (RecordingController.canRecord(this)) start()
        else Toast.makeText(this, R.string.recorder_need_camera, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toggle = intent.getBooleanExtra(EXTRA_TOGGLE, true)
        if (RecordingController.isActive) {
            if (toggle) RecordingController.stop(this)
            finish()
            return
        }
        if (RecordingController.canRecord(this)) {
            start()
            finish()
        } else {
            val settings = RecordingSettingsStore.load(this)
            permissions.launch(RecordingController.missingPermissions(this, settings.audio).toTypedArray())
        }
    }

    private fun start() {
        RecordingController.startVideo(this, CaptureOrigin.COLLECTION)
    }

    companion object {
        const val EXTRA_TOGGLE = "toggle"

        fun intent(context: Context) = Intent(context, RecorderLaunchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
