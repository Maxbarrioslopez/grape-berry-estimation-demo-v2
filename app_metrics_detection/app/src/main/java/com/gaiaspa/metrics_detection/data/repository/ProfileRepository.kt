package com.gaiaspa.metrics_detection.data.repository

import android.content.Context
import android.util.Log
import com.gaiaspa.metrics_detection.data.local.DatabaseProvider
import com.gaiaspa.metrics_detection.data.local.ProfileDao
import com.gaiaspa.metrics_detection.data.model.Profile
import com.gaiaspa.metrics_detection.data.model.request.RefreshTokenRequest
import com.gaiaspa.metrics_detection.data.model.response.toProfile
import com.gaiaspa.metrics_detection.network.ApiClient
import com.gaiaspa.metrics_detection.network.ApiService
import com.gaiaspa.metrics_detection.network.TokenProvider
import com.gaiaspa.metrics_detection.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileRepository private constructor(
    private val apiService: ApiService,
    private val profileDao: ProfileDao,
    private val context: Context
) {

    companion object {
        @Volatile
        private var INSTANCE: ProfileRepository? = null

        fun getInstance(context: Context): ProfileRepository {
            return INSTANCE ?: synchronized(this) {
                val database = DatabaseProvider.getDatabase(context)

                val apiService = ApiClient.create(context)
                val instance = ProfileRepository(
                    apiService = apiService,
                    profileDao = database.profileDao(),
                    context = context.applicationContext
                )
                INSTANCE = instance
                instance
            }
        }
        private const val TAG = "ProfileRepository"

    }

    suspend fun getProfile(): Profile? {
        return if (NetworkUtils.isNetworkAvailable(context)) {
            try {
                val profileResponse = apiService.getProfile()
                val profileResponseBody = profileResponse.body()
                if (profileResponse.isSuccessful){
                    val profile = profileResponseBody?.toProfile()
                    withContext(Dispatchers.IO) {
                        if (profile != null) {
                            profileDao.insertProfile(profile)
                        } // Operación de base de datos en hilo de fondo
                        if (profile != null) {
                            TokenProvider.saveUserId(profile.userId)
                        } // Guarda el userId
                    }
                    profile
                } else {
                    // Manejar excepciones según sea necesario
                    withContext(Dispatchers.IO) {
                        profileDao.getProfile() // Operación de lectura en hilo de fondo
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()

                // Manejar excepciones según sea necesario
                withContext(Dispatchers.IO) {
                    profileDao.getProfile() // Operación de lectura en hilo de fondo
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                profileDao.getProfile() // Operación de lectura en hilo de fondo
            }
        }
    }


    fun logout() {
        if (NetworkUtils.isNetworkAvailable(context)){

        CoroutineScope(Dispatchers.IO).launch {
                val response = apiService.logout(RefreshTokenRequest(TokenProvider.getRefreshToken()))
                if (response.isSuccessful) {
                    // Éxito: el servidor invalidó el refreshToken
                    profileDao.deleteProfile()
                    TokenProvider.logout() // Limpiamos tokens localmente
                } else {
                    // Manejar error de logout en servidor
                    Log.e(TAG, "Falló logout: ${response.code()} -> ${response.errorBody()?.string()}")
            }
        }
        }else{
            profileDao.deleteProfile()
            TokenProvider.logout() // Limpiamos tokens localmente
        }

    }


}
