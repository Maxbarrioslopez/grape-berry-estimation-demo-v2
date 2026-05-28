/**
 * ApiService.kt
 *
 * Purpose: Define the communication contracts with the Backend.
 * Responsibility: Declare the authentication and batch management endpoints.
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
     * Authenticates the user with email and password.
     * @param loginRequest body with login credentials.
     * @return [Response] with [LoginResponse] including accessToken, refreshToken, userId, and roles.
     */
    @POST("auth/login")
    suspend fun login(@Body loginRequest: LoginRequest): Response<LoginResponse>

    /**
     * Corporate registration by invitation.
     * The backend validates the invitation token embedded in CompanyRegisterRequest.
     */
    @POST("auth/company-registration")
    suspend fun registerCompany(@Body request: CompanyRegisterRequest): Response<Void>

    /**
     * Retrieves the authenticated user's profile using the current access token.
     * @return [Response] with [ProfileResponse] containing name, email, company, and role.
     */
    @GET("auth/profile")
    suspend fun getProfile(): Response<ProfileResponse>

    /**
     * Closes the session on the backend by invalidating the active refresh token.
     * @param refreshTokenRequest contains the refresh token to invalidate.
     */
    @POST("auth/logout")
    suspend fun logout(@Body refreshTokenRequest: RefreshTokenRequest): Response<LogoutResponse>

    /**
     * Renews the token pair synchronously (used by OkHttp Authenticator,
     * which does not support suspend calls).
     * @param refreshTokenRequest contains the current refresh token.
     * @return Synchronous [Call] with [LoginResponse] carrying the new accessToken and refreshToken.
     * @see TokenAuthenticator
     */
    @POST("auth/refresh-token")
    fun refreshTokenSync(@Body refreshTokenRequest: RefreshTokenRequest): Call<LoginResponse>

    /**
     * RECOVERY (Simplified v2 Process)
     * Before: 2-step flow with emailed token.
     * Now: Direct password change via Email + RUT validation.
     * Reason: UX simplification and removal of email sending dependency.
     *
     * @param request contains email, RUT, and the new password.
     */
    @POST("auth/password-recovery/change")
    suspend fun changePassword(@Body request: PasswordChangeRequest): Response<Void>

    // ── Lote APIs ────────────────────────────────────────────────────────────

    /**
     * Uploads a new detection lot to the backend with images as multipart parts.
     * Textual @Part fields are sent as RequestBody with media type text/plain
     * or application/json as appropriate.
     *
     * @param userId identifier of the user owning the lot.
     * @param company company name or ID.
     * @param vessel vessel/ship identifier.
     * @param block vineyard block.
     * @param variety grape variety.
     * @param predictedAt Unix prediction timestamp.
     * @param calPredictsJson JSON-serialized array of predicted calibers.
     * @param files array of multipart parts with the associated JPEG images.
     * @return [Response] with [LoteResponse] including the assigned cloudId and
     *         the URLs of the images uploaded to S3.
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
     * Deletes a lot from the backend by its cloudId.
     * @param loteId remote lot identifier (cloudId).
     * @return [Response] with [DeleteBatchGrapeResponse] indicating whether deletion was successful.
     */
    @DELETE("batch-predictions-grape/{id}")
    suspend fun deleteBatchDetection(@Path("id") loteId: String): Response<DeleteBatchGrapeResponse>

    /**
     * Retrieves all lots for the authenticated user from the backend.
     * @return [Response] with a list of [LoteResponse] sorted by descending prediction date.
     */
    @GET("batch-predictions-grape")
    suspend fun getBatchsDetections(): Response<List<LoteResponse>>

}
