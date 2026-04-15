/**
 * ApiService.kt
 *
 * Propósito: Definir los contratos de comunicación con el Backend.
 * Responsabilidad: Declarar los endpoints de autenticación y gestión de lotes (Batch).
 */
package com.gaiaspa.metrics_detection.network

import com.gaiaspa.metrics_detection.data.model.response.LoginResponse
import com.gaiaspa.metrics_detection.data.model.response.ProfileResponse
import com.gaiaspa.metrics_detection.data.model.request.LoginRequest
import com.gaiaspa.metrics_detection.data.model.response.LogoutResponse
import com.gaiaspa.metrics_detection.data.model.request.RefreshTokenRequest
import com.gaiaspa.metrics_detection.data.model.response.DeleteBatchGrapeResponse
import com.gaiaspa.metrics_detection.data.model.response.LoteResponse
import com.gaiaspa.metrics_detection.data.model.request.CompanyRegisterRequest
import com.gaiaspa.metrics_detection.data.model.request.RecoveryRequest
import com.gaiaspa.metrics_detection.data.model.request.ResetRequest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    /**  AUTH  */
    @POST("auth/login")
    suspend fun login(@Body loginRequest: LoginRequest): Response<LoginResponse>

    /** 
     * Registro corporativo por invitación.
     * Antes: /auth/register con JSONObject manual.
     * Ahora: /auth/company-registration con DTO CompanyRegisterRequest.
     */
    @POST("auth/company-registration")
    suspend fun registerCompany(@Body request: CompanyRegisterRequest): Response<Void>

    @GET("auth/profile")
    suspend fun getProfile(): Response<ProfileResponse>

    @POST("auth/logout")
    suspend fun logout(@Body refreshTokenRequest: RefreshTokenRequest): Response<LogoutResponse>

    @POST("auth/refresh-token")
    fun refreshTokenSync(@Body refreshTokenRequest: RefreshTokenRequest): Call<LoginResponse>

    /**  RECOVERY  */
    @POST("auth/password-recovery/request")
    suspend fun requestRecovery(@Body request: RecoveryRequest): Response<Void>

    @POST("auth/password-recovery/reset")
    suspend fun resetPassword(@Body request: ResetRequest): Response<Void>

    /**  Lote APIs  */
    @Multipart
    @POST("batch-predictions-grape")
    suspend fun insertBatchDetection(
        @Part("userId") userId: RequestBody,
        @Part("company") company: RequestBody,
        @Part("vessel") vessel: RequestBody,
        @Part("block") block: RequestBody,
        @Part("variety") variety: RequestBody,
        @Part("predictedAt") predictedAt: RequestBody,
        @Part("calPredicts") calPredictsJson: RequestBody,
        @Part files: List<MultipartBody.Part>
        ): Response<LoteResponse>

    @DELETE("batch-predictions-grape/{id}")
    suspend fun deleteBatchDetection(@Path("id") loteId: String): Response<DeleteBatchGrapeResponse>

    @GET("batch-predictions-grape")
    suspend fun getBatchsDetections(): Response<List<LoteResponse>>

}
