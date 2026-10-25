package com.santy.grabacion.collection

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.santy.grabacion.recorder.CaptureOrigin
import com.santy.grabacion.recorder.RecordingController
import com.santy.grabacion.recorder.RecordingSettingsStore

/**
 * Inicia la grabación de video en segundo plano desde la colección (gesto de 5 toques), con los
 * ajustes que se configuraron en el vault. No abre el vault ni cambia de pantalla: solo una
 * vibración breve confirma que empezó. Debe crearse como propiedad del fragmento.
 */
class CollectionRecordingTrigger(private val fragment: Fragment) {

    private val permissions = fragment.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (RecordingController.canRecord(fragment.requireContext())) start()
    }

    fun onSequence() {
        if (RecordingController.isActive) return
        val context = fragment.requireContext()
        if (!RecordingController.canRecord(context)) {
            // Sin permiso de cámara Android exige preguntar; es lo único que se muestra.
            val settings = RecordingSettingsStore.load(context)
            permissions.launch(RecordingController.missingPermissions(context, settings.audio).toTypedArray())
            return
        }
        start()
    }

    private fun start() {
        val context = fragment.context ?: return
        if (RecordingController.startVideo(context, CaptureOrigin.COLLECTION)) {
            val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
            fragment.view?.performHapticFeedback(feedback)
        }
    }
}
