// LauncherActivity.kt
package com.gaiaspa.metrics_detection

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.gaiaspa.metrics_detection.auth.LoginActivity
import com.gaiaspa.metrics_detection.network.TokenProvider

class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar TokenProvider
        TokenProvider.init(this)

        // Navegar a la pantalla correspondiente basado en el estado de logueo
        navigate()
    }

    /**
     * Navega a MainActivity si el usuario está logueado, de lo contrario a LoginActivity.
     */
    private fun navigate() {
        val destination = if (TokenProvider.isLoggedIn()) {
            MainActivity::class.java
        } else {
            LoginActivity::class.java
        }

        startActivity(Intent(this, destination))
        finish() // Finalizar LauncherActivity para no dejarla en la pila de actividades
    }
}
