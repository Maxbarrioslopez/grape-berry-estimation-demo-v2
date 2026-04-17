package com.gaiaspa.metrics_detection.ml

import android.graphics.*
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

object ImageUtils {

    /**
     * Dibuja los resultados sobre el Bitmap.
     * Escala las coordenadas de ONNX (512x512) al tamaño real del Bitmap proporcionado.
     */
    fun drawDetectionsOverlay(original: Bitmap, results: List<SegmentationResult>): Bitmap {
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        
        // El modelo trabaja internamente a 512x512 (Letterbox)
        val scaleX = out.width.toFloat() / 512f
        val scaleY = out.height.toFloat() / 512f

        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = out.width / 150f // Grosor dinámico según resolución
            isAntiAlias = true
        }

        results.forEach { result ->
            val box = result.box
            boxPaint.color = if (box.clsName.contains("grape", true)) Color.GREEN else Color.RED
            
            // Dibujar escalando las coordenadas 512 -> Real
            canvas.drawRect(
                box.x1 * scaleX, 
                box.y1 * scaleY, 
                box.x2 * scaleX, 
                box.y2 * scaleY, 
                boxPaint
            )
        }
        return out
    }

    fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) { null }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun saveBitmapToDisk(bitmap: Bitmap, folder: File, prefix: String): String? {
        return try {
            val file = File(folder, "${prefix}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            file.absolutePath
        } catch (e: Exception) { null }
    }
}
