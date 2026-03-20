package com.gaiaspa.metrics_detection.ml

fun unifyMasks(
    maskA: Array<IntArray>,
    maskB: Array<IntArray>
): Array<IntArray> {
    val height = maskA.size
    val width = maskA[0].size
    val out = Array(height) { IntArray(width) }
    for (y in 0 until height) {
        for (x in 0 until width) {
            out[y][x] = if (maskA[y][x] != 0 || maskB[y][x] != 0) 1 else 0
        }
    }
    return out
}

fun countNonZero(mask: Array<IntArray>): Int {
    var count = 0
    for (y in mask.indices) {
        for (x in mask[y].indices) {
            if (mask[y][x] != 0) {
                count++
            }
        }
    }
    return count
}
