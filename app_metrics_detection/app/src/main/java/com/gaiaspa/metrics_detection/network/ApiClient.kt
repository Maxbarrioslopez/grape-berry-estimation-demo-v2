/**
 * Singleton HTTP client that builds and exposes the [ApiService] instance ready
 * to communicate with the MetricsApp backend.
 *
 * Role in the architecture: network layer. Centralizes the configuration of
 * OkHttp (timeouts, interceptors, authenticator) and Retrofit (base URL,
 * JSON converter) so the rest of the app consumes a single access point.
 *
 * @property BASE_URL Backend base URL (production environment). Includes the
 *                   `/v1/` version prefix for API versioning.
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

    // Placeholder URL for the public academic demo. Backend is disabled in DEMO_MODE builds.
    // Replace with your private backend URL only when re-enabling server connectivity.
    val BASE_URL: HttpUrl =
        "https://your-backend-url.example.com/v1/".toHttpUrlOrNull()!!

    /**
     * Creates a fully configured [ApiService].
     *
     * - Initializes [TokenProvider] so interceptors can read tokens.
     * - Attaches [AuthInterceptor] to automatically inject the Authorization header.
     * - Attaches [TokenAuthenticator] to renew tokens on 401 responses.
     * - 300s timeouts to tolerate image uploads on slow networks.
     *
     * @param context used to initialize TokenProvider and the token authenticator.
     * @return [ApiService] instance ready to make calls to the backend.
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
