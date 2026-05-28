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
 * In DEMO_MODE (academic public build): shows the mandatory Academic
 * Demonstration Agreement, then navigates to the real LoginActivity for
 * architectural UI demonstration. Authentication is intercepted locally.
 *
 * In production mode: checks TokenProvider.isLoggedIn() and routes to
 * MainActivity or LoginActivity accordingly.
 */
class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        TokenProvider.init(this)

        if (BuildConfig.DEMO_MODE) {
            handleDemoMode()
            return
        }

        handleProductionMode()
    }

    private fun handleDemoMode() {
        AcademicDemoAgreementDialog.show(
            activity = this,
            onAccepted = {
                // In DEMO_MODE, show the real login screen for architectural demonstration.
                // Authentication is intercepted locally — no backend call is made.
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            },
            onExit = {
                finishAffinity()
            }
        )
    }

    private fun handleProductionMode() {
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

        navigate()
    }

    private fun navigate() {
        val destination = if (TokenProvider.isLoggedIn()) {
            MainActivity::class.java
        } else {
            LoginActivity::class.java
        }

        startActivity(Intent(this, destination))
        finish()
    }

}
