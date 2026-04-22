package com.gaiaspa.metrics_detection.ml

import android.graphics.*
import android.util.Log

/**
 * SingleImageDrawer (vía SingleDrawer.kt) - v7.7 TRACE
 * Único motor de dibujo oficial. Incluye marca de agua para validación absoluta y LOGS.
 */
class SingleImageDrawer {

    fun draw(original: Bitmap, results: List<SegmentationResult>, scaleX: Float, scaleY: Float): Bitmap {
        Log.d("SingleDrawer_TRACE", "[D] Iniciando dibujo. Bitmap: ${original.width}x${original.height}. Detecciones: ${results.size}")
        
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        
        val paint = Paint().apply {
            color = Color.parseColor("#4CAF50") // Verde Gaia
            style = Paint.Style.STROKE
            strokeWidth = out.width / 180f
            isAntiAlias = true
        }

        // 1. Dibujar ÓVALOS (Contornos naturales)
        results.forEachIndexed { index, result ->
            val rect = RectF(
                result.box.x1 * scaleX,
                result.box.y1 * scaleY,
                result.box.x2 * scaleX,
                result.box.y2 * scaleY
            )
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
