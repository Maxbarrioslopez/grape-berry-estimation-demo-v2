/**
 * OkHttp Interceptor that injects the header `Authorization: Bearer <token>` into
 * every outgoing request, except for public authentication endpoints.
 *
 * Excluded endpoints (do not require token):
 * - `/auth/login` — the user does not yet have a token.
 * - `/auth/refresh-token` — prevents loops during renewal; the
 *   [TokenAuthenticator] uses its own client without this interceptor.
 *
 * NOTE: `/auth/logout` is INTENTIONALLY NOT excluded because the backend requires
 * the current access token to invalidate the session.
 *
 * If [TokenProvider] is not initialized or the token is empty, the request
 * proceeds without the header (the backend will return 401, which will trigger
 * the [TokenAuthenticator] if configured).
 */
package com.gaiaspa.metrics_detection.network

import android.util.Log
import com.gaiaspa.metrics_detection.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor : Interceptor {

    companion object {
        private const val TAG = "AuthInterceptor"
    }

    /**
     * Intercepts the outgoing request and injects the Authorization header if
     * appropriate.
     *
     * @param chain the OkHttp interceptor chain.
     * @return the server response after processing the (possibly modified) request.
     */
    override fun intercept(chain: Interceptor.Chain): Response {

        val originalRequest = chain.request()
        val path = originalRequest.url.encodedPath

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Intercepted request: $path")
        }

        // Do not add Authorization for login and refresh-token
        // (DO NOT exclude logout)
        if (path.equals("/auth/login", ignoreCase = true) ||
            path.equals("/auth/refresh-token", ignoreCase = true)
        ) {
            return chain.proceed(originalRequest)
        }

        val accessToken = try {
            TokenProvider.getToken()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "TokenProvider not initialized; proceeding without Authorization", e)
            ""
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Access token present: ${accessToken.isNotBlank()}")
        }

        if (accessToken.isBlank()) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Empty access token; sending request without Authorization")
            }
            return chain.proceed(originalRequest)
        }

        val newRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Authorization header injected")
        }

        return chain.proceed(newRequest)
    }
}