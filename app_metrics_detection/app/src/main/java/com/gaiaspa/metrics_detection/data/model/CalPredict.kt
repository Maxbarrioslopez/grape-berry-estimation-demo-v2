package com.gaiaspa.metrics_detection.data.model

data class CalPredict(
    val error: String = "",
    val status: Boolean = false,
    val bunchColor: String = "",
    val qty: Int = 0,
    val std: Float = 0f,
    val mean: Float = 0f,
    val mode: Float = 0f,
    val pred: List<Int> = emptyList(),
    val bins: List<Float> = emptyList()
)
