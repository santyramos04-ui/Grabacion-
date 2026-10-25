package com.santy.grabacion.gate

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.santy.grabacion.R
import com.santy.grabacion.core.Routes
import com.santy.grabacion.core.VaultGate
import com.santy.grabacion.core.applySystemBarPadding

/**
 * Punto de entrada de la app: muestra el logo un momento y abre la colección. Siempre cierra
 * el vault, aunque la app se haya cerrado estando dentro. No tiene ningún acceso al vault.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val goToCollection = Runnable {
        // Borra la tarea anterior: si el vault quedó en segundo plano, Atrás no debe volver a él.
        startActivity(Routes.collection(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        VaultGate.lock(this)
        setContentView(R.layout.activity_splash)
        findViewById<View>(R.id.root).applySystemBarPadding()
    }

    override fun onStart() {
        super.onStart()
        if (!isFinishing) handler.postDelayed(goToCollection, SPLASH_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(goToCollection)
        super.onStop()
    }

    private companion object {
        const val SPLASH_MS = 1_000L
    }
}
