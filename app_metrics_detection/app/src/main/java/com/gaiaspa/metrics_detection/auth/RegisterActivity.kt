/**
 * RegisterActivity.kt
 *
 * Propósito: Gestionar el alta de nuevos usuarios invitados en el sistema.
 * Responsabilidad: Recolectar datos del usuario en dos etapas (Invitación -> Perfil),
 * validar reglas de negocio locales y procesar el registro corporativo.
 */
package com.gaiaspa.metrics_detection.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.databinding.ActivityRegisterBinding
import com.gaiaspa.metrics_detection.network.ApiClient
import com.gaiaspa.metrics_detection.network.ApiService
import com.gaiaspa.metrics_detection.data.model.request.CompanyRegisterRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var apiService: ApiService
    private var currentStage = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiService = ApiClient.create(applicationContext)

        binding.btnRegister.setOnClickListener {
            if (currentStage == 1) {
                proceedToStage2()
            } else {
                performRegister()
            }
        }

        binding.tvLogin.setOnClickListener {
            finish()
        }
    }

    /**
     * Valida la etapa 1 (Email e Invitación) y muestra el resto del formulario.
     */
    private fun proceedToStage2() {
        val email = binding.etEmailRegister.text.toString().trim()
        val inviteCode = binding.etInviteCode.text.toString().trim()

        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, getString(R.string.invalid_email), Toast.LENGTH_SHORT).show()
            return
        }

        if (inviteCode.isEmpty()) {
            Toast.makeText(this, getString(R.string.invite_code_required), Toast.LENGTH_SHORT).show()
            return
        }

        // Transición visual
        binding.layoutStage1.visibility = View.GONE
        binding.layoutStage2.visibility = View.VISIBLE
        binding.btnRegister.text = getString(R.string.register_final_button)
        currentStage = 2
    }

    private fun setLoadingState(isLoading: Boolean) {
        binding.btnRegister.isEnabled = !isLoading
        val tintRes = if (isLoading) R.color.button_disabled else R.color.button_enabled
        binding.btnRegister.backgroundTintList = ContextCompat.getColorStateList(this, tintRes)
    }

    /**
     * Procesa la solicitud final de registro usando el DTO CompanyRegisterRequest.
     *
     * La validación de consentimiento de datos (cbConsent) es local.
     * El backend no tiene campo de consentimiento; si se requiere validación
     * del lado del servidor, debe agregarse en CompanyRegisterRequest y ApiService.
     */
    private fun performRegister() {
        val name = binding.etNameRegister.text.toString().trim()
        val lastname = binding.etLastnameRegister.text.toString().trim()
        val rut = binding.etRutRegister.text.toString().trim()
        val email = binding.etEmailRegister.text.toString().trim().lowercase()
        val inviteCode = binding.etInviteCode.text.toString().trim()
        val password = binding.etPasswordRegister.text.toString().trim()
        val confirmPassword = binding.etConfirmPasswordRegister.text.toString().trim()

        if (name.isEmpty() || lastname.isEmpty() || rut.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, getString(R.string.complete_all_fields), Toast.LENGTH_SHORT).show()
            return
        }

        if (password != confirmPassword) {
            Toast.makeText(this, getString(R.string.passwords_not_match), Toast.LENGTH_SHORT).show()
            return
        }

        if (password.length < 6) {
            Toast.makeText(this, getString(R.string.password_min_length), Toast.LENGTH_SHORT).show()
            return
        }

        if (!binding.cbConsent.isChecked) {
            Toast.makeText(this, getString(R.string.consent_required), Toast.LENGTH_LONG).show()
            return
        }

        hideKeyboard()
        setLoadingState(true)

        lifecycleScope.launch {
            try {
                val request = CompanyRegisterRequest(
                    email = email,
                    name = name,
                    lastname = lastname,
                    password = password,
                    rut = rut,
                    inviteCode = inviteCode
                )

                val response = withContext(Dispatchers.IO) {
                    apiService.registerCompany(request) 
                }

                if (response.isSuccessful) {
                    Toast.makeText(this@RegisterActivity, getString(R.string.account_created_success), Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    val errorMsg = when (response.code()) {
                        401 -> getString(R.string.invalid_invite_code)
                        403 -> getString(R.string.invite_code_expired)
                        409 -> getString(R.string.email_already_registered)
                        else -> "${getString(R.string.network_error_prefix)}${response.code()}"
                    }
                    Toast.makeText(this@RegisterActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@RegisterActivity, "${getString(R.string.network_error_prefix)}${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                setLoadingState(false)
            }
        }
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
}
