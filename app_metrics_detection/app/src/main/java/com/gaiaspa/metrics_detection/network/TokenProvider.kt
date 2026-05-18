/**
 * TokenProvider.kt
 *
 * Propósito: Gestionar de forma segura la persistencia de la sesión del usuario.
 * Responsabilidad: Almacenar y recuperar tokens de acceso, refresco, ID de usuario y roles.
 *
 * Seguridad: Usa EncryptedSharedPreferences con AES-256 GCM/SIV para proteger
 * los datos sensibles en reposo. Ante corrupción del keystore, intenta
 * recuperación limpiando los archivos de claves y recreando las preferencias.
 */
package com.gaiaspa.metrics_detection.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

object TokenProvider {

    private const val TAG = "TokenProvider"
    private const val PREFS_FILENAME = "auth_prefs"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var sharedPreferences: SharedPreferences? = null

    /**
     * Inicializa el singleton con el application context.
     * Implementa double-checked locking para garantizar inicialización única
     * incluso desde múltiples hilos.
     *
     * @param context cualquier contexto; internamente se usa solo el applicationContext.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (sharedPreferences != null) return

        synchronized(this) {
            if (sharedPreferences != null) return

            val resolvedContext = requireNotNull(appContext) {
                "TokenProvider requiere applicationContext para inicializarse"
            }

            try {
                sharedPreferences = createEncryptedPrefs(resolvedContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error inicializando EncryptedSharedPreferences. Intentando recuperacion...", e)
                clearEncryptedPrefsFiles(resolvedContext)
                try {
                    sharedPreferences = createEncryptedPrefs(resolvedContext)
                } catch (retryException: Exception) {
                    Log.e(TAG, "Fallo critico en EncryptedSharedPreferences", retryException)
                }
            }
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILENAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Limpia los archivos de EncryptedSharedPreferences corruptos para forzar
     * una recreación limpia en el siguiente intento de inicialización.
     */
    private fun clearEncryptedPrefsFiles(context: Context) {
        try {
            context.deleteSharedPreferences(PREFS_FILENAME)
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            // Los keysets de AndroidX Security Crypto deben eliminarse manualmente
            val keysetFiles = listOf(
                "__androidx_security_crypto_encrypted_prefs_keyset__",
                "__androidx_security_crypto_encrypted_prefs_value_keyset__",
                "$PREFS_FILENAME.xml"
            )
            keysetFiles.forEach { fileName ->
                val file = File(prefsDir, fileName)
                if (file.exists()) file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error durante la limpieza de archivos", e)
        }
    }

    /** Obtiene las SharedPreferences cifradas o lanza IllegalStateException si no se inicializó. */
    private fun prefs(): SharedPreferences {
        sharedPreferences?.let { return it }
        val resolvedContext = appContext ?: throw IllegalStateException("TokenProvider no fue inicializado")
        init(resolvedContext)
        return checkNotNull(sharedPreferences)
    }

    // ── Access Token ─────────────────────────────────────────────────────────

    /** @return el access token JWT actual, o cadena vacía si no existe. */
    fun getToken(): String = prefs().getString("ACCESS_TOKEN", "") ?: ""
    /** Persiste el access token JWT. */
    fun saveToken(token: String) = prefs().edit().putString("ACCESS_TOKEN", token).apply()

    // ── Refresh Token ────────────────────────────────────────────────────────

    /** @return el refresh token actual, o cadena vacía si no existe. */
    fun getRefreshToken(): String = prefs().getString("REFRESH_TOKEN", "") ?: ""
    /** Persiste el refresh token. */
    fun saveRefreshToken(refreshToken: String) = prefs().edit().putString("REFRESH_TOKEN", refreshToken).apply()

    // ── Estado de sesión ─────────────────────────────────────────────────────

    /** @return true si el usuario tiene una sesión activa. */
    fun isLoggedIn(): Boolean = prefs().getBoolean("IS_LOGGED_IN", false)
    /** Marca la sesión como activa o inactiva. */
    fun saveIsLoggedIn(isLoggedIn: Boolean) = prefs().edit().putBoolean("IS_LOGGED_IN", isLoggedIn).apply()

    // ── Datos de usuario ─────────────────────────────────────────────────────

    /** @return el ID del usuario autenticado. */
    fun getUserId(): String = prefs().getString("USER_ID", "") ?: ""
    /** Persiste el ID del usuario. */
    fun saveUserId(userId: String) = prefs().edit().putString("USER_ID", userId).apply()

    /** @return el rol del usuario (por defecto "user"). */
    fun getRole(): String = prefs().getString("USER_ROLE", "user") ?: "user"
    /** Persiste el rol del usuario. */
    fun saveRole(role: String) = prefs().edit().putString("USER_ROLE", role).apply()

    /** @return el ID de la empresa asociada al usuario. */
    fun getCompanyId(): String = prefs().getString("COMPANY_ID", "") ?: ""
    /** Persiste el ID de la empresa. */
    fun saveCompanyId(id: String) = prefs().edit().putString("COMPANY_ID", id).apply()

    // ── Ciclo de vida de sesión ──────────────────────────────────────────────

    /**
     * Limpia todas las credenciales y datos de sesión almacenados.
     * Tras llamar a este método [isLoggedIn] retorna false.
     */
    fun logout() {
        try {
            prefs().edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error during logout clearing prefs", e)
        }
    }

    /** Alias semántico de [logout]. */
    fun clearSession() = logout()
}
