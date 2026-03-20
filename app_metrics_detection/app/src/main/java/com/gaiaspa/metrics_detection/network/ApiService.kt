// ApiService.kt
package com.gaiaspa.metrics_detection.network

import com.gaiaspa.metrics_detection.data.model.response.LoginResponse
import com.gaiaspa.metrics_detection.data.model.response.ProfileResponse
import com.gaiaspa.metrics_detection.data.model.request.LoginRequest
import com.gaiaspa.metrics_detection.data.model.response.LogoutResponse
import com.gaiaspa.metrics_detection.data.model.request.RefreshTokenRequest
import com.gaiaspa.metrics_detection.data.model.response.DeleteBatchGrapeResponse
import com.gaiaspa.metrics_detection.data.model.response.LoteResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

     /**  AUTH  */
    @POST("auth/login")
    suspend fun login(@Body loginRequest: LoginRequest): Response<LoginResponse>

    @GET("auth/profile")
    suspend fun getProfile(): Response<ProfileResponse>

    @POST("auth/logout")
    suspend fun logout(@Body refreshTokenRequest: RefreshTokenRequest): Response<LogoutResponse>

    @POST("auth/refresh-token")
    fun refreshTokenSync(@Body refreshTokenRequest: RefreshTokenRequest): Call<LoginResponse>

    /**  Lote APIs  */
    // Nueva función para insertar un Lote con imágenes como archivos
    @Multipart
    @POST("batch-predictions-grape")
    suspend fun insertBatchDetection(
        @Part("userId") userId: RequestBody,
        @Part("company") company: RequestBody,
        @Part("vessel") vessel: RequestBody,
        @Part("block") block: RequestBody,
        @Part("variety") variety: RequestBody,
        @Part("predictedAt") predictedAt: RequestBody,
        @Part("calPredicts") calPredictsJson: RequestBody, // JSON serializado
        @Part files: List<MultipartBody.Part>
        ): Response<LoteResponse>

    @DELETE("batch-predictions-grape/{id}")
    suspend fun deleteBatchDetection(@Path("id") loteId: String): Response<DeleteBatchGrapeResponse>

    @GET("batch-predictions-grape")
    suspend fun getBatchsDetections(): Response<List<LoteResponse>>

}
