package com.gaiaspa.metrics_detection.data.model.request

/**
 * DTOs para el flujo de autenticación y recuperación de cuenta.
 * Alineados estrictamente con el contrato del backend multi-tenant.
 */

data class CompanyRegisterRequest(
    val email: String,
    val name: String,
    val lastname: String,
    val password: String,
    val rut: String,
    val inviteCode: String
)

data class RecoveryRequest(
    val email: String,
    val rut: String
)

data class ResetRequest(
    val token: String,
    val newPassword: String
)
