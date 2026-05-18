/**
 * Interceptor OkHttp que inyecta el header `Authorization: Bearer <token>` en
 * cada petición saliente, excepto en los endpoints públicos de autenticación.
 *
 * Endpoints excluidos (no requieren token):
 * - `/auth/login` — el usuario aún no tiene token.
 * - `/auth/refresh-token` — evita bucles durante la renovación; el
 *   [TokenAuthenticator] usa su propio cliente sin este interceptor.
 *
 * NOTA: `/auth/logout` NO se excluye intencionalmente porque el backend requiere
 * el access token vigente para invalidar la sesión.
 *
 * Si [TokenProvider] no está inicializado o el token está vacío, la petición
 * prosigue sin el header (el backend devolverá 401, lo que disparará al
 * [TokenAuthenticator] si está configurado).
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
     * Intercepta la petición saliente e inyecta el header Authorization si
     * corresponde.
     *
     * @param chain la cadena de interceptores de OkHttp.
     * @return la respuesta del servidor tras procesar la petición (posiblemente modificada).
     */
    override fun intercept(chain: Interceptor.Chain): Response {

        val originalRequest = chain.request()
        val path = originalRequest.url.encodedPath

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Petición interceptada: $path")
        }

        // No añadir Authorization para login y refresh-token
        // (NO excluir logout)
        if (path.equals("/auth/login", ignoreCase = true) ||
            path.equals("/auth/refresh-token", ignoreCase = true)
        ) {
            return chain.proceed(originalRequest)
        }

        val accessToken = try {
            TokenProvider.getToken()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "TokenProvider no está inicializado; continuando sin Authorization", e)
            ""
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Access token presente: ${accessToken.isNotBlank()}")
        }

        if (accessToken.isBlank()) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Access token vacío; enviando request sin Authorization")
            }
            return chain.proceed(originalRequest)
        }

        val newRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Cabecera Authorization inyectada")
        }

        return chain.proceed(newRequest)
    }
}