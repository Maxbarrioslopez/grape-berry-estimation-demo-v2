/**
 * LoginActivity.kt
 *
 * Purpose: Entry point for user authentication.
 * Responsibility: Validate credentials and navigate to Registration or Recovery.
 *
 * In DEMO_MODE: The real login UI is preserved for architectural demonstration.
 * The login action is intercepted locally — no backend call is made.
 * Registration and password recovery screens remain navigable for UI inspection.
 */
package com.gaiaspa.metrics_detection.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gaiaspa.metrics_detection.BuildConfig
import com.gaiaspa.metrics_detection.FeatureFlags
import com.gaiaspa.metrics_detection.MainActivity
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.databinding.ActivityLoginBinding
import com.gaiaspa.metrics_detection.data.model.request.LoginRequest
import com.gaiaspa.metrics_detection.i18n.LanguagePreferenceManager
import com.gaiaspa.metrics_detection.network.ApiClient
import com.gaiaspa.metrics_detection.network.ApiService
import com.gaiaspa.metrics_detection.network.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var apiService: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (FeatureFlags.FEATURE_LANGUAGE_SWITCH) {
            LanguagePreferenceManager.applySavedLanguageIfAny(this)
        }
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (BuildConfig.DEMO_MODE) {
            setupDemoMode()
            return
        }

        apiService = ApiClient.create(applicationContext)

        if (TokenProvider.isLoggedIn()) {
            navigateToMain()
            return
        }

        setupLanguageButton()

        binding.btnLogin.setOnClickListener {
            performLogin()
        }

        binding.tvSignUp.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, RecoveryActivity::class.java))
        }
    }

    /**
     * In DEMO_MODE: preserves the real login UI for architectural demonstration.
     * Login button is intercepted — no backend call, no credentials required.
     * Registration and recovery screens remain navigable for UI inspection.
     */
    private fun setupDemoMode() {
        setupLanguageButton()

        binding.btnLogin.setOnClickListener {
            performDemoModeLogin()
        }

        binding.tvSignUp.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, RecoveryActivity::class.java))
        }
    }

    private fun performDemoModeLogin() {
        binding.root.hideKeyboard()
        Toast.makeText(
            this,
            getString(R.string.demo_login_intercepted),
            Toast.LENGTH_LONG
        ).show()
        navigateToMain()
    }

    private fun setupLanguageButton() {
        binding.btnLanguage.visibility =
            if (FeatureFlags.FEATURE_LANGUAGE_SWITCH) View.VISIBLE else View.GONE
        binding.btnLanguage.setOnClickListener {
            if (!FeatureFlags.FEATURE_LANGUAGE_SWITCH) return@setOnClickListener
            LanguagePreferenceManager.showSelector(
                activity = this,
                cancelable = true
            )
        }
    }

    private fun View.hideKeyboard() {
        (this.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(this.windowToken, 0)
    }

    private fun setLoadingState(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !isLoading
        val tintRes = if (isLoading) R.color.button_disabled else R.color.button_enabled
        binding.btnLogin.backgroundTintList = ContextCompat.getColorStateList(this, tintRes)
    }

    private fun performLogin() {
        val email = binding.etEmail.text.toString().trim().lowercase()
        val password = binding.etPassword.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, getString(R.string.login_empty_fields), Toast.LENGTH_SHORT).show()
            return
        }

        binding.root.hideKeyboard()
        setLoadingState(true)

        lifecycleScope.launch {
            try {
                val loginResponse = withContext(Dispatchers.IO) {
                    apiService.login(LoginRequest(email, password))
                }

                val body = loginResponse.body()
                if (!loginResponse.isSuccessful || body == null) {
                    Toast.makeText(this@LoginActivity, getString(R.string.login_invalid_credentials), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                TokenProvider.apply {
                    saveToken(body.accessToken)
                    saveRefreshToken(body.refreshToken)
                    saveIsLoggedIn(true)
                }

                val profileResponse = withContext(Dispatchers.IO) { apiService.getProfile() }
                profileResponse.body()?.let { profile ->
                    TokenProvider.saveUserId(profile.userId)
                    TokenProvider.saveRole(profile.role) 
                }

                navigateToMain()
            } catch (e: Exception) {
                Log.e("LoginActivity", "Login failure", e)
                Toast.makeText(this@LoginActivity, getString(R.string.connection_error), Toast.LENGTH_SHORT).show()
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    setLoadingState(false)
                }
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
