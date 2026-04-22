package com.gaiaspa.metrics_detection.ml

import android.graphics.*
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

/**
 * ImageUtils.kt - v8.8 MEMORY OPTIMIZED
 * Gestión unificada de imágenes con protección contra OOM.
 */
object ImageUtils {

    private const val TAG = "ImageUtils_MEM"

    /**
     * Motor de dibujo unificado.
     * ✅ Genera el overlay visual final en Kotlin.
     */
    fun drawDetectionsOverlay(original: Bitmap, results: List<SegmentationResult>, originalW: Int, originalH: Int): Bitmap {
        val drawer = SingleImageDrawer()
        val scaleX = original.width.toFloat() / originalW.toFloat()
        val scaleY = original.height.toFloat() / originalH.toFloat()
        return drawer.draw(original, results, scaleX, scaleY)
    }

    /**
     * Genera la imagen para el backend (512x512, sin overlay, letterbox).
     * ✅ OPTIMIZADO: Usa decodeSampledBitmap para no cargar la imagen original completa.
     */
    fun generateUpload512(srcPath: String, outputDir: File): String? {
        Log.d(TAG, "Generando upload_512 optimizado...")
        var scaled: Bitmap? = null
        var outBitmap: Bitmap? = null
        return try {
            // Decodificamos directamente a un tamaño cercano a 512 para ahorrar RAM
            val rotated = decodeSampledBitmap(srcPath, 512, 512) ?: return null
            
            outBitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outBitmap)
            canvas.drawColor(Color.BLACK) 
            
            val scale = 512f / max(rotated.width, rotated.height)
            val w = (rotated.width * scale).toInt()
            val h = (rotated.height * scale).toInt()
            
            scaled = Bitmap.createScaledBitmap(rotated, w, h, true)
            canvas.drawBitmap(scaled, (512 - w) / 2f, (512 - h) / 2f, null)
            
            val outFile = File(outputDir, "upload_512_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { out ->
                outBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            
            rotated.recycle()
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error generando upload_512: ${e.message}")
            null
        } finally {
            scaled?.recycle()
            outBitmap?.recycle()
        }
    }

    /**
     * Decodifica una imagen ajustándola a un tamaño máximo manteniendo el aspecto.
     */
    fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { 
                inJustDecodeBounds = true 
            }
            BitmapFactory.decodeFile(path, options)
            
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            // Opcional: Usar RGB_565 si la memoria es crítica, pero ARGB_8888 es mejor para overlays
            options.inPreferredConfig = Bitmap.Config.ARGB_8888 
            
            BitmapFactory.decodeFile(path, options)?.let { rotateImageIfRequired(it, path) }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM decodificando imagen: $path")
            null
        } catch (e: Exception) { 
            Log.e(TAG, "Error decodificando: ${e.message}")
            null 
        }
    }

    private fun rotateImageIfRequired(img: Bitmap, path: String): Bitmap {
        return try {
            val ei = androidx.exifinterface.media.ExifInterface(path)
            val orientation = ei.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
            when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> rotate(img, 90f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> rotate(img, 180f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> rotate(img, 270f)
                else -> img
            }
        } catch (e: Exception) { img }
    }

    private fun rotate(img: Bitmap, degree: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degree) }
        val rotated = Bitmap.createBitmap(img, 0, 0, img.width, img.height, matrix, true)
        if (rotated != img) img.recycle()
        return rotated
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
            val file = File(folder, "$prefix.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            file.absolutePath
        } catch (e: Exception) { 
            Log.e(TAG, "Error guardando: ${e.message}")
            null 
        }
    }
}
