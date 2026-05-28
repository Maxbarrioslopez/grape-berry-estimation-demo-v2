package com.gaiaspa.metrics_detection.ui.home

data class VarietyOption(
    val id: Int,
    val name: String
) {
    override fun toString(): String = name // so the dropdown shows the name
}