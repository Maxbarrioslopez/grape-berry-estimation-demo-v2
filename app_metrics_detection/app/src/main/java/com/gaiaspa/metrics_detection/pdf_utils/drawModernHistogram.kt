package com.gaiaspa.metrics_detection.pdf_utils

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
/**
 * Draws a stylized histogram on a PDF [Canvas] within the area delimited
 * by ([left], [top]) and dimensions [width]x[height].
 *
 * ## Architectural role
 * Called exclusively from [createLotesReportPdf] to render the calibre
 * distribution of each [CalPredict] in the right column of the prediction row.
 *
 * ## Parameters
 * @param canvas PDF canvas on which to paint.
 * @param left   X coordinate of the left edge of the drawing area.
 * @param top    Y coordinate of the top edge of the drawing area.
 * @param width  Available width for the complete histogram.
 * @param height Available height for the complete histogram.
 * @param bins   X-axis labels (calibre values).
 * @param pred   Frequencies corresponding to each bin (bar height).
 * @param paint  Base [Paint] reused from the caller; its state is restored at the end
 *               so as not to affect subsequent drawings.
 *
 * ## Behavior
 * - If [bins] or [pred] are empty, or their sizes do not match, the function returns
 *   immediately without drawing anything.
 * - If all values in [pred] are <= 0, 1 is used as the maximum to avoid division
 *   by zero.
 * - X-axis labels are rotated -45 to avoid overlapping.
 * - The received [Paint] is temporarily modified; its original state is saved at
 *   the beginning and restored at the end of the method.
 */
fun drawModernHistogram(
    canvas: Canvas,
    left:  Float,
    top:   Float,
    width: Float,
    height: Float,
    bins:  List<Float>,
    pred:  List<Int>,
    paint: Paint          // reuse the same Paint already provided by the caller
) {
    if (bins.isEmpty() || pred.isEmpty() || bins.size != pred.size) return

    /* ─── Palette colors ───────────────────────────── */
    val cPrimary        = Color.parseColor("#2E7D32")
    val cPrimaryLight   = Color.parseColor("#F3F8F3")
    val cTextSecondary  = Color.parseColor("#5F6B5F")
    val cOutline        = Color.parseColor("#DDE5DD")

    /* ─── Save original Paint state ──────────────── */
    val oColor  = paint.color
    val oSize   = paint.textSize
    val oAlign  = paint.textAlign
    val oStyle  = paint.style
    val oEffect = paint.pathEffect

    /* ─── Background ──────────────────────────────────────── */
    paint.style = Paint.Style.FILL
    paint.color = cPrimaryLight
    canvas.drawRoundRect(RectF(left, top, left + width, top + height), 10f, 10f, paint)

    /* ─── Prepare graph area ───────────────────────── */
        val mX        = 8f
        val mY        = 8f       // a bit less tall than before
    val gLeft     = left + mX
    val gTop      = top  + mY
    val gRight    = left + width  - mX
    val gBottom   = top  + height - mY - 16f
    val gWidth    = gRight  - gLeft
    val gHeight   = gBottom - gTop

    val maxVal    = pred.maxOrNull()?.takeIf { it > 0 } ?: 1
    val barWidth  = gWidth / bins.size

    /* ─── Axes ───────────────────────────────────────────── */
    paint.color = cTextSecondary
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.2f
    canvas.drawLine(gLeft,  gTop,    gLeft,  gBottom, paint)   // Y axis
    canvas.drawLine(gLeft,  gBottom, gRight, gBottom, paint)   // X axis

    /* ─── Horizontal grid ───────────────────────────────── */
    val gridLines    = 5
    val stepHeight   = gHeight / gridLines
    paint.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
    paint.color      = cOutline
    for (i in 1 until gridLines) {
        val y = gBottom - i * stepHeight
        canvas.drawLine(gLeft, y, gRight, y, paint)
    }
    paint.pathEffect = null

    /* ─── Bars ────────────────────────────────────────── */
    paint.style = Paint.Style.FILL
    paint.color = cPrimary
    for (i in bins.indices) {
        val barH   = gHeight * (pred[i].toFloat() / maxVal)
        val bLeft  = gLeft + i * barWidth + 2f
        val bRight = bLeft + barWidth - 4f
        val bTop   = gBottom - barH
        canvas.drawRoundRect(RectF(bLeft, bTop, bRight, gBottom), 4f, 4f, paint)
    }

    /* ─── Y-axis labels (values) ─────────────────────── */
    paint.color     = cTextSecondary
    paint.textSize  = 10f
    paint.textAlign = Paint.Align.RIGHT
    for (i in 0..gridLines) {
        val yVal = (maxVal / gridLines.toFloat()) * i
        val y    = gBottom - i * stepHeight + 8f
        canvas.drawText(String.format("%.0f", yVal), gLeft - 6f, y, paint)
    }

    /* ─── X-axis labels (bins) ────────────────────────── */
    paint.textAlign = Paint.Align.LEFT
    for ((i, bin) in bins.withIndex()) {
        val x = gLeft + i * barWidth + barWidth / 2
        val y = gBottom + 24f

        canvas.save()
        canvas.rotate(-45f, x, y)
        canvas.drawText(String.format("%.1f", bin), x, y, paint)
        canvas.restore()
    }

    /* ─── Restore Paint ───────────────────────────────── */
    paint.color       = oColor
    paint.textSize    = oSize
    paint.textAlign   = oAlign
    paint.style       = oStyle
    paint.pathEffect  = oEffect
}
