// src/main/java/com/gaiaspa/metrics_detection/network/TokenAuthenticator.kt
package com.gaiaspa.metrics_detection.network

import android.content.Context
import android.content.Intent
import android.util.Log
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

        // Creamos un OkHttpClient "limpio" (sin interceptor ni authenticator)
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
            Log.e(TAG, "Authenticator: no se pudo inicializar TokenProvider", e)
            return null
        }

        // 1) Si ya hemos intentado antes → abortamos para no buclear
        if (responseCount(response) >= 2) {
            Log.e(TAG, "Authenticator: demasiados reintentos")
            return null
        }

        synchronized(this) {
            val currentToken = TokenProvider.getToken()
            val originalRequest = response.request
            val originalPath = originalRequest.url.encodedPath
            val originalBody = originalRequest.body

            // 2) Si el header que falló era justamente el token guardado...
            if (originalRequest.header("Authorization") == "Bearer $currentToken") {
                // 3) Intentamos refrescar
                if (refreshToken()) {
                    val newAccess = TokenProvider.getToken()       // ya guardó el nuevo
                    val newRefresh = TokenProvider.getRefreshToken()

                    // 4) Reconstruimos la petición original con el access token renovado
                    val newReqBuilder = originalRequest.newBuilder()
                        .header("Authorization", "Bearer $newAccess")

                    // 5) Si era /auth/logout, sustituimos también el refreshToken en el body
                    if (originalPath.contains("/auth/logout") && originalBody != null) {
                        val updatedBody = replaceRefreshTokenInBody(originalBody, newRefresh)
                        newReqBuilder.method(originalRequest.method, updatedBody)
                    } else {
                        newReqBuilder.method(originalRequest.method, originalBody)
                    }

                    return newReqBuilder.build()
                } else {
                    // 6) Refresh falló → forzamos logout
                    Log.e(TAG, "Authenticator: refresh fallido, cerrando sesión")
                    forceLogout()
                    return null
                }
            } else {
                // 7) Si el token ya fue actualizado por otro hilo, reintentamos con el último token
                return originalRequest.newBuilder()
                    .header("Authorization", "Bearer ${TokenProvider.getToken()}")
                    .build()
            }
        }
    }

    /** Cuenta cuántas veces hemos recibido 401 en esta cadena de respuestas */
    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result++; prior = prior.priorResponse
        }
        return result
    }

    /**
     * Hace la llamada síncrona a /auth/refresh-token.
     * Devuelve true si logró renovar y guardó los tokens.
     */
    private fun refreshToken(): Boolean {
        val oldRefresh = TokenProvider.getRefreshToken()
        if (oldRefresh.isBlank()) {
            Log.e(TAG, "No hay refresh token para renovar")
            return false
        }

        val call = apiService.refreshTokenSync(RefreshTokenRequest(oldRefresh))
        return try {
            val resp = call.execute()
            if (resp.isSuccessful) {
                val body = resp.body()
                if (body != null) {
                    // Guardamos siempre el nuevo par
                    TokenProvider.saveToken(body.accessToken)
                    TokenProvider.saveRefreshToken(body.refreshToken)
                    Log.d(TAG, "Tokens renovados OK")
                    true
                } else {
                    Log.e(TAG, "Refresh exitoso pero body nulo")
                    false
                }
            } else {
                Log.e(TAG, "Refresh falló: ${resp.code()} / ${resp.errorBody()?.string()}")
                false
            }
        } catch (e: IOException) {
            Log.e(TAG, "Excepción al refrescar token", e)
            false
        }
    }

    /** Reemplaza en el JSON del body el campo refreshToken por el nuevo */
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
            Log.e(TAG, "No se pudo reemplazar refreshToken en body", e)
            originalBody
        }
    }

    /** Limpia credenciales y arranca LoginActivity limpiando la pila */
    private fun forceLogout() {
        TokenProvider.logout()
        val intent = Intent(appContext, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        appContext.startActivity(intent)
    }
}
