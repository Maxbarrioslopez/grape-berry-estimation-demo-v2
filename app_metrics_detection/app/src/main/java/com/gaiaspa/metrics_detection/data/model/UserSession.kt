package com.gaiaspa.metrics_detection.data.model

data class UserSession(
    val jwt: String? = null,
    val refreshJwt: String? = null,
    val isLoggedIn: Boolean = false
)