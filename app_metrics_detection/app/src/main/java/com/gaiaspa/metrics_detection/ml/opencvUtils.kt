package com.gaiaspa.metrics_detection.ml
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.DataType

import android.graphics.PointF
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
// Función para realizar la interpolación bicúbica en un punto (x, y)
fun bicubicInterpolation(x: Double, y: Double, image: Array<FloatArray>): Float {
    val a = -0.5
    var result = 0.0
    for (i in -1..2) {
        for (j in -1..2) {
            val xi = x + i
            val yj = y + j
            result += image[(xi.toInt()).coerceIn(0, image.size - 1)][(yj.toInt()).coerceIn(0, image[0].size - 1)] *
                    cubicWeight(x - xi) * cubicWeight(y - yj)
        }
    }
    return result.toFloat()
}

// Función para calcular el peso cúbico
fun cubicWeight(t: Double): Double {
    return if (t < 0) {
        ((1 + 2 * t) * (1 + t) * (1 + t)) / 6
    } else {
        ((1 + t) * (1 + t) * (1 + 2 * t)) / 6
    }
}

// Función principal para redimensionar el depthMap con interpolación bicúbica
fun resizeDepthMapWithBicubicInterpolation(depthMap: TensorBuffer, targetWidth: Int, targetHeight: Int): TensorBuffer {

    // Convertir el tensor de profundidad a una matriz 2D
    val depthArray = depthMap.floatArray
    val originalHeight = depthMap.shape[0]
    val originalWidth = depthMap.shape[1]

    // Convertir el tensor a una matriz bidimensional de flotantes
    val image = Array(originalHeight) { FloatArray(originalWidth) }
    for (y in 0 until originalHeight) {
        for (x in 0 until originalWidth) {
            image[y][x] = depthArray[y * originalWidth + x]
        }
    }

    // Crear una matriz para almacenar la imagen redimensionada
    val resizedImage = Array(targetHeight) { FloatArray(targetWidth) }

    // Mapear coordenadas de la nueva imagen a la original usando interpolación bicúbica
    for (y in 0 until targetHeight) {
        for (x in 0 until targetWidth) {
            val srcX = x.toDouble() * (originalWidth - 1) / (targetWidth - 1)
            val srcY = y.toDouble() * (originalHeight - 1) / (targetHeight - 1)

            resizedImage[y][x] = bicubicInterpolation(srcX, srcY, image)
        }
    }

    // Convertir la matriz redimensionada de vuelta a un tensor
    val resizedTensor = TensorBuffer.createFixedSize(intArrayOf(targetHeight, targetWidth), DataType.FLOAT32)

    // Cargar la matriz redimensionada en el TensorBuffer
    val resizedArray = FloatArray(targetHeight * targetWidth)
    for (y in 0 until targetHeight) {
        for (x in 0 until targetWidth) {
            resizedArray[y * targetWidth + x] = resizedImage[y][x]
        }
    }

    resizedTensor.loadArray(resizedArray)
    return resizedTensor
}


/**
 * Given a binary mask where mask[y][x] != 0 indicates foreground,
 * fits an ellipse to the shape using a PCA-based approach and
 * returns the minor-axis and major-axis length (diameter) in pixels.
 *
 * If there aren't enough points to form an ellipse, returns 0f.
 */
data class EllipseAxes(
    val majorAxis: Float,
    val minorAxis: Float
)
fun findEllipseAxesPx(mask: Array<IntArray>): EllipseAxes {
    val points = mutableListOf<PointF>()

    // 1) Collect all foreground pixels
    for (y in mask.indices) {
        for (x in mask[y].indices) {
            if (mask[y][x] != 0) {  // or > threshold
                points.add(PointF(x.toFloat(), y.toFloat()))
            }
        }
    }

    // Need at least a handful of points to estimate an ellipse
    if (points.size < 5) {
        return EllipseAxes(0f, 0f)
    }

    // 2) Compute centroid (mean X, mean Y)
    val (cx, cy) = computeMean(points)

    // 3) Build the 2x2 covariance matrix
    val (cov11, cov12, cov22) = computeCovMatrix(points, cx, cy)

    // 4) Find the eigenvalues (λ1, λ2) of [ [cov11, cov12], [cov12, cov22] ]
    //    The principal axes = sqrt(eigenvalue).
    //    The ellipse axis = 2 * sqrt(eigenvalue).
    val (lambda1, lambda2) = eigenValues2x2(cov11, cov12, cov22)

    // Largest eigenvalue => major axis
    val majorEigen = max(lambda1, lambda2)
    val minorEigen = min(lambda1, lambda2)

    if (majorEigen <= 0f) {
        return EllipseAxes(0f, 0f)
    }

    // 5) Compute the axes as diameters in pixels
    val majorAxis = 2f * sqrt(majorEigen)
    val minorAxis = 2f * sqrt(minorEigen.coerceAtLeast(0f))
    // coerceAtLeast(0f) just to avoid negative sqrt if there's numerical noise

    return EllipseAxes(majorAxis, minorAxis)
}

/**
 * Compute the mean (centroid) of a list of points.
 */
private fun computeMean(points: List<PointF>): Pair<Float, Float> {
    var sumX = 0f
    var sumY = 0f
    for (p in points) {
        sumX += p.x
        sumY += p.y
    }
    val n = points.size.toFloat()
    return (sumX / n) to (sumY / n)
}

/**
 * Builds the 2x2 covariance matrix from a list of points given their mean.
 *
 * Cov =
 *  [ cov11  cov12 ]
 *  [ cov12  cov22 ]
 */
private fun computeCovMatrix(
    points: List<PointF>,
    cx: Float,
    cy: Float
): Triple<Float, Float, Float> {
    var cov11 = 0f  // var(x)
    var cov12 = 0f  // cov(x,y)
    var cov22 = 0f  // var(y)

    for (p in points) {
        val dx = p.x - cx
        val dy = p.y - cy
        cov11 += dx * dx
        cov12 += dx * dy
        cov22 += dy * dy
    }
    val n = points.size.toFloat()

    // "Sample" covariance, dividing by n (or n-1 if you prefer)
    cov11 /= n
    cov12 /= n
    cov22 /= n

    return Triple(cov11, cov12, cov22)
}

/**
 * Computes eigenvalues (lambda1, lambda2) of the 2x2 matrix:
 *   | a  b |
 *   | b  c |
 *
 * λ = (trace ± sqrt(trace^2 - 4 * det)) / 2
 */
private fun eigenValues2x2(
    a: Float, b: Float, c: Float
): Pair<Float, Float> {
    val trace = a + c
    val det = (a * c) - (b * b)
    val disc = trace * trace - 4f * det

    if (disc < 0f) {
        // This can happen due to numerical issues or if shape is degenerate
        // We'll clamp disc to zero to avoid NaN
        val lambda = trace / 2f
        return lambda to lambda
    }

    val sqrtDisc = sqrt(disc)
    val lambda1 = (trace + sqrtDisc) / 2f
    val lambda2 = (trace - sqrtDisc) / 2f
    return lambda1 to lambda2
}
