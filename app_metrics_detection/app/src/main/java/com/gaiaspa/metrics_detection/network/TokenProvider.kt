/**
 * TokenProvider.kt
 *
 * Purpose: Securely manage user session persistence.
 * Responsibility: Store and retrieve access tokens, refresh tokens, user ID, and roles.
 *
 * Security: Uses EncryptedSharedPreferences with AES-256 GCM/SIV to protect
 * sensitive data at rest. In case of keystore corruption, attempts
 * recovery by cleaning up key files and recreating preferences.
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
     * Initializes the singleton with the application context.
     * Implements double-checked locking to guarantee unique initialization
     * even from multiple threads.
     *
     * @param context any context; internally only applicationContext is used.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (sharedPreferences != null) return

        synchronized(this) {
            if (sharedPreferences != null) return

            val resolvedContext = requireNotNull(appContext) {
                "TokenProvider requires applicationContext for initialization"
            }

            try {
                sharedPreferences = createEncryptedPrefs(resolvedContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing EncryptedSharedPreferences. Attempting recovery...", e)
                clearEncryptedPrefsFiles(resolvedContext)
                try {
                    sharedPreferences = createEncryptedPrefs(resolvedContext)
                } catch (retryException: Exception) {
                    Log.e(TAG, "Critical failure in EncryptedSharedPreferences", retryException)
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
     * Cleans up corrupted EncryptedSharedPreferences files to force
     * a clean recreation on the next initialization attempt.
     */
    private fun clearEncryptedPrefsFiles(context: Context) {
        try {
            context.deleteSharedPreferences(PREFS_FILENAME)
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            // AndroidX Security Crypto keysets must be manually deleted
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
            Log.e(TAG, "Error during file cleanup", e)
        }
    }

    /** Retrieves the encrypted SharedPreferences or throws IllegalStateException if not initialized. */
    private fun prefs(): SharedPreferences {
        sharedPreferences?.let { return it }
        val resolvedContext = appContext ?: throw IllegalStateException("TokenProvider was not initialized")
        init(resolvedContext)
        return checkNotNull(sharedPreferences)
    }

    // ── Access Token ─────────────────────────────────────────────────────────

    /** @return the current JWT access token, or empty string if it does not exist. */
    fun getToken(): String = prefs().getString("ACCESS_TOKEN", "") ?: ""
    /** Persists the JWT access token. */
    fun saveToken(token: String) = prefs().edit().putString("ACCESS_TOKEN", token).apply()

    // ── Refresh Token ────────────────────────────────────────────────────────

    /** @return the current refresh token, or empty string if it does not exist. */
    fun getRefreshToken(): String = prefs().getString("REFRESH_TOKEN", "") ?: ""
    /** Persists the refresh token. */
    fun saveRefreshToken(refreshToken: String) = prefs().edit().putString("REFRESH_TOKEN", refreshToken).apply()

    // ── Session state ─────────────────────────────────────────────────────────

    /** @return true if the user has an active session. */
    fun isLoggedIn(): Boolean = prefs().getBoolean("IS_LOGGED_IN", false)
    /** Marks the session as active or inactive. */
    fun saveIsLoggedIn(isLoggedIn: Boolean) = prefs().edit().putBoolean("IS_LOGGED_IN", isLoggedIn).apply()

    // ── User data ─────────────────────────────────────────────────────────────

    /** @return the authenticated user's ID. */
    fun getUserId(): String = prefs().getString("USER_ID", "") ?: ""
    /** Persists the user ID. */
    fun saveUserId(userId: String) = prefs().edit().putString("USER_ID", userId).apply()

    /** @return the user's role (default "user"). */
    fun getRole(): String = prefs().getString("USER_ROLE", "user") ?: "user"
    /** Persists the user's role. */
    fun saveRole(role: String) = prefs().edit().putString("USER_ROLE", role).apply()

    /** @return the company ID associated with the user. */
    fun getCompanyId(): String = prefs().getString("COMPANY_ID", "") ?: ""
    /** Persists the company ID. */
    fun saveCompanyId(id: String) = prefs().edit().putString("COMPANY_ID", id).apply()

    // ── Session lifecycle ─────────────────────────────────────────────────────

    /**
     * Clears all stored credentials and session data.
     * After calling this method [isLoggedIn] returns false.
     */
    fun logout() {
        try {
            prefs().edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error during logout clearing prefs", e)
        }
    }

    /** Semantic alias for [logout]. */
    fun clearSession() = logout()
}
