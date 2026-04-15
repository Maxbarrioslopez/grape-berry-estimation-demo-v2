package com.gaiaspa.metrics_detection.ml

import android.content.Context
import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

object ImageUtils {

    private const val TAG = "ImageUtils"
    private const val TARGET_SIZE = 512

    /**
     * Decodifica una imagen desde disco aplicando downsampling para ahorrar memoria.
     */
    fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            
            val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
            rotateImageIfRequired(bitmap, path)
        } catch (e: Exception) {
            Log.e(TAG, "Error en decodeSampledBitmap: ${e.message}")
            null
        }
    }

    /**
     * Normaliza una imagen a un JPEG de 512x512 limpio (Gold Standard).
     */
    fun normalizeTo512(context: Context, sourcePath: String, outputFolder: File): String? {
        return try {
            val sampledBitmap = decodeSampledBitmap(sourcePath, TARGET_SIZE * 2, TARGET_SIZE * 2) ?: return null
            
            // Crear Letterbox 512x512 (Ya corregido por decodeSampledBitmap para rotación)
            val normalizedBitmap = createLetterbox(sampledBitmap, TARGET_SIZE)
            
            val outputFile = File(outputFolder, "norm_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outputFile).use { out ->
                normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                out.flush()
            }
            
            sampledBitmap.recycle()
            normalizedBitmap.recycle()
            
            outputFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error normalizando imagen: ${e.message}")
            null
        }
    }

    private fun createLetterbox(src: Bitmap, targetSize: Int): Bitmap {
        val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val srcW = src.width.toFloat()
        val srcH = src.height.toFloat()
        val scale = min(targetSize / srcW, targetSize / srcH)

        val finalW = (srcW * scale).toInt()
        val finalH = (srcH * scale).toInt()

        val left = (targetSize - finalW) / 2
        val top = (targetSize - finalH) / 2

        val scaled = Bitmap.createScaledBitmap(src, finalW, finalH, true)
        canvas.drawBitmap(scaled, left.toFloat(), top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        
        if (scaled != src) scaled.recycle()
        return out
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

    private fun rotateImageIfRequired(img: Bitmap, path: String): Bitmap {
        val ei = ExifInterface(path)
        val orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotate(img, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotate(img, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotate(img, 270f)
            else -> img
        }
    }

    private fun rotate(img: Bitmap, degree: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degree) }
        val rotated = Bitmap.createBitmap(img, 0, 0, img.width, img.height, matrix, true)
        if (rotated != img) img.recycle()
        return rotated
    }

    fun saveBitmapToDisk(bitmap: Bitmap, folder: File, prefix: String = "img"): String {
        val file = File(folder, "${prefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file.absolutePath
    }
}
