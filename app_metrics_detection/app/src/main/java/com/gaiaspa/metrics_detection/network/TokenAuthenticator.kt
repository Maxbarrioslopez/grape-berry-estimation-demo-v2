/**
 * OkHttp Authenticator that automatically renews the access token when the
 * backend responds with HTTP 401 (Unauthorized).
 *
 * Renewal flow:
 * 1. Verifies that the same response chain has not been retried more than 2 times.
 * 2. If the Authorization header matches the stored token, calls
 *    /auth/refresh-token synchronously.
 * 3. If renewal is successful, rebuilds the original request with the new token.
 *    For /auth/logout it also replaces the refreshToken in the body.
 * 4. If it fails, forces logout by redirecting to LoginActivity.
 *
 * Creates its own internal ApiService (without AuthInterceptor) to avoid infinite
 * interception loops during renewal.
 *
 * @property appContext application context used to initialize TokenProvider
 *                      and launch LoginActivity in case of forced logout.
 */
// src/main/java/com/gaiaspa/metrics_detection/network/TokenAuthenticator.kt
package com.gaiaspa.metrics_detection.network

import android.content.Context
import android.content.Intent
import android.util.Log
import com.gaiaspa.metrics_detection.BuildConfig
import com.gaiaspa.metrics_detection.auth.LoginActivity
import com.gaiaspa.metrics_detection.data.model.request.RefreshTokenRequest
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class TokenAuthenticator(
    private val appContext: Context
) : Authenticator {

    companion object {
        private const val TAG = "TokenAuthenticator"
    }

    private val apiService: ApiService

    init {
        TokenProvider.init(appContext)

        // Create a "clean" OkHttpClient (no interceptor or authenticator)
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(ApiClient.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(ApiService::class.java)
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        try {
            TokenProvider.init(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "Authenticator: could not initialize TokenProvider", e)
            return null
        }

        // 1) If we have already tried before → abort to avoid looping
        if (responseCount(response) >= 2) {
            Log.e(TAG, "Authenticator: too many retries")
            return null
        }

        synchronized(this) {
            val currentToken = TokenProvider.getToken()
            val originalRequest = response.request
            val originalPath = originalRequest.url.encodedPath
            val originalBody = originalRequest.body

            // 2) If the header that failed was exactly the stored token...
            if (originalRequest.header("Authorization") == "Bearer $currentToken") {
                // 3) Try to refresh
                if (refreshToken()) {
                    val newAccess = TokenProvider.getToken()       // already saved the new one
                    val newRefresh = TokenProvider.getRefreshToken()

                    // 4) Rebuild the original request with the renewed access token
                    val newReqBuilder = originalRequest.newBuilder()
                        .header("Authorization", "Bearer $newAccess")

                    // 5) If it was /auth/logout, also replace the refreshToken in the body
                    if (originalPath.contains("/auth/logout") && originalBody != null) {
                        val updatedBody = replaceRefreshTokenInBody(originalBody, newRefresh)
                        newReqBuilder.method(originalRequest.method, updatedBody)
                    } else {
                        newReqBuilder.method(originalRequest.method, originalBody)
                    }

                    return newReqBuilder.build()
                } else {
                    // 6) Refresh failed → force logout
                    Log.e(TAG, "Authenticator: refresh failed, logging out")
                    forceLogout()
                    return null
                }
            } else {
                // 7) If the token was already updated by another thread, retry with the latest token
                return originalRequest.newBuilder()
                    .header("Authorization", "Bearer ${TokenProvider.getToken()}")
                    .build()
            }
        }
    }

    /** Counts how many times we have received 401 in this response chain */
    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result++; prior = prior.priorResponse
        }
        return result
    }

    /**
     * Makes the synchronous call to /auth/refresh-token.
     * Returns true if it successfully renewed and saved the tokens.
     */
    private fun refreshToken(): Boolean {
        val oldRefresh = TokenProvider.getRefreshToken()
        if (oldRefresh.isBlank()) {
            Log.e(TAG, "No refresh token to renew")
            return false
        }

        val call = apiService.refreshTokenSync(RefreshTokenRequest(oldRefresh))
        return try {
            val resp = call.execute()
            if (resp.isSuccessful) {
                val body = resp.body()
                if (body != null) {
                    // Always save the new pair
                    TokenProvider.saveToken(body.accessToken)
                    TokenProvider.saveRefreshToken(body.refreshToken)
                    Log.d(TAG, "Tokens renewed OK")
                    true
                } else {
                    Log.e(TAG, "Refresh successful but body null")
                    false
                }
            } else {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Refresh failed: ${resp.code()} / ${resp.errorBody()?.string()}")
                } else {
                    Log.e(TAG, "Refresh failed: code ${resp.code()}")
                }
                false
            }
        } catch (e: IOException) {
            Log.e(TAG, "Exception refreshing token", e)
            false
        }
    }

    /** Replaces the refreshToken field in the JSON body with the new one */
    private fun replaceRefreshTokenInBody(
        originalBody: RequestBody,
        newRefreshToken: String
    ): RequestBody {
        return try {
            val buffer = okio.Buffer()
            originalBody.writeTo(buffer)
            val originalJson = buffer.readUtf8()
            val updatedJson = originalJson.replace(
                "\"refreshToken\":\"[^\"]*\"".toRegex(),
                "\"refreshToken\":\"$newRefreshToken\""
            )
            updatedJson.toRequestBody(originalBody.contentType())
        } catch (e: Exception) {
            Log.e(TAG, "Could not replace refreshToken in body", e)
            originalBody
        }
    }

    /** Clears credentials and launches LoginActivity, clearing the task stack */
    private fun forceLogout() {
        TokenProvider.logout()
        val intent = Intent(appContext, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        appContext.startActivity(intent)
    }
}
