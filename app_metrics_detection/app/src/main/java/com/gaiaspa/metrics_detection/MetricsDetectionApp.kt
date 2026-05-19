package com.gaiaspa.metrics_detection

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.gaiaspa.metrics_detection.network.TokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Application class responsible for atomic model extraction on startup.
 *
 * ONNX models and optional `.data` weight files are extracted from the APK assets
 * to the internal files directory (`filesDir/weights/`). Extraction uses a
 * temp-file + rename pattern to prevent the ML pipeline from reading
 * partially-written files in case of a crash or kill during extraction.
 *
 * Model files already present on disk are skipped (idempotent). If a file is
 * corrupt the user must reinstall to trigger a fresh extraction.
 *
 * @see TokenProvider Initialised before model extraction so networking
 *        is ready if needed later in the pipeline lifecycle.
 */
class MetricsDetectionApp : Application() {

    override fun onCreate() {
        super.onCreate()
        applyDefaultLightMode()
        TokenProvider.init(this)

        // Asynchronous extraction on IO dispatcher to avoid blocking the main thread.
        CoroutineScope(Dispatchers.IO).launch {
            prepareModels()
        }
    }

    private fun applyDefaultLightMode() {
        val isDark = getSharedPreferences("dark_mode", MODE_PRIVATE)
            .getBoolean("enabled", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /**
     * Iterates over the known ONNX model asset paths and extracts each one
     * (plus any optional companion `.data` weight file) to internal storage.
     */
    private fun prepareModels() {
        val models = listOf(
            "weights/modelos/legacy/seg_best.onnx",
            "weights/modelos/qty_model_rgbdt.onnx",
            "weights/modelos/hist_rgbdt_bimodal.onnx"
        )

        models.forEach { assetPath ->
            extractAssetAtomic(assetPath)
            // Optional external weights bundled alongside the ONNX model.
            extractAssetAtomic("$assetPath.data")
        }
    }

    /**
     * Extracts a single file from APK assets to internal storage atomically.
     *
     * Writes to a `.tmp` file first, then renames to the final name. This
     * guarantees the target file is either fully written or absent — the ML
     * pipeline will never see a truncated file.
     *
     * Skips extraction if the target file already exists and is non-empty
     * (idempotent). Exceptions for optional `.data` files are silently caught;
     * failures for mandatory ONNX files will cause pipeline errors at runtime.
     */
    private fun extractAssetAtomic(assetPath: String) {
        val fileName = File(assetPath).name
        val weightsDir = File(filesDir, "weights").apply { mkdirs() }
        val targetFile = File(weightsDir, fileName)

        // Idempotent: skip if file already extracted successfully.
        if (targetFile.exists() && targetFile.length() > 0) return

        val tempFile = File(weightsDir, "$fileName.tmp")
        try {
            assets.open(assetPath).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            // Atomic rename: the file is only visible once fully written.
            if (tempFile.renameTo(targetFile)) {
                Log.d("App", "Modelo listo: $fileName")
            }
        } catch (e: Exception) {
            // Expected for optional .data files that don't exist in assets.
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
