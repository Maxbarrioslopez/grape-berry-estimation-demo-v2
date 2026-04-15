/**
 * RecoveryActivity.kt
 *
 * Propósito: Gestionar la recuperación de acceso para usuarios que olvidaron su contraseña.
 * Responsabilidad: Realizar el cambio de contraseña en un solo paso validando Email y RUT.
 * 
 * Flujo: Validar datos locales -> Petición Directa al Backend -> Éxito/Error -> Login.
 */
package com.gaiaspa.metrics_detection.auth

import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

        apiService = ApiClient.create(applicationContext)

        binding.btnChangePassword.setOnClickListener {
            performChangePassword()
        }

        binding.tvBackToLogin.setOnClickListener {
            finish()
        }
    }

    /**
     * Realiza el cambio de contraseña validando Email, RUT y la nueva clave.
     * Envía 'newContraseña' según contrato del backend.
     */
    private fun performChangePassword() {
        val email = binding.etEmailRecovery.text.toString().trim().lowercase()
        val rut = binding.etRutRecovery.text.toString().trim()
        val newPassword = binding.etNewPasswordRecovery.text.toString().trim()

        // Validaciones en cliente
        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Ingrese un correo electrónico válido", Toast.LENGTH_SHORT).show()
            return
        }

        if (rut.isEmpty()) {
            Toast.makeText(this, "El RUT es obligatorio", Toast.LENGTH_SHORT).show()
            return
        }

        if (newPassword.length < 6) {
            Toast.makeText(this, "La nueva contraseña debe tener al menos 6 caracteres", Toast.LENGTH_SHORT).show()
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
                        "Contraseña actualizada correctamente", 
                        Toast.LENGTH_LONG).show()
                    
                    // Limpieza preventiva de sesión local
                    TokenProvider.clearSession()
                    finish() // Regresa a LoginActivity
                } else {
                    val message = when(response.code()) {
                        404 -> "Los datos ingresados no coinciden con nuestros registros"
                        else -> "No se pudo cambiar la contraseña (${response.code()})"
                    }
                    Toast.makeText(this@RecoveryActivity, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@RecoveryActivity, "Error de conexión: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
