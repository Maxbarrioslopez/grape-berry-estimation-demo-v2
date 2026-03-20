package com.gaiaspa.metrics_detection.ml

/**
 * Computes the average depth from a flattened [depthMap] (1D FloatArray of size width*height)
 * at the points in [ellipsePoints]. The map is assumed to be 256x256 by default,
 * but you can parameterize [width] and [height] if needed.
 *
 * @param depthMap Flattened depth array, length = [width]*[height].
 * @param ellipsePoints The major/minor axis endpoints (or other points) to sample.
 * @param width The width of the depth map. Default = 256.
 * @param height The height of the depth map. Default = 256.
 * @return The mean depth at those points, or 0f if none are valid.
 */
fun getDepthMeanEllipse(
    depthMap: FloatArray,
    ellipsePoints: EllipseAxisPoints,
    width: Int = 256,
    height: Int = 256
): Float {
    val values = mutableListOf<Float>()

    // Sample the minor axis endpoints
    for (pt in ellipsePoints.minorAxis) {
        val x = pt.x.toInt()
        val y = pt.y.toInt()
        if (x in 0 until width && y in 0 until height) {
            val index = y * width + x
            values.add(depthMap[index])
        }
    }

    // Sample the major axis endpoints
    for (pt in ellipsePoints.majorAxis) {
        val x = pt.x.toInt()
        val y = pt.y.toInt()
        if (x in 0 until width && y in 0 until height) {
            val index = y * width + x
            values.add(depthMap[index])
        }
    }

    if (values.isEmpty()) return 0f
    val mean = values.average().toFloat()
    return mean / 255f // Normalize between 0 and 1
}

fun getAdaptedMmPx(mmPxRef: Float, depthMean: Float, zReference: Float): Float {
    // If depthMean is 0, guard from division by zero
    if (depthMean <= 1e-6f) return mmPxRef
    return mmPxRef * (zReference / depthMean)
}