// ScratchBuffers.kt (o dentro de ImageUtils como private object)
package com.gaiaspa.metrics_detection.ml

import kotlin.math.max

internal object ScratchBuffers {
    private var w: Int = 0
    private var h: Int = 0
    private var n: Int = 0

    // Reutilizables
    var tmpIntA: IntArray = IntArray(1)
        private set
    var tmpIntB: IntArray = IntArray(1)
        private set
    var tmpByteA: ByteArray = ByteArray(1)
        private set
    var tmpByteB: ByteArray = ByteArray(1)
        private set

    fun ensure(w0: Int, h0: Int) {
        val n0 = max(1, w0 * h0)
        if (w0 != w || h0 != h || n0 != n) {
            w = w0
            h = h0
            n = n0
            tmpIntA = IntArray(n0)
            tmpIntB = IntArray(n0)
            tmpByteA = ByteArray(n0)
            tmpByteB = ByteArray(n0)
        }
    }
}
