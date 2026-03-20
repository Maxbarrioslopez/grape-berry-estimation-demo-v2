package com.gaiaspa.metrics_detection.pdf_utils

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
/**
 * Función utilitaria para dibujar un histograma estilizado en el Canvas del PDF.
 */
/**
 * Función que dibuja un histograma usando:
 *  - bins: Lista de etiquetas para el eje X (Floats)
 *  - pred: Lista de valores para las barras (Ints)
 */

fun drawModernHistogram(
    canvas: Canvas,
    left:  Float,
    top:   Float,
    width: Float,
    height: Float,
    bins:  List<Float>,
    pred:  List<Int>,
    paint: Paint          // reutilizamos el mismo Paint que ya trae el caller
) {
    if (bins.isEmpty() || pred.isEmpty() || bins.size != pred.size) return

    /* ─── Colores de la paleta ───────────────────────────── */
    val cPrimary        = Color.parseColor("#3F51B5")
    val cPrimaryLight   = Color.parseColor("#f7f8fc")
    val cTextSecondary  = Color.parseColor("#757575")
    val cOutline        = Color.parseColor("#D1D5DB")

    /* ─── Guardar estado original del Paint ──────────────── */
    val oColor  = paint.color
    val oSize   = paint.textSize
    val oAlign  = paint.textAlign
    val oStyle  = paint.style
    val oEffect = paint.pathEffect

    /* ─── Fondo ──────────────────────────────────────────── */
    paint.style = Paint.Style.FILL
    paint.color = cPrimaryLight
    canvas.drawRect(left, top, left + width, top + height, paint)

    /* ─── Preparar área de gráfico ───────────────────────── */
    val mX        = 8f
    val mY        = 8f       // un poco menos alto que antes
    val gLeft     = left + mX
    val gTop      = top  + mY
    val gRight    = left + width  - mX
    val gBottom   = top  + height - mY - 16f
    val gWidth    = gRight  - gLeft
    val gHeight   = gBottom - gTop

    val maxVal    = pred.maxOrNull()?.takeIf { it > 0 } ?: 1
    val barWidth  = gWidth / bins.size

    /* ─── Ejes ───────────────────────────────────────────── */
    paint.color = cTextSecondary
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.2f
    canvas.drawLine(gLeft,  gTop,    gLeft,  gBottom, paint)   // eje Y
    canvas.drawLine(gLeft,  gBottom, gRight, gBottom, paint)   // eje X

    /* ─── Grid horizontal ───────────────────────────────── */
    val gridLines    = 5
    val stepHeight   = gHeight / gridLines
    paint.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
    paint.color      = cOutline
    for (i in 1 until gridLines) {
        val y = gBottom - i * stepHeight
        canvas.drawLine(gLeft, y, gRight, y, paint)
    }
    paint.pathEffect = null

    /* ─── Barras ────────────────────────────────────────── */
    paint.style = Paint.Style.FILL
    paint.color = cPrimary
    for (i in bins.indices) {
        val barH   = gHeight * (pred[i].toFloat() / maxVal)
        val bLeft  = gLeft + i * barWidth + 2f
        val bRight = bLeft + barWidth - 4f
        val bTop   = gBottom - barH
        canvas.drawRect(bLeft, bTop, bRight, gBottom, paint)
    }

    /* ─── Etiquetas eje Y (valores) ─────────────────────── */
    paint.color     = cTextSecondary
    paint.textSize  = 10f
    paint.textAlign = Paint.Align.RIGHT
    for (i in 0..gridLines) {
        val yVal = (maxVal / gridLines.toFloat()) * i
        val y    = gBottom - i * stepHeight + 8f
        canvas.drawText(String.format("%.0f", yVal), gLeft - 6f, y, paint)
    }

    /* ─── Etiquetas eje X (bins) ────────────────────────── */
    paint.textAlign = Paint.Align.LEFT
    for ((i, bin) in bins.withIndex()) {
        val x = gLeft + i * barWidth + barWidth / 2
        val y = gBottom + 24f

        canvas.save()
        canvas.rotate(-45f, x, y)
        canvas.drawText(String.format("%.1f", bin), x, y, paint)
        canvas.restore()
    }

    /* ─── Restaurar Paint ───────────────────────────────── */
    paint.color       = oColor
    paint.textSize    = oSize
    paint.textAlign   = oAlign
    paint.style       = oStyle
    paint.pathEffect  = oEffect
}
