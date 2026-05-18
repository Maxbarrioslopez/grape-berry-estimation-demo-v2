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

    // ── Auth ─────────────────────────────────────────────────────────────────

    /**
     * Autentica al usuario con email y contraseña.
     * @param loginRequest cuerpo con credenciales de inicio de sesión.
     * @return [Response] con [LoginResponse] que incluye accessToken, refreshToken, userId y roles.
     */
    @POST("auth/login")
    suspend fun login(@Body loginRequest: LoginRequest): Response<LoginResponse>

    /** 
     * Registro corporativo por invitación.
     * El backend valida el token de invitación embebido en CompanyRegisterRequest.
     */
    @POST("auth/company-registration")
    suspend fun registerCompany(@Body request: CompanyRegisterRequest): Response<Void>

    /**
     * Obtiene el perfil del usuario autenticado usando el access token actual.
     * @return [Response] con [ProfileResponse] conteniendo nombre, email, empresa y rol.
     */
    @GET("auth/profile")
    suspend fun getProfile(): Response<ProfileResponse>

    /**
     * Cierra la sesión en el backend invalidando el refresh token activo.
     * @param refreshTokenRequest contiene el refresh token a invalidar.
     */
    @POST("auth/logout")
    suspend fun logout(@Body refreshTokenRequest: RefreshTokenRequest): Response<LogoutResponse>

    /**
     * Renueva el par de tokens de forma síncrona (usado por OkHttp Authenticator,
     * que no soporta llamadas suspend).
     * @param refreshTokenRequest contiene el refresh token actual.
     * @return [Call] síncrono con [LoginResponse] portando el nuevo accessToken y refreshToken.
     * @see TokenAuthenticator
     */
    @POST("auth/refresh-token")
    fun refreshTokenSync(@Body refreshTokenRequest: RefreshTokenRequest): Call<LoginResponse>

    /**  
     * RECOVERY (Proceso Simplificado v2)
     * Antes: Flujo de 2 pasos con token por correo.
     * Ahora: Cambio de contraseña directo mediante validación de Email + RUT.
     * Motivo: Simplificación de UX y eliminación de dependencia de envío de correos.
     *
     * @param request contiene email, RUT y la nueva contraseña.
     */
    @POST("auth/password-recovery/change")
    suspend fun changePassword(@Body request: PasswordChangeRequest): Response<Void>

    // ── Lote APIs ────────────────────────────────────────────────────────────

    /**
     * Sube un nuevo lote de detección al backend con imágenes como partes multipart.
     * Los campos @Part textuales se envían como RequestBody con media type text/plain
     * o application/json según corresponda.
     *
     * @param userId identificador del usuario propietario del lote.
     * @param company nombre o ID de la empresa.
     * @param vessel identificador del navío/barco.
     * @param block bloque del viñedo.
     * @param variety variedad de uva.
     * @param predictedAt timestamp Unix de predicción.
     * @param calPredictsJson JSON serializado del array de calibres predichos.
     * @param files array de partes multipart con las imágenes JPEG asociadas.
     * @return [Response] con [LoteResponse] que incluye el cloudId asignado y las URLs
     *         de las imágenes subidas a S3.
     */
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
        @Part files: Array<MultipartBody.Part>
    ): Response<LoteResponse>

    /**
     * Elimina un lote del backend por su cloudId.
     * @param loteId identificador remoto del lote (cloudId).
     * @return [Response] con [DeleteBatchGrapeResponse] indicando si se eliminó correctamente.
     */
    @DELETE("batch-predictions-grape/{id}")
    suspend fun deleteBatchDetection(@Path("id") loteId: String): Response<DeleteBatchGrapeResponse>

    /**
     * Obtiene todos los lotes del usuario autenticado desde el backend.
     * @return [Response] con lista de [LoteResponse] ordenados por fecha de predicción descendente.
     */
    @GET("batch-predictions-grape")
    suspend fun getBatchsDetections(): Response<List<LoteResponse>>

}
