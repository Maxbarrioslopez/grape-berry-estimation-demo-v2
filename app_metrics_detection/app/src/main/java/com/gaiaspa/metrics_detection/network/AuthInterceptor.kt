package com.gaiaspa.metrics_detection.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor : Interceptor {

    companion object {
        private const val TAG = "AuthInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {

        val originalRequest = chain.request()
        val path = originalRequest.url.encodedPath
        Log.d(TAG, " -) Petición Interceptada")
        Log.d(TAG, " -) Path= $path")

        // No añadir Authorization solo para /auth/login y /auth/refresh-token
        // (Asegúrate de NO excluir /auth/logout aquí)
        if (path.equals("/auth/login", ignoreCase = true) ||
            path.equals("/auth/refresh-token", ignoreCase = true)
        ) {
            return chain.proceed(originalRequest)
        }

        // Para cualquier otra ruta, incluimos el Bearer token.
        Log.d(TAG, " -) Inyectando cabecera Authorization")
        val accessToken = try {
            TokenProvider.getToken()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "TokenProvider no está inicializado; continuando sin Authorization", e)
            ""
        }
        Log.d(TAG, " -) accessToken: $accessToken")

        if (accessToken.isBlank()) {
            Log.w(TAG, " -) Access token vacío; enviando request sin cabecera Authorization")
            return chain.proceed(originalRequest)
        }

        val newRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        return chain.proceed(newRequest)
    }
}
