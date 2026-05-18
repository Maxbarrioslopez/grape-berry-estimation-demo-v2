package com.gaiaspa.metrics_detection.ml

import android.graphics.*
import android.util.Log
import kotlin.math.sqrt

/**
 * SingleImageDrawer — v7.7 TRACE
 *
 * Canonical drawing engine for detection overlay rendering. Produces the final
 * visual output bitmap displayed to the user.
 *
 * Behaviour:
 * - Draws green ovals around each detected berry.
 * - Clamps oval radii when multiple detections are close together (visual-only
 *   adjustment; does not affect measurement data).
 * - Renders a fixed blue watermark (circle + "OVAL_TEST" text) in the top-right
 *   corner for absolute output validation.
 * - Logs drawing progress and the first oval position at DEBUG level for
 *   troubleshooting.
 */
class SingleImageDrawer {

    /**
     * Renders detection results as ovals on a copy of the original bitmap.
     *
     * @param original The base image on which detections will be drawn.
     * @param results Detection results with bounding boxes in model-native coordinates.
     * @param scaleX Horizontal scaling factor from model space to bitmap pixels.
     * @param scaleY Vertical scaling factor from model space to bitmap pixels.
     * @return A new [Bitmap] containing the original image with overlaid detections
     *         and watermark.
     */
    fun draw(original: Bitmap, results: List<SegmentationResult>, scaleX: Float, scaleY: Float): Bitmap {
        Log.d("SingleDrawer_TRACE", "[D] Iniciando dibujo. Bitmap: ${original.width}x${original.height}. Detecciones: ${results.size}")
        
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        
        val paint = Paint().apply {
            color = Color.parseColor("#4CAF50")
            style = Paint.Style.STROKE
            strokeWidth = out.width / 180f
            isAntiAlias = true
        }

        // 1. Calcular centros y radios para separación visual
        data class OvalCenter(val cx: Float, val cy: Float, val rx: Float, val ry: Float)
        val ovals = results.map { result ->
            val rect = RectF(
                result.box.x1 * scaleX,
                result.box.y1 * scaleY,
                result.box.x2 * scaleX,
                result.box.y2 * scaleY
            )
            OvalCenter(rect.centerX(), rect.centerY(), rect.width() / 2f, rect.height() / 2f)
        }

        // 2. Ajustar radio si hay vecinos cercanos (visual-only)
        val clamped = ovals.mapIndexed { index, o ->
            var minDist = Float.MAX_VALUE
            ovals.forEachIndexed { otherIdx, other ->
                if (otherIdx != index) {
                    val dist = sqrt((o.cx - other.cx).let { it * it } + (o.cy - other.cy).let { it * it })
                    if (dist < minDist) minDist = dist
                }
            }
            val maxClamp = minDist * 0.44f
            val crx = if (o.rx > maxClamp && maxClamp > 0f) maxClamp else o.rx
            val cry = if (o.ry > maxClamp && maxClamp > 0f) maxClamp else o.ry
            RectF(o.cx - crx, o.cy - cry, o.cx + crx, o.cy + cry)
        }

        // 3. Dibujar óvalos con radio ajustado
        clamped.forEachIndexed { index, rect ->
            canvas.drawOval(rect, paint)
            if (index == 0) Log.d("SingleDrawer_TRACE", "[D.1] Primer óvalo en: $rect")
        }

        // 🚨 MARCA DE AGUA PARA VALIDACIÓN ABSOLUTA
        Log.d("SingleDrawer_TRACE", "[D.2] Dibujando OVAL_TEST (Marca de agua azul)")
        val testPaint = Paint().apply {
            color = Color.BLUE
            style = Paint.Style.FILL
            textSize = out.width / 12f
            isFakeBoldText = true
        }
        // Círculo azul en esquina y texto gigante
        canvas.drawCircle(out.width - 80f, 80f, 50f, testPaint)
        canvas.drawText("OVAL_TEST", 40f, 120f, testPaint)

        Log.d("SingleDrawer_TRACE", "[D.3] Dibujo finalizado")
        return out
    }
}
