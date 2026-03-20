package com.gaiaspa.metrics_detection.model

data class CalPredict(
    val bunchColor : String,
    val qty : Int,
    val std: Float,
    val mean: Float,
    val mode: Float,
    val pred : List<Int>,
    val bins: List<Float>
    
)