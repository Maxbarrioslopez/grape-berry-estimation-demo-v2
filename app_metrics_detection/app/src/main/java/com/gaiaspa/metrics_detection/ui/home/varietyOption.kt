package com.gaiaspa.metrics_detection.ui.home

data class VarietyOption(
    val id: Int,
    val name: String
) {
    override fun toString(): String = name // para que el dropdown muestre el nombre
}