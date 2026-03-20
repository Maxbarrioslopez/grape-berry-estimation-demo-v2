package com.gaiaspa.metrics_detection.model


data class AuthState(
    val isLoggedIn: Boolean,
    val jwtToken: String?,
    val refreshJwtToken: String?,
    val email: String? = null,
    val id: String? = null ,
    val name: String? = null,
    val lastname: String? = null,
    val role: String? = null,
    val rut: String? = null,
    val isAvailable: Boolean = false,
    val photoPath:  String? = null,
)