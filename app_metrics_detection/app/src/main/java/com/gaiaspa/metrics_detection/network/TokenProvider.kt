/**
 * TokenProvider.kt
 *
 * Propósito: Gestionar de forma segura la persistencia de la sesión del usuario.
 * Responsabilidad: Almacenar y recuperar tokens de acceso, refresco, ID de usuario y roles.
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
                    throw retryException 
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

    private fun clearEncryptedPrefsFiles(context: Context) {
        try {
            context.deleteSharedPreferences(PREFS_FILENAME)
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
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

    private fun prefs(): SharedPreferences {
        sharedPreferences?.let { return it }
        val resolvedContext = appContext ?: throw IllegalStateException("TokenProvider no fue inicializado")
        init(resolvedContext)
        return checkNotNull(sharedPreferences)
    }

    fun getToken(): String = prefs().getString("ACCESS_TOKEN", "") ?: ""
    fun saveToken(token: String) = prefs().edit().putString("ACCESS_TOKEN", token).apply()

    fun getRefreshToken(): String = prefs().getString("REFRESH_TOKEN", "") ?: ""
    fun saveRefreshToken(refreshToken: String) = prefs().edit().putString("REFRESH_TOKEN", refreshToken).apply()

    fun isLoggedIn(): Boolean = prefs().getBoolean("IS_LOGGED_IN", false)
    fun saveIsLoggedIn(isLoggedIn: Boolean) = prefs().edit().putBoolean("IS_LOGGED_IN", isLoggedIn).apply()

    fun getUserId(): String = prefs().getString("USER_ID", "") ?: ""
    fun saveUserId(userId: String) = prefs().edit().putString("USER_ID", userId).apply()

    fun saveRole(role: String) = prefs().edit().putString("USER_ROLE", role).apply()
    fun getRole(): String = prefs().getString("USER_ROLE", "user") ?: "user"

    fun logout() {
        try {
            prefs().edit().clear().apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Alias para limpiar sesión tras recuperación de contraseña o logout forzado. */
    fun clearSession() = logout()
}
