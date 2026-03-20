package com.gaiaspa.metrics_detection.data.model.response


data class DeleteBatchGrapeResponse (
    val message: String,
    val success: Boolean,
    val error: String?
)
