package com.gaiaspa.metrics_detection.data.model.response
import java.time.Instant
import com.gaiaspa.metrics_detection.data.model.Profile

/**
 * ProfileResponse.kt
 * 
 * Before: Did not include companyId.
 * Now: Includes companyId for multi-tenant alignment.
 * Reason: The backend now explicitly links the user with a company.
 */
data class ProfileResponse(
    val email: String,
    val name: String,
    val userId: String,
    val lastname: String,
    val role: String,
    val rut: String,
    val companyId: String?, // Linked company ID
    val isVerified: Boolean,
    val isAvailable: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val photoUrl: String?
)

fun ProfileResponse.toProfile(): Profile {
    return Profile(
        email = this.email,
        name = this.name,
        userId = this.userId,
        lastname = this.lastname,
        role = this.role,
        rut = this.rut,
        isVerified = this.isVerified,
        isAvailable = this.isAvailable,
        createdAt = try { Instant.parse(this.createdAt).toEpochMilli() } catch(e: Exception) { 0L },
        updatedAt = try { Instant.parse(this.updatedAt).toEpochMilli() } catch(e: Exception) { 0L },
        photoPath = this.photoUrl
    )
}
