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
import com.gaiaspa.metrics_detection.data.model.request.PasswordChangeRequest
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
     */
    @POST("auth/company-registration")
    suspend fun registerCompany(@Body request: CompanyRegisterRequest): Response<Void>

    @GET("auth/profile")
    suspend fun getProfile(): Response<ProfileResponse>

    @POST("auth/logout")
    suspend fun logout(@Body refreshTokenRequest: RefreshTokenRequest): Response<LogoutResponse>

    @POST("auth/refresh-token")
    fun refreshTokenSync(@Body refreshTokenRequest: RefreshTokenRequest): Call<LoginResponse>

    /**  
     * RECOVERY (Proceso Simplificado v2)
     * Antes: Flujo de 2 pasos con token por correo.
     * Ahora: Cambio de contraseña directo mediante validación de Email + RUT.
     * Motivo: Simplificación de UX y eliminación de dependencia de envío de correos.
     */
    @POST("auth/password-recovery/change")
    suspend fun changePassword(@Body request: PasswordChangeRequest): Response<Void>

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
