package com.gaiaspa.metrics_detection.data.model.response
import java.time.Instant
import com.gaiaspa.metrics_detection.data.model.Profile

data class ProfileResponse(
    val email: String,
    val name: String,
    val userId: String,
    val lastname: String,
    val role: String,
    val rut: String,
    val isVerified: Boolean,
    val isAvailable: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val photoUrl: String? // URL de la foto de perfil
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
        createdAt = Instant.parse(this.createdAt).toEpochMilli(),
        updatedAt = Instant.parse(this.updatedAt).toEpochMilli(),
        photoPath = this.photoUrl // Asumiendo que guardas la URL directamente
    )
}
