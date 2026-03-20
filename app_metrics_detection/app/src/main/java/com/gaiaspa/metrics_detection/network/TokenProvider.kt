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
                
                // Paso 1: Limpiar archivos corruptos
                clearEncryptedPrefsFiles(resolvedContext)
                
                try {
                    // Paso 2: Reintento unico
                    sharedPreferences = createEncryptedPrefs(resolvedContext)
                    Log.i(TAG, "Recuperacion de EncryptedSharedPreferences exitosa.")
                } catch (retryException: Exception) {
                    Log.e(TAG, "Fallo critico en reintento de EncryptedSharedPreferences", retryException)
                    throw retryException // Rethrow si el fallo persiste tras la limpieza
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
            // 1. Borrar SharedPreferences via API
            context.deleteSharedPreferences(PREFS_FILENAME)

            // 2. Borrar archivos de Keyset de Tink manualmente (usados internamente por EncryptedSharedPreferences)
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            val keysetFiles = listOf(
                "__androidx_security_crypto_encrypted_prefs_keyset__",
                "__androidx_security_crypto_encrypted_prefs_value_keyset__",
                "$PREFS_FILENAME.xml"
            )

            keysetFiles.forEach { fileName ->
                val file = File(prefsDir, fileName)
                if (file.exists()) {
                    val deleted = file.delete()
                    Log.w(TAG, "Archivo corrupto eliminado: $fileName ($deleted)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error durante la limpieza de archivos de preferencias", e)
        }
    }

    private fun prefs(): SharedPreferences {
        sharedPreferences?.let { return it }

        val resolvedContext = appContext
            ?: throw IllegalStateException("TokenProvider no fue inicializado")
        init(resolvedContext)

        return checkNotNull(sharedPreferences) {
            "TokenProvider no pudo inicializar SharedPreferences"
        }
    }

    fun getToken(): String {
        return prefs().getString("ACCESS_TOKEN", "") ?: ""
    }

    fun saveToken(token: String) {
        prefs().edit().putString("ACCESS_TOKEN", token).apply()
    }

    fun getRefreshToken(): String {
        return prefs().getString("REFRESH_TOKEN", "") ?: ""
    }

    fun saveRefreshToken(refreshToken: String) {
        prefs().edit().putString("REFRESH_TOKEN", refreshToken).apply()
    }

    fun isLoggedIn(): Boolean {
        return prefs().getBoolean("IS_LOGGED_IN", false)
    }

    fun saveIsLoggedIn(isLoggedIn: Boolean) {
        prefs().edit().putBoolean("IS_LOGGED_IN", isLoggedIn).apply()
    }

    fun getUserId(): String {
        return prefs().getString("USER_ID", "") ?: ""
    }

    fun saveUserId(userId: String) {
        prefs().edit().putString("USER_ID", userId).apply()
    }

    fun logout() {
        try {
            prefs().edit().clear().apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
