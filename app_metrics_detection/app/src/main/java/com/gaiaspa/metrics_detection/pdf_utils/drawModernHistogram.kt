package com.gaiaspa.metrics_detection.pdf_utils

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
/**
 * Dibuja un histograma estilizado sobre un [Canvas] de PDF dentro del area delimitada
 * por ([left], [top]) y dimensiones [width]×[height].
 *
 * ## Rol en la arquitectura
 * Llamado exclusivamente desde [createLotesReportPdf] para renderizar la distribucion
 * de calibres de cada [CalPredict] en la columna derecha de la fila de prediccion.
 *
 * ## Parametros
 * @param canvas Lienzo del PDF sobre el que se pinta.
 * @param left   Coordenada X del borde izquierdo del area de dibujo.
 * @param top    Coordenada Y del borde superior del area de dibujo.
 * @param width  Ancho disponible para el histograma completo.
 * @param height Alto disponible para el histograma completo.
 * @param bins   Etiquetas del eje X (valores de calibre).
 * @param pred   Frecuencias correspondientes a cada bin (altura de barra).
 * @param paint  [Paint] base reutilizado del llamador; su estado se restaura al final
 *               para no afectar dibujos posteriores.
 *
 * ## Comportamiento
 * - Si [bins] o [pred] estan vacios, o sus tamanos no coinciden, la funcion retorna
 *   inmediatamente sin dibujar nada.
 * - Si todos los valores de [pred] son ≤ 0, se usa 1 como maximo para evitar division
 *   por cero.
 * - Las etiquetas del eje X se rotan −45° para evitar solapamiento.
 * - El [Paint] recibido se modifica temporalmente; su estado original se guarda al
 *   inicio y se restaura al final del metodo.
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
    val cPrimary        = Color.parseColor("#2E7D32")
    val cPrimaryLight   = Color.parseColor("#F3F8F3")
    val cTextSecondary  = Color.parseColor("#5F6B5F")
    val cOutline        = Color.parseColor("#DDE5DD")

    /* ─── Guardar estado original del Paint ──────────────── */
    val oColor  = paint.color
    val oSize   = paint.textSize
    val oAlign  = paint.textAlign
    val oStyle  = paint.style
    val oEffect = paint.pathEffect

    /* ─── Fondo ──────────────────────────────────────────── */
    paint.style = Paint.Style.FILL
    paint.color = cPrimaryLight
    canvas.drawRoundRect(RectF(left, top, left + width, top + height), 10f, 10f, paint)

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
        canvas.drawRoundRect(RectF(bLeft, bTop, bRight, gBottom), 4f, 4f, paint)
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
