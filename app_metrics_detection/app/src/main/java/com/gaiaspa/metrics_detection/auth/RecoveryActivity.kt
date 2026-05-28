/**
 * RecoveryActivity.kt
 *
 * Purpose: Manage access recovery for users who forgot their password.
 * Responsibility: Perform password change in a single step by validating Email and RUT.
 *
 * Flow: Validate local data -> Direct Request to Backend -> Success/Error -> Login.
 *
 * In DEMO_MODE: The real recovery UI is preserved for architectural demonstration.
 * Submit shows a demo message — no backend call is made.
 */
package com.gaiaspa.metrics_detection.auth

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gaiaspa.metrics_detection.BuildConfig
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.databinding.ActivityRecoveryBinding
import com.gaiaspa.metrics_detection.network.ApiClient
import com.gaiaspa.metrics_detection.network.ApiService
import com.gaiaspa.metrics_detection.data.model.request.PasswordChangeRequest
import com.gaiaspa.metrics_detection.network.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecoveryBinding
    private lateinit var apiService: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityRecoveryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (BuildConfig.DEMO_MODE) {
            setupDemoMode()
            return
        }

        apiService = ApiClient.create(applicationContext)

        binding.btnChangePassword.setOnClickListener {
            performChangePassword()
        }

        binding.tvBackToLogin.setOnClickListener {
            finish()
        }
    }

    /**
     * In DEMO_MODE: preserves the real password recovery UI for architectural
     * demonstration. Submit shows an info message — no backend call is made.
     */
    private fun setupDemoMode() {
        binding.btnChangePassword.setOnClickListener {
            Toast.makeText(
                this,
                getString(R.string.demo_recovery_intercepted),
                Toast.LENGTH_LONG
            ).show()
            finish()
        }

        binding.tvBackToLogin.setOnClickListener {
            finish()
        }
    }

    /**
     * Performs the password change by validating Email, RUT, and the new password.
     * Sends 'newContraseña' according to the backend contract.
     */
    private fun performChangePassword() {
        val email = binding.etEmailRecovery.text.toString().trim().lowercase()
        val rut = binding.etRutRecovery.text.toString().trim()
        val newPassword = binding.etNewPasswordRecovery.text.toString().trim()

        // Client-side validations
        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, getString(R.string.invalid_email), Toast.LENGTH_SHORT).show()
            return
        }

        if (rut.isEmpty()) {
            Toast.makeText(this, getString(R.string.rut_required), Toast.LENGTH_SHORT).show()
            return
        }

        if (newPassword.length < 6) {
            Toast.makeText(this, getString(R.string.password_min_length), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val request = PasswordChangeRequest(email, rut, newPassword)
                val response = withContext(Dispatchers.IO) {
                    apiService.changePassword(request)
                }

                if (response.isSuccessful) {
                    Toast.makeText(this@RecoveryActivity, 
                        "Password updated successfully", 
                        Toast.LENGTH_LONG).show()
                    
                    // Preventive cleanup of local session
                    TokenProvider.clearSession()
                    finish() // Returns to LoginActivity
                } else {
                    val message = when(response.code()) {
                        404 -> "The data entered does not match our records"
                        else -> "Could not change password (${response.code()})"
                    }
                    Toast.makeText(this@RecoveryActivity, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@RecoveryActivity, "${getString(R.string.connection_error_recovery)}: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
