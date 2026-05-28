package com.gaiaspa.metrics_detection.ml

class ScalersOriginal {
    // Parameters for X
    // Minimums of original data
    private val dataMinX = doubleArrayOf(15.0, 5290.0)

    // Maximums of original data
    private val dataMaxX = doubleArrayOf(101.0, 16605.0)

    // Scaling factors (scale_)
    private val scaleX = doubleArrayOf(1.16279070e-02, 8.83782589e-05)

    // Minimum transformation applied (min_)
    private val minX = doubleArrayOf(-0.1744186, -0.46752099)

    // Parameters for Y
    private val dataMinY = doubleArrayOf(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
    )
    private val dataMaxY = doubleArrayOf(
        3.0,  4.0, 35.0, 61.0, 32.0, 37.0, 38.0, 26.0, 56.0,
        55.0, 41.0, 33.0, 23.0, 26.0, 17.0,  1.0,  0.0,  0.0
    )
    private val scaleY = doubleArrayOf(
        0.33333333, 0.25, 0.02857143, 0.01639344, 0.03125, 0.02702703,
        0.02631579, 0.03846154, 0.01785714, 0.01818182, 0.02439024, 0.03030303,
        0.04347826, 0.03846154, 0.05882353, 1.0, 1.0, 1.0
    )
    private val minY = doubleArrayOf(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
    )

    // ------------------------
    //    Forward Scaling
    // ------------------------

    /**
     * Scales X data using the transformation:
     * scaledValue = data[i] * scaleX[i] + minX[i]
     * Calculations are performed in double precision.
     */
    fun scaleX(data: FloatArray): FloatArray {
        checkXDimensions(data.size)
        val scaled = DoubleArray(data.size)
        for (i in data.indices) {
            val value = data[i].toDouble()
            scaled[i] = value * scaleX[i] + minX[i]
        }
        return scaled.map { it.toFloat() }.toFloatArray()
    }

    /**
     * Scales Y data using the transformation:
     * scaledValue = data[i] * scaleY[i] + minY[i]
     * Calculations are performed in double precision.
     */
    fun scaleY(data: FloatArray): FloatArray {
        checkYDimensions(data.size)
        val scaled = DoubleArray(data.size)
        for (i in data.indices) {
            val value = data[i].toDouble()
            scaled[i] = value * scaleY[i] + minY[i]
        }
        return scaled.map { it.toFloat() }.toFloatArray()
    }

    // ------------------------
    //    Inverse Scaling
    // ------------------------

    /**
     * Applies the inverse transformation on X:
     * originalValue = (scaledValue - minX[i]) / scaleX[i]
     * Calculations are performed in double precision.
     */
    fun inverseScaleX(data: FloatArray): FloatArray {
        checkXDimensions(data.size)
        val original = DoubleArray(data.size)
        for (i in data.indices) {
            val scaledVal = data[i].toDouble()
            original[i] = (scaledVal - minX[i]) / scaleX[i]
        }
        return original.map { it.toFloat() }.toFloatArray()
    }

    /**
     * Applies the inverse transformation on Y:
     * originalValue = (scaledValue - minY[i]) / scaleY[i]
     * Calculations are performed in double precision.
     */
    fun inverseScaleY(data: FloatArray): FloatArray {
        checkYDimensions(data.size)
        val original = DoubleArray(data.size)
        for (i in data.indices) {
            val scaledVal = data[i].toDouble()
            original[i] = (scaledVal - minY[i]) / scaleY[i]
        }
        return original.map { it.toFloat() }.toFloatArray()
    }

    // ------------------------
    //    Helpers
    // ------------------------
    private fun checkXDimensions(size: Int) {
        if (size != dataMinX.size || size != dataMaxX.size || size != scaleX.size || size != minX.size) {
            throw IllegalArgumentException("Dimensions of X parameters do not match.")
        }
    }

    private fun checkYDimensions(size: Int) {
        if (size != dataMinY.size || size != dataMaxY.size || size != scaleY.size || size != minY.size) {
            throw IllegalArgumentException("Dimensions of Y parameters do not match.")
        }
    }
}
