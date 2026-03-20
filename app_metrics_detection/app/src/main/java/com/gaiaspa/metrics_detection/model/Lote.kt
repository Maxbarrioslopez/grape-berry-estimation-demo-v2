package com.gaiaspa.metrics_detection.model

data class Lote(
    val loteID: String,
    val userId: String,           // Identificador único (UUID o auto generado)
    val company: String,
    val vessel: String,
    val block: String,
    val images: List<String>, // URIs locales de las imágenes o paths
    val calPredicts: List<CalPredict> = emptyList(), // resultados de CalPredict
    val calKeys: List<Double> = emptyList(), // resultados de CalPredict
    val isSynced: Boolean = false,  // indica si el lote está sincronizado con la nube
    val predictedAt: Long,
    val updatedAt: Long,
)