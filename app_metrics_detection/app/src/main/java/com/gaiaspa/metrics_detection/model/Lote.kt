package com.gaiaspa.metrics_detection.model

data class Lote(
    val loteID: String,
    val userId: String,           // Unique identifier (UUID or auto-generated)
    val company: String,
    val vessel: String,
    val block: String,
    val images: List<String>, // Local image URIs or paths
    val calPredicts: List<CalPredict> = emptyList(), // CalPredict results
    val calKeys: List<Double> = emptyList(), // CalPredict results
    val isSynced: Boolean = false,  // indicates whether the lot is synced with the cloud
    val predictedAt: Long,
    val updatedAt: Long,
)