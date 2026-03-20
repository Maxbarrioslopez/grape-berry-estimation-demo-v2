package com.gaiaspa.metrics_detection.ml

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import android.graphics.*
import android.util.Log
import kotlin.math.*


object IntMask512Pool {
    private var arr: Array<IntArray>? = null
    private var w: Int = 0
    private var h: Int = 0

    fun get(outW: Int, outH: Int): Array<IntArray> {
        val a = arr
        if (a == null || w != outW || h != outH) {
            w = outW; h = outH
            arr = Array(outH) { IntArray(outW) }
        }
        return arr!!
    }

    fun clear() { arr = null; w = 0; h = 0 }
}

fun Bitmap.alpha8ToIntMaskReused(outW: Int, outH: Int): Array<IntArray> {
    require(this.config == Bitmap.Config.ALPHA_8) { "Expected ALPHA_8 bitmap" }
    require(this.width == outW && this.height == outH) { "Size mismatch" }

    val out = IntMask512Pool.get(outW, outH)

    val buf = ByteArray(outW * outH)
    this.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(buf))

    var k = 0
    for (y in 0 until outH) {
        val row = out[y]
        for (x in 0 until outW) {
            row[x] = if ((buf[k++].toInt() and 0xFF) != 0) 1 else 0
        }
    }
    return out
}

object MaskPool {
    // Slot 0: low-res (xPoints,yPoints)
    private var alpha0: Bitmap? = null
    // Slot 1: full-res (512,512)
    private var alpha1: Bitmap? = null
    // Tmp para conversiones
    private var tmpAlpha: Bitmap? = null

    fun getAlpha(slot: Int, w: Int, h: Int): Bitmap {
        val ref = if (slot == 0) alpha0 else alpha1
        if (ref == null || ref.width != w || ref.height != h || ref.config != Bitmap.Config.ALPHA_8) {
            // recicla SOLO el slot correspondiente
            if (slot == 0) alpha0?.recycle() else alpha1?.recycle()
            val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            if (slot == 0) alpha0 = nb else alpha1 = nb
        }
        return if (slot == 0) alpha0!! else alpha1!!
    }

    fun getTmpAlpha(w: Int, h: Int): Bitmap {
        val b = tmpAlpha
        if (b == null || b.width != w || b.height != h || b.config != Bitmap.Config.ALPHA_8) {
            tmpAlpha?.recycle()
            tmpAlpha = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        }
        return tmpAlpha!!
    }

    fun clear() {
        alpha0?.recycle(); alpha0 = null
        alpha1?.recycle(); alpha1 = null
        tmpAlpha?.recycle(); tmpAlpha = null
    }
}

object ImageUtils {

    // ============================================================
    // Existing helpers
    // ============================================================
    fun Array<IntArray>.toAlpha8Bitmap(): Bitmap {
        val h = this.size
        val w = if (h > 0) this[0].size else 0
        require(w > 0 && h > 0) { "Mask vacía" }

        val bmp = MaskPool.getAlpha(slot = 0, w = w, h = h)   // << slot 0
        val buf = ByteArray(w * h)
        var k = 0
        for (y in 0 until h) {
            val row = this[y]
            for (x in 0 until w) {
                buf[k++] = if (row[x] != 0) 0xFF.toByte() else 0x00.toByte()
            }
        }
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(buf))
        return bmp
    }
    fun Bitmap.scaleMask(outW: Int, outH: Int): Bitmap {
        val src = if (this.config == Bitmap.Config.ALPHA_8) this else {
            val conv = MaskPool.getTmpAlpha(this.width, this.height)
            val c = Canvas(conv)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val p = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
            c.drawBitmap(this, 0f, 0f, p)
            conv
        }

        // DESTINO en slot 1 (512,512). NO recicla el slot 0 (src low-res).
        val dst = MaskPool.getAlpha(slot = 1, w = outW, h = outH)

        val c = Canvas(dst)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val m = Matrix().apply {
            setScale(outW.toFloat() / src.width.toFloat(), outH.toFloat() / src.height.toFloat())
        }

        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            isFilterBitmap = true
            isAntiAlias = false
            isDither = false
        }

        c.drawBitmap(src, m, p)
        return dst
    }
     fun Array<IntArray>.scaleNearest(newW: Int, newH: Int): Array<IntArray> {
        val srcH = this.size
        val srcW = if (srcH > 0) this[0].size else 0
        require(srcW > 0 && srcH > 0) { "Mask vacía" }

        val out = Array(newH) { IntArray(newW) }
        for (y in 0 until newH) {
            val sy = (y.toLong() * srcH / newH).toInt().coerceIn(0, srcH - 1)
            val srcRow = this[sy]
            val outRow = out[y]
            for (x in 0 until newW) {
                val sx = (x.toLong() * srcW / newW).toInt().coerceIn(0, srcW - 1)
                outRow[x] = srcRow[sx]
            }
        }
        return out
    }
    fun Array<FloatArray>.toMask(): Array<IntArray> {
        return Array(this.size) { i ->
            IntArray(this[i].size) { j ->
                if (this[i][j] > 0f) 1 else 0
            }
        }
    }

    // ============================================================
    // NEW: ROI-only smoothing to avoid OOM
    // - Finds bbox of mask==1
    // - Expands bbox by margin
    // - Crops ROI, smooths only ROI, thresholds, pastes back
    // ============================================================
    fun Array<IntArray>.smoothRoiOnly(
        kernel: Int,
        margin: Int = max(2, kernel),   // safe default: expand bbox by ~kernel
        threshold: Float = 0.9f,
        sigma: Float = 2f
    ): Array<IntArray> {
        if (this.isEmpty() || this[0].isEmpty()) return this

        val h = this.size
        val w = this[0].size

        val bb0 = bboxFromMask(this) ?: return this // nothing to smooth
        val bb = expandBBox(bb0, margin, w, h)

        val roi = cropMask(this, bb)

        // Smooth ONLY ROI
        val roiSmoothed = roi.smoothFull(kernel = kernel, threshold = threshold, sigma = sigma)

        // Paste back into full mask (only ROI area overwritten)
        val out = Array(h) { y -> this[y].clone() }
        pasteMask(out, roiSmoothed, bb)
        return out
    }

    /**
     * Backwards-compatible smoother, but internally uses ROI-only by default.
     * If you really want full-frame smoothing, call smoothFull(...) directly.
     */
    fun Array<IntArray>.smooth(
        kernel: Int,
        threshold: Float = 0.9f,
        sigma: Float = 2f
    ): Array<IntArray> {
        // ROI-only is what prevents OOM in large images / many instances.
        return this.smoothRoiOnly(kernel = kernel, margin = max(2, kernel), threshold = threshold, sigma = sigma)
    }

    // ============================================================
    // INTERNAL: Full smoothing (old behavior) - keep available
    // ============================================================
    private fun Array<IntArray>.smoothFull(
        kernel: Int,
        threshold: Float,
        sigma: Float
    ): Array<IntArray> {
        // Convert mask to float (full frame)
        val maskFloat = Array(this.size) { i ->
            FloatArray(this[i].size) { j ->
                if (this[i][j] > 0) 1f else 0f
            }
        }
        val gaussianKernel = createGaussianKernel(kernel, sigma)
        val blurredImage = applyGaussianBlur(maskFloat, gaussianKernel)
        return thresholdImage(blurredImage, threshold)
    }

    // ============================================================
    // Gaussian helpers (same logic, minor safety upgrades)
    // ============================================================
    private fun createGaussianKernel(size: Int, sigma: Float): Array<FloatArray> {
        val s = max(0.1f, sigma)
        val k = max(1, size)
        val kernel = Array(k) { FloatArray(k) }
        val mean = k / 2
        var sum = 0f

        for (x in 0 until k) {
            for (y in 0 until k) {
                val dx = (x - mean).toFloat()
                val dy = (y - mean).toFloat()
                val v = (1f / (2f * Math.PI.toFloat() * s * s)) * exp(-(dx * dx + dy * dy) / (2f * s * s))
                kernel[x][y] = v
                sum += v
            }
        }

        val inv = if (sum > 0f) (1f / sum) else 1f
        for (x in 0 until k) for (y in 0 until k) kernel[x][y] *= inv
        return kernel
    }

    private fun applyGaussianBlur(image: Array<FloatArray>, kernel: Array<FloatArray>): Array<FloatArray> {
        val height = image.size
        val width = image[0].size
        val kernelSize = kernel.size
        val offset = kernelSize / 2
        val blurredImage = Array(height) { FloatArray(width) { 0f } }

        // Only compute where kernel fits; borders remain 0 (same as before)
        for (i in offset until height - offset) {
            val rowOut = blurredImage[i]
            for (j in offset until width - offset) {
                var sum = 0f
                for (ki in 0 until kernelSize) {
                    val srcRow = image[i - offset + ki]
                    val kRow = kernel[ki]
                    var kj = 0
                    val base = j - offset
                    while (kj < kernelSize) {
                        sum += srcRow[base + kj] * kRow[kj]
                        kj++
                    }
                }
                rowOut[j] = sum
            }
        }
        return blurredImage
    }

    private fun thresholdImage(image: Array<FloatArray>, thr: Float): Array<IntArray> {
        val height = image.size
        val width = image[0].size
        val t = thr.coerceIn(0f, 1f)
        return Array(height) { i ->
            IntArray(width) { j ->
                if (image[i][j] > t) 1 else 0
            }
        }
    }

    // ============================================================
    // ROI utilities
    // ============================================================
    private data class BBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
        val w: Int get() = (x2 - x1 + 1)
        val h: Int get() = (y2 - y1 + 1)
    }

    private fun bboxFromMask(mask: Array<IntArray>): BBox? {
        val h = mask.size
        val w = mask[0].size
        var xMin = w
        var yMin = h
        var xMax = -1
        var yMax = -1

        for (y in 0 until h) {
            val row = mask[y]
            for (x in 0 until w) {
                if (row[x] != 0) {
                    if (x < xMin) xMin = x
                    if (y < yMin) yMin = y
                    if (x > xMax) xMax = x
                    if (y > yMax) yMax = y
                }
            }
        }
        return if (xMax >= 0) BBox(xMin, yMin, xMax, yMax) else null
    }

    private fun expandBBox(bb: BBox, margin: Int, imgW: Int, imgH: Int): BBox {
        val m = max(0, margin)
        val x1 = (bb.x1 - m).coerceIn(0, imgW - 1)
        val y1 = (bb.y1 - m).coerceIn(0, imgH - 1)
        val x2 = (bb.x2 + m).coerceIn(0, imgW - 1)
        val y2 = (bb.y2 + m).coerceIn(0, imgH - 1)
        return BBox(x1, y1, x2, y2)
    }

    private fun cropMask(mask: Array<IntArray>, bb: BBox): Array<IntArray> {
        val outH = bb.h
        val outW = bb.w
        val out = Array(outH) { IntArray(outW) }
        var yy = 0
        var y = bb.y1
        while (y <= bb.y2) {
            val srcRow = mask[y]
            val dstRow = out[yy]
            var xx = 0
            var x = bb.x1
            while (x <= bb.x2) {
                dstRow[xx] = srcRow[x]
                xx++
                x++
            }
            yy++
            y++
        }
        return out
    }

    private fun pasteMask(dst: Array<IntArray>, roi: Array<IntArray>, bb: BBox) {
        val outH = roi.size
        val outW = roi[0].size
        var yy = 0
        var y = bb.y1
        while (yy < outH && y <= bb.y2) {
            val dstRow = dst[y]
            val srcRow = roi[yy]
            var xx = 0
            var x = bb.x1
            while (xx < outW && x <= bb.x2) {
                dstRow[x] = srcRow[xx]
                xx++
                x++
            }
            yy++
            y++
        }
    }
}
