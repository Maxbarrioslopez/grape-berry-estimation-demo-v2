package com.gaiaspa.metrics_detection.data.repository

import android.content.Context
import android.util.Log
import com.gaiaspa.metrics_detection.BuildConfig
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
        if (BuildConfig.DEMO_MODE) {
            return withContext(Dispatchers.IO) {
                profileDao.getProfile()
            }
        }
        return if (NetworkUtils.isNetworkAvailable(context)) {
            try {
                val profileResponse = apiService.getProfile()
                val profileResponseBody = profileResponse.body()
                if (profileResponse.isSuccessful){
                    val profile = profileResponseBody?.toProfile()
                    withContext(Dispatchers.IO) {
                        if (profile != null) {
                            profileDao.insertProfile(profile)
                        } // Database operation on background thread
                        if (profile != null) {
                            TokenProvider.saveUserId(profile.userId)
                        } // Save the userId
                    }
                    profile
                } else {
                    // Handle exceptions as needed
                    withContext(Dispatchers.IO) {
                        profileDao.getProfile() // Read operation on background thread
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()

                // Handle exceptions as needed
                withContext(Dispatchers.IO) {
                    profileDao.getProfile() // Read operation on background thread
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                profileDao.getProfile() // Read operation on background thread
            }
        }
    }


    fun logout() {
        if (BuildConfig.DEMO_MODE) {
            profileDao.deleteProfile()
            TokenProvider.logout()
            return
        }
        if (NetworkUtils.isNetworkAvailable(context)){

        CoroutineScope(Dispatchers.IO).launch {
                val response = apiService.logout(RefreshTokenRequest(TokenProvider.getRefreshToken()))
                if (response.isSuccessful) {
                    // Success: server invalidated the refreshToken
                    profileDao.deleteProfile()
                    TokenProvider.logout() // Clear tokens locally
                } else {
                    // Handle server logout error
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, "Logout failed: ${response.code()} -> ${response.errorBody()?.string()}")
                    } else {
                        Log.e(TAG, "Logout failed: code ${response.code()}")
                    }
            }
        }
        }else{
            profileDao.deleteProfile()
            TokenProvider.logout() // Clear tokens locally
        }

    }


}
