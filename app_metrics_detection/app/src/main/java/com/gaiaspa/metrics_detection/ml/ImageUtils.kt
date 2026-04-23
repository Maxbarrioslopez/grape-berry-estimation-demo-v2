package com.gaiaspa.metrics_detection.ml

import android.graphics.*
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

/**
 * ImageUtils.kt - v10.1 QUALITY UPLOAD
 * Gestión unificada de imágenes con protección contra OOM.
 * Actualizado para subir imágenes de 1024px manteniendo proporción.
 */
object ImageUtils {

    private const val TAG = "ImageUtils_ARCH"

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
     * Genera la imagen para el backend (Lado mayor 1024px, JPEG 85%).
     * ✅ OPTIMIZADO: Usa lado mayor 1024px, manteniendo proporción (NO letterbox).
     */
    fun generateUpload512(srcPath: String, outputDir: File): String? {
        Log.d(TAG, "Generando imagen de subida de alta calidad (1024px)...")
        var scaled: Bitmap? = null
        return try {
            // 1. Decodificamos con sampleo a un tamaño cercano a 1024 para ahorrar RAM
            val rotated = decodeSampledBitmap(srcPath, 1024, 1024) ?: return null
            
            // 2. Calculamos nuevas dimensiones manteniendo proporción (Lado mayor = 1024)
            val width = rotated.width
            val height = rotated.height
            val newWidth: Int
            val newHeight: Int
            
            if (width >= height) {
                newWidth = 1024
                newHeight = (height * 1024) / width
            } else {
                newHeight = 1024
                newWidth = (width * 1024) / height
            }
            
            // 3. Escalado final
            scaled = Bitmap.createScaledBitmap(rotated, newWidth, newHeight, true)
            if (scaled != rotated) rotated.recycle()
            
            // 4. Compresión y guardado (Mantenemos prefijo upload_512 por compatibilidad de sistema)
            val outFile = File(outputDir, "upload_512_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            
            Log.d(TAG, "Imagen de subida generada: ${newWidth}x${newHeight} px en ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error generando imagen de subida: ${e.message}")
            null
        } finally {
            scaled?.recycle()
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

    /**
     * Generador de nombre único y determinista para imágenes descargadas de la nube.
     */
    fun getCloudImageName(cloudId: String, index: Int): String {
        return "upload_512_cloud_${cloudId}_${index}.jpg"
    }
}
