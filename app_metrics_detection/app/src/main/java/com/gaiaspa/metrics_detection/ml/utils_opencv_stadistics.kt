package com.gaiaspa.metrics_detection.ml

import kotlin.math.sqrt


/**
 * Returns the mean (average) of a List<Double>.
 */
fun List<Double>.mean(): Double {
    if (this.isEmpty()) return Double.NaN
    return this.sum() / this.size
}

/**
 * Returns the standard deviation (sample or population) of a List<Double>.
 * Here we use population (dividing by n). If you prefer sample, use (n - 1).
 */
fun List<Double>.std(): Double {
    if (this.size < 2) return 0.0
    val mean = this.mean()
    val variance = this.map { (it - mean) * (it - mean) }.sum() / this.size
    return sqrt(variance)
}

/**
 * Returns the percentile [p] (0..100) of this List<Double>.
 * For simplicity, we do a linear interpolation approach.
 */
fun List<Double>.percentile(p: Double): Double {
    if (this.isEmpty()) return Double.NaN
    if (p <= 0.0) return this.minOrNull() ?: Double.NaN
    if (p >= 100.0) return this.maxOrNull() ?: Double.NaN

    val sorted = this.sorted()
    val position = (p / 100.0) * (sorted.size - 1)
    val lowerIndex = position.toInt()
    val upperIndex = kotlin.math.ceil(position).toInt()
    if (lowerIndex == upperIndex) {
        return sorted[lowerIndex]
    }
    val fraction = position - lowerIndex
    return sorted[lowerIndex] + fraction * (sorted[upperIndex] - sorted[lowerIndex])
}
