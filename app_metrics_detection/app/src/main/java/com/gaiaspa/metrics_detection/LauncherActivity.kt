package com.gaiaspa.metrics_detection

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.gaiaspa.metrics_detection.auth.LoginActivity
import com.gaiaspa.metrics_detection.i18n.LanguagePreferenceManager
import com.gaiaspa.metrics_detection.network.TokenProvider

/**
 * Entry point that decides whether to show login or main screen.
 *
 * If the user already has a valid session (TokenProvider.isLoggedIn()),
 * navigates directly to MainActivity. Otherwise shows the login flow.
 * Optionally shows the language selector on first launch.
 */
class LauncherActivity : AppCompatActivity() {

    /**
     * Inicializa TokenProvider, resuelve el idioma si el feature flag está activo
     * y redirige a la pantalla correspondiente según el estado de sesión.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar TokenProvider
        TokenProvider.init(this)

        if (FeatureFlags.FEATURE_LANGUAGE_SWITCH) {
            if (LanguagePreferenceManager.hasSavedLanguage(this)) {
                LanguagePreferenceManager.applySavedLanguageIfAny(this)
                navigate()
            } else {
                LanguagePreferenceManager.showSelector(
                    activity = this,
                    cancelable = false,
                    onSelected = { navigate() }
                )
            }
            return
        }

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
