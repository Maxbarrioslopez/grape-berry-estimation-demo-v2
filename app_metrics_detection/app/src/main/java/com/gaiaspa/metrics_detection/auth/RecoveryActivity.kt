/**
 * RecoveryActivity.kt
 *
 * Propósito: Gestionar la recuperación de acceso para usuarios que olvidaron su contraseña.
 * Responsabilidad: Orquestar la solicitud de recuperación (Etapa 1) y el reseteo final (Etapa 2).
 */
package com.gaiaspa.metrics_detection.auth

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gaiaspa.metrics_detection.databinding.ActivityRecoveryBinding
import com.gaiaspa.metrics_detection.network.ApiClient
import com.gaiaspa.metrics_detection.network.ApiService
import com.gaiaspa.metrics_detection.data.model.request.RecoveryRequest
import com.gaiaspa.metrics_detection.data.model.request.ResetRequest
import com.gaiaspa.metrics_detection.network.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecoveryBinding
    private lateinit var apiService: ApiService
    private var isResetMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecoveryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiService = ApiClient.create(applicationContext)

        binding.btnRecoveryAction.setOnClickListener {
            if (!isResetMode) {
                performRecoveryRequest()
            } else {
                performPasswordReset()
            }
        }

        binding.tvBackToLogin.setOnClickListener {
            finish()
        }
    }

    /**
     * Etapa 1: Solicita el inicio del flujo de recuperación enviando Email y RUT.
     */
    private fun performRecoveryRequest() {
        val email = binding.etEmailRecovery.text.toString().trim().lowercase()
        val rut = binding.etRutRecovery.text.toString().trim()

        if (email.isEmpty() || rut.isEmpty()) {
            Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    apiService.requestRecovery(RecoveryRequest(email, rut))
                }

                // Feedback genérico por seguridad (blind feedback)
                Toast.makeText(this@RecoveryActivity, 
                    "Si los datos coinciden, recibirás un código en tu correo", 
                    Toast.LENGTH_LONG).show()

                // Cambiar a modo reset para que el usuario ingrese el token
                switchToResetMode()
            } catch (e: Exception) {
                Toast.makeText(this@RecoveryActivity, "Error de red: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchToResetMode() {
        binding.layoutRequest.visibility = View.GONE
        binding.layoutReset.visibility = View.VISIBLE
        binding.btnRecoveryAction.text = "Actualizar Contraseña"
        isResetMode = true
    }

    /**
     * Etapa 2: Envía el token recibido por correo y la nueva contraseña.
     */
    private fun performPasswordReset() {
        val token = binding.etTokenRecovery.text.toString().trim()
        val newPassword = binding.etNewPassword.text.toString().trim()

        if (token.isEmpty() || newPassword.length < 6) {
            Toast.makeText(this, "Ingresa un token válido y password min 6 caracteres", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    apiService.resetPassword(ResetRequest(token, newPassword))
                }

                if (response.isSuccessful) {
                    Toast.makeText(this@RecoveryActivity, "Contraseña actualizada correctamente", Toast.LENGTH_LONG).show()
                    // Limpieza preventiva de sesión local
                    TokenProvider.clearSession()
                    finish() // Regresa a Login
                } else {
                    Toast.makeText(this@RecoveryActivity, "Token inválido o expirado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@RecoveryActivity, "Error de red", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
