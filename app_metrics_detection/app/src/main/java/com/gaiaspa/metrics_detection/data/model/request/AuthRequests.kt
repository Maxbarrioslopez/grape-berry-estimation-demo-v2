package com.gaiaspa.metrics_detection.data.model.request

import com.google.gson.annotations.SerializedName

/**
 * DTOs for Auth flow aligned with multi-tenant backend v1.
 */

data class CompanyRegisterRequest(
    val email: String,
    val name: String,
    val lastname: String,
    val password: String,
    val rut: String,
    val inviteCode: String
)

data class PasswordChangeRequest(
    val email: String,
    val rut: String,
    @SerializedName("newContraseña")
    val newPassword: String
)

data class RecoveryRequest(
    val email: String,
    val rut: String
)

data class ResetRequest(
    val token: String,
    val newPassword: String
)
