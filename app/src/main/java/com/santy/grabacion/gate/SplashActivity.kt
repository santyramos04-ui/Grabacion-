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
import com.santy.grabacion.core.UnlockTrigger
import com.santy.grabacion.core.VaultGate
import com.santy.grabacion.core.applySystemBarPadding

/**
 * Punto de entrada de la app. Siempre lleva al inventario y cierra el vault, aunque la app se
 * haya cerrado estando dentro. Cinco toques rápidos sobre el logo abren el vault.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val goToInventory = Runnable {
        // Borra la tarea anterior: si el vault quedó en segundo plano, Atrás no debe volver a él.
        startActivity(
            Routes.collection(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        VaultGate.lock(this)
        setContentView(R.layout.activity_splash)
        findViewById<View>(R.id.root).applySystemBarPadding()
        UnlockTrigger.attach(
            findViewById(R.id.logo),
            // Cada toque da 2 s más para completar el gesto antes de pasar al inventario.
            onTap = { schedule(TAP_GRACE_MS) },
            onUnlock = {
                handler.removeCallbacks(goToInventory)
                VaultGate.unlock(this)
            }
        )
    }

    override fun onStart() {
        super.onStart()
        if (!isFinishing) schedule(SPLASH_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(goToInventory)
        super.onStop()
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(goToInventory)
        handler.postDelayed(goToInventory, delayMs)
    }

    private companion object {
        const val SPLASH_MS = 1_500L
        const val TAP_GRACE_MS = 2_000L
    }
}
