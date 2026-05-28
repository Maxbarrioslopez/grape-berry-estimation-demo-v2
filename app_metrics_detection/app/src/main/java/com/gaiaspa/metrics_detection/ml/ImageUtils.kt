package com.gaiaspa.metrics_detection.ml

import android.graphics.*
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

/**
 * ImageUtils — v10.1 QUALITY UPLOAD
 *
 * Centralised image manipulation utility for the metrics-detection pipeline.
 * Provides:
 * - OOM-safe bitmap decoding with automatic EXIF rotation.
 * - High-quality upload-image generation (1024 px long edge, aspect-ratio preserving).
 * - Detection overlay rendering delegation to [SingleImageDrawer].
 * - Deterministic file-naming for cloud-downloaded images.
 *
 * All public functions return nullable types to allow graceful degradation
 * when input images are corrupt or the device is low on memory.
 */
object ImageUtils {

    private const val TAG = "ImageUtils_ARCH"

    /**
     * Renders detection bounding boxes as ovals over the original image.
     *
     * Delegates to [SingleImageDrawer], computing scale factors between
     * the model's coordinate space and the bitmap's pixel dimensions.
     *
     * @param original The base image bitmap to draw on.
     * @param results Detection results in model-native coordinate space.
     * @param originalW Width of the image as seen by the model.
     * @param originalH Height of the image as seen by the model.
     * @return A new bitmap with ovals drawn for each detection.
     */
    fun drawDetectionsOverlay(original: Bitmap, results: List<SegmentationResult>, originalW: Int, originalH: Int): Bitmap {
        val drawer = SingleImageDrawer()
        val scaleX = original.width.toFloat() / originalW.toFloat()
        val scaleY = original.height.toFloat() / originalH.toFloat()
        return drawer.draw(original, results, scaleX, scaleY)
    }

    /**
     * Generates a high-quality upload image for the backend.
     *
     * Produces a JPEG at 85% quality with the long edge scaled to 1024 px,
     * preserving the original aspect ratio (no letterboxing). Uses incremental
     * sampling during decode to reduce peak memory usage.
     *
     * The output filename prefix `upload_512_` is retained for backward
     * compatibility with existing backend storage schema.
     *
     * @param srcPath Absolute path to the source image file.
     * @param outputDir Directory where the generated JPEG will be written.
     * @return Absolute path to the generated file, or null on failure.
     */
    fun generateUpload512(srcPath: String, outputDir: File): String? {
        Log.d(TAG, "Generating high-quality upload image (1024px)...")
        var scaled: Bitmap? = null
        return try {
            // 1. Decode with sampling to a size close to 1024 to save RAM
            val rotated = decodeSampledBitmap(srcPath, 1024, 1024) ?: return null
            
            // 2. Calculate new dimensions preserving aspect ratio (Longest side = 1024)
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
            
            // 3. Final scaling
            scaled = Bitmap.createScaledBitmap(rotated, newWidth, newHeight, true)
            if (scaled != rotated) rotated.recycle()
            
            // 4. Compress and save (Keep upload_512 prefix for system compatibility)
            val outFile = File(outputDir, "upload_512_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            
            Log.d(TAG, "Upload image generated: ${newWidth}x${newHeight} px at ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error generating upload image: ${e.message}")
            null
        } finally {
            scaled?.recycle()
        }
    }

    /**
     * Decodes an image file into a bitmap, automatically subsampling to fit
     * within the requested dimensions while preserving aspect ratio.
     *
     * Handles [OutOfMemoryError] gracefully by returning null instead of crashing.
     * Automatically applies EXIF-based rotation correction.
     *
     * @param path Absolute path to the image file.
     * @param reqWidth Target maximum width in pixels.
     * @param reqHeight Target maximum height in pixels.
     * @return A decoded and EXIF-rotated bitmap, or null if decoding fails or OOM occurs.
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
            Log.e(TAG, "OOM decoding image: $path")
            null
        } catch (e: Exception) { 
            Log.e(TAG, "Error decoding: ${e.message}")
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

    /**
     * Saves a bitmap to disk as a JPEG with 90% quality.
     *
     * @param bitmap The bitmap to persist.
     * @param folder Destination directory (must exist).
     * @param prefix Filename prefix; the resulting file will be named `{prefix}.jpg`.
     * @return Absolute path to the saved file, or null on IO failure.
     */
    fun saveBitmapToDisk(bitmap: Bitmap, folder: File, prefix: String): String? {
        return try {
            val file = File(folder, "$prefix.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            file.absolutePath
        } catch (e: Exception) { 
            Log.e(TAG, "Error saving: ${e.message}")
            null 
        }
    }

    /**
     * Generates a deterministic filename for images downloaded from cloud storage.
     *
     * Uses the cloud asset identifier and a sequential index to produce a
     * reproducible name, ensuring idempotent downloads and cache validation.
     *
     * @param cloudId The cloud asset identifier (e.g. storage bucket key).
     * @param index Zero-based positional index within the batch.
     * @return A filename following the pattern `upload_512_cloud_{cloudId}_{index}.jpg`.
     */
    fun getCloudImageName(cloudId: String, index: Int): String {
        return "upload_512_cloud_${cloudId}_${index}.jpg"
    }
}
