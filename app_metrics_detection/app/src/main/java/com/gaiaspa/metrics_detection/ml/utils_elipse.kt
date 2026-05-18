package com.gaiaspa.metrics_detection.ml

import android.graphics.PointF
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class EllipseParams(
    val centerX: Float,
    val centerY: Float,
    val majorAxis: Float,
    val minorAxis: Float,
    val angleDeg: Float  // orientation in degrees
)


data class EllipseAxisPoints(
    val majorAxis: List<PointF>,
    val minorAxis: List<PointF>
)

/**
 * Kotlin equivalent of your Python get_ellipse_axis_points.
 *
 * @param center      (xc, yc) center of the ellipse, in pixels
 * @param majorAxis   length of the major axis, in pixels
 * @param minorAxis   length of the minor axis, in pixels
 * @param angleDeg    rotation angle of the ellipse in degrees
 * @return [EllipseAxisPoints] containing two endpoints for the major axis and two for the minor axis.
 */
fun getEllipseAxisPoints(
    xc: Float,
    yc: Float,
    majorAxis: Float,
    minorAxis: Float,
    angleDeg: Float
): EllipseAxisPoints {

    val angleRad = Math.toRadians(angleDeg.toDouble())

    // Half-length of the major axis
    val rMajor = majorAxis / 2f
    val x1Major = xc + cos(angleRad) * rMajor
    val y1Major = yc - sin(angleRad) * rMajor
    val x2Major = xc - cos(angleRad) * rMajor
    val y2Major = yc + sin(angleRad) * rMajor

    // Half-length of the minor axis
    val rMinor = minorAxis / 2f
    val x1Minor = xc + sin(angleRad) * rMinor
    val y1Minor = yc + cos(angleRad) * rMinor
    val x2Minor = xc - sin(angleRad) * rMinor
    val y2Minor = yc - cos(angleRad) * rMinor

    return EllipseAxisPoints(
        majorAxis = listOf(PointF(x1Major.toFloat(), y1Major.toFloat()),
            PointF(x2Major.toFloat(), y2Major.toFloat())),
        minorAxis = listOf(PointF(x1Minor.toFloat(), y1Minor.toFloat()),
            PointF(x2Minor.toFloat(), y2Minor.toFloat()))
    )
}

/**
 * Fits an ellipse to the nonzero pixels of [mask] using second-order moments,
 * returning center, majorAxis, minorAxis, and orientation angle in degrees.
 *
 * This mimics the main idea of cv2.fitEllipse, but is approximate.
 */
fun fitEllipseMoments(mask: Array<IntArray>): EllipseParams? {
    val points = mutableListOf<PointF>()
    for (y in mask.indices) {
        for (x in mask[y].indices) {
            if (mask[y][x] != 0) {
                points.add(PointF(x.toFloat(), y.toFloat()))
            }
        }
    }
    if (points.size < 5) return null

    // 1) Centroid
    val (cx, cy) = computeMean(points)

    // 2) Covariance
    val (cov11, cov12, cov22) = computeCovMatrix(points, cx, cy)

    // 3) Eigenvalues and eigenvectors
    val (lambda1, lambda2, vec1, vec2) = eigenDecomposition2x2(cov11, cov12, cov22)

    // If eigenvalues are non-positive => can't fit
    if (lambda1 <= 0f || lambda2 <= 0f) return null

    // majorAxis = 2 * sqrt(larger eigenvalue)
    // minorAxis = 2 * sqrt(smaller eigenvalue)
    val (lamMax, lamMin, vecMax) = if (lambda1 >= lambda2) {
        Triple(lambda1, lambda2, vec1)
    } else {
        Triple(lambda2, lambda1, vec2)
    }
    val majorAxis = 2f * sqrt(lamMax)
    val minorAxis = 2f * sqrt(lamMin)

    // orientation angle in radians = atan2(vector.y, vector.x)
    // convert to degrees
    val angleRad = atan2(vecMax.y, vecMax.x)
    val angleDeg = (angleRad * 180f / PI.toFloat()) % 180f

    return EllipseParams(cx, cy, majorAxis, minorAxis, angleDeg)
}

// Helpers:

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

private fun computeCovMatrix(
    points: List<PointF>,
    cx: Float,
    cy: Float
): Triple<Float, Float, Float> {
    var cov11 = 0f
    var cov12 = 0f
    var cov22 = 0f
    for (p in points) {
        val dx = p.x - cx
        val dy = p.y - cy
        cov11 += dx * dx
        cov12 += dx * dy
        cov22 += dy * dy
    }
    val n = points.size.toFloat()
    cov11 /= n
    cov12 /= n
    cov22 /= n
    return Triple(cov11, cov12, cov22)
}

/**
 * Returns (lambda1, lambda2, vec1, vec2) for the 2x2 matrix:
 *     [ a   b ]
 *     [ b   c ]
 * Where vec1, vec2 are the normalized eigenvectors corresponding to lambda1, lambda2.
 */
data class Vec2(val x: Float, val y: Float)

private fun eigenDecomposition2x2(
    a: Float, b: Float, c: Float
): Quadruple<Float, Float, Vec2, Vec2> {
    // 1) Eigenvalues
    val trace = a + c
    val det = a * c - b * b
    val disc = trace * trace - 4f * det

    // If negative discriminant => repeated eigenvalues
    val sqrtDisc = if (disc < 0f) 0f else kotlin.math.sqrt(disc)
    val lambda1 = (trace + sqrtDisc) / 2f
    val lambda2 = (trace - sqrtDisc) / 2f

    // 2) Eigenvectors (for 2x2, we can solve simply)
    // for lambda1: (a - lambda1, b)
    val vx1 = if (b != 0f) b else 1f
    val vy1 = lambda1 - a
    val mag1 = kotlin.math.sqrt(vx1 * vx1 + vy1 * vy1)
    val vec1 = if (mag1 > 1e-12) Vec2(vx1 / mag1, vy1 / mag1) else Vec2(1f, 0f)

    // for lambda2:
    val vx2 = if (b != 0f) b else 1f
    val vy2 = lambda2 - a
    val mag2 = kotlin.math.sqrt(vx2 * vx2 + vy2 * vy2)
    val vec2 = if (mag2 > 1e-12) Vec2(vx2 / mag2, vy2 / mag2) else Vec2(0f, 1f)

    return Quadruple(lambda1, lambda2, vec1, vec2)
}

// A helper data structure
data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
