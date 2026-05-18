/**
 * Cliente HTTP singleton que construye y expone la instancia de [ApiService] lista
 * para comunicarse con el backend de MetricsApp.
 *
 * Rol en la arquitectura: capa de red (network). Centraliza la configuración de
 * OkHttp (timeouts, interceptores, autenticador) y de Retrofit (URL base,
 * convertidor JSON) para que el resto de la app consuma un punto único de acceso.
 *
 * @property BASE_URL URL base del backend (entorno de producción). Incluye el prefijo
 *                   de versión `/v1/` para versionado de API.
 */
package com.gaiaspa.metrics_detection.network

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    val BASE_URL: HttpUrl =
        "https://metricsapp.online/v1/".toHttpUrlOrNull()!!

    /**
     * Crea un [ApiService] completamente configurado.
     *
     * - Inicializa [TokenProvider] para que los interceptores puedan leer tokens.
     * - Adjunta [AuthInterceptor] para inyectar el header Authorization automáticamente.
     * - Adjunta [TokenAuthenticator] para renovar tokens ante respuestas 401.
     * - Timeouts de 300 s para tolerar subidas de imágenes en redes lentas.
     *
     * @param context usado para inicializar TokenProvider y el autenticador de tokens.
     * @return instancia de [ApiService] lista para realizar llamadas al backend.
     */
    fun create(context: Context): ApiService {
        TokenProvider.init(context)

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor())
            .authenticator(TokenAuthenticator(context))
            .connectTimeout(300, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
