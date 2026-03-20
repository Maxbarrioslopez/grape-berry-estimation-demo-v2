package com.gaiaspa.metrics_detection.auth

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gaiaspa.metrics_detection.MainActivity
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.databinding.ActivityLoginBinding
import com.gaiaspa.metrics_detection.data.model.request.LoginRequest
import com.gaiaspa.metrics_detection.network.ApiClient
import com.gaiaspa.metrics_detection.network.ApiService
import com.gaiaspa.metrics_detection.network.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var apiService: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflamos el binding y seteamos el layout
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicializamos ApiService
        apiService = ApiClient.create(applicationContext)

        // Si el usuario ya está logueado, redirigimos a MainActivity
        if (TokenProvider.isLoggedIn()) {
            navigateToMain()
            return
        }

        binding.btnLogin.setOnClickListener {
            performLogin()
        }
    }

    /**
     * Extensión para ocultar el teclado.
     */
    private fun View.hideKeyboard() {
        (this.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(this.windowToken, 0)
    }

    /**
     * Actualiza la interfaz durante la carga: muestra u oculta el ProgressBar y habilita o deshabilita el botón.
     */
    private fun setLoadingState(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !isLoading
        // Se asume que estos recursos están definidos correctamente.
        val tintRes = if (isLoading) R.color.button_disabled else R.color.button_enabled
        binding.btnLogin.backgroundTintList = ContextCompat.getColorStateList(this, tintRes)
    }

    /**
     * Realiza la petición de login de manera asíncrona.
     * Se asegura que, al finalizar el intento, se actualice el estado del botón.
     */
    private fun performLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        // Validar campos vacíos.
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Por favor ingrese usuario y contraseña", Toast.LENGTH_SHORT).show()
            return
        }

        // Ocultar el teclado y quitar el foco.
        binding.root.hideKeyboard()
        binding.etEmail.clearFocus()
        binding.etPassword.clearFocus()

        setLoadingState(true)

        // Variable para identificar si el login fue exitoso.
        var loginSuccess = false

        lifecycleScope.launch {
            try {
                // Realiza la petición de login en el dispatcher IO.
                val loginResponse = withContext(Dispatchers.IO) {
                    apiService.login(LoginRequest(email, password))
                }

                // Verifica la respuesta de login.
                val body = loginResponse.body()
                if (!loginResponse.isSuccessful || body == null ||
                    body.accessToken.isBlank() || body.refreshToken.isBlank()
                ) {
                    Toast.makeText(
                        this@LoginActivity,
                        "Error al procesar las credenciales.",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Al no ser exitoso, salimos, pero el bloque finally se ejecutará.
                    return@launch
                }

                // Guardamos los tokens y el estado del login.
                TokenProvider.apply {
                    saveToken(body.accessToken)
                    saveRefreshToken(body.refreshToken)
                    saveIsLoggedIn(true)
                }

                // Recupera el perfil del usuario en el dispatcher IO y guarda el userId.
                val profileResponse = withContext(Dispatchers.IO) { apiService.getProfile() }
                profileResponse.body()?.let { profile ->
                    TokenProvider.saveUserId(profile.userId)
                }

                // Marca que el login fue exitoso.
                loginSuccess = true
            } catch (e: Exception) {
                // Manejo de errores según el tipo de excepción.
                val errorMessage = when (e) {
                    is HttpException -> when (e.code()) {
                        400 -> "Solicitud incorrecta."
                        401 -> "Credenciales incorrectas."
                        500 -> "Error del servidor."
                        else -> "Error de autenticación."
                    }
                    is IOException -> "Error de conexión. Verifique su red."
                    else -> "Ocurrió un error inesperado."
                }
                Toast.makeText(this@LoginActivity, errorMessage, Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            } finally {
                // Forzar la ejecución del bloque de limpieza en el hilo principal.
                withContext(NonCancellable + Dispatchers.Main) {
                    setLoadingState(false)
                }
            }

            // Si el login fue exitoso, navega a MainActivity.
            if (loginSuccess) {
                navigateToMain()
            }
        }
    }

    /**
     * Navega a MainActivity con animaciones personalizadas y finaliza la actividad actual.
     */
    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        val options = ActivityOptions.makeCustomAnimation(
            this,
            R.anim.fade_in,
            R.anim.fade_out
        )
        startActivity(intent, options.toBundle())
        finish()
    }
}
