// ApiClient.kt
package com.gaiaspa.metrics_detection.network

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    val BASE_URL: HttpUrl = "http://metricsapp.online:3000/v1/".toHttpUrlOrNull()!!

    fun create(context: Context): ApiService {
        TokenProvider.init(context)

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor())
            .authenticator(TokenAuthenticator(context))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

