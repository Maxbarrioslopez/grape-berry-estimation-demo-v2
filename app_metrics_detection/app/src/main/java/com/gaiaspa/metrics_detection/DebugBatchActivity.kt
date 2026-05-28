package com.gaiaspa.metrics_detection

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gaiaspa.metrics_detection.ml.MetricsPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only Activity that launches a [BatchProcessor] run and displays its
 * result in a simple text view.
 *
 * Accepts optional intent extras to override input/output directories and the
 * ONNX execution provider. On Android 11+ (API 30+), verifies that the app has
 * the `MANAGE_EXTERNAL_STORAGE` permission before touching shared-storage paths.
 *
 * Not exported in the manifest — intended for internal developer use only.
 */
class DebugBatchActivity : AppCompatActivity() {

    companion object {
        /** Intent extra: absolute path to the input image directory. */
        const val EXTRA_INPUT_DIR = "inputDir"
        /** Intent extra: absolute path to the output directory for JSON results. */
        const val EXTRA_OUTPUT_DIR = "outputDir"
        /** Intent extra: ONNX execution provider preference string. */
        const val EXTRA_PROVIDER = "provider"
    }

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            text = getString(R.string.processing)
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
        setContentView(statusView)

        val inputDir = intent.getStringExtra(EXTRA_INPUT_DIR)
            ?.takeIf { it.isNotBlank() }
            ?: BatchProcessor.DEFAULT_INPUT_DIR
        val outputDir = intent.getStringExtra(EXTRA_OUTPUT_DIR)
            ?.takeIf { it.isNotBlank() }
            ?: BatchProcessor.DEFAULT_OUTPUT_DIR
        val provider = intent.getStringExtra(EXTRA_PROVIDER)
            ?.takeIf { it.isNotBlank() }
            ?: MetricsPipeline.DEFAULT_PROVIDER

        Log.d(
            BatchProcessor.TAG,
            "DebugBatchActivity start requestedInput=${java.io.File(inputDir).absolutePath} requestedOutput=${java.io.File(outputDir).absolutePath} provider=$provider"
        )
        statusView.text = getString(R.string.processing)

        lifecycleScope.launch {
            if (!hasRequiredStorageAccess(inputDir, outputDir)) {
                val message = getString(R.string.connection_error)
                Log.e(BatchProcessor.TAG, message)
                statusView.text = message
                return@launch
            }

            try {
                val summary = withContext(Dispatchers.IO) {
                    BatchProcessor(
                        context = applicationContext,
                        providerPreference = provider
                    ).run(
                        inputDirPath = inputDir,
                        outputDirPath = outputDir
                    )
                }
                statusView.text = getString(R.string.batch_saved_locally)
                Log.d(
                    BatchProcessor.TAG,
                    "DebugBatchActivity end runId=${summary.runId} ok=${summary.processedOk} error=${summary.processedError} manifest=${summary.manifestPath}"
                )
            } catch (t: Throwable) {
                val message = t.message ?: getString(R.string.batch_save_error)
                statusView.text = getString(R.string.batch_save_error)
                Log.e(BatchProcessor.TAG, "DebugBatchActivity failure", t)
            }
            // Removed finish() so the user can see the result on screen
        }
    }

    private fun hasRequiredStorageAccess(inputDir: String, outputDir: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true

        val touchesSharedStorage = isSharedStoragePath(inputDir) || isSharedStoragePath(outputDir)
        if (!touchesSharedStorage) return true

        return Environment.isExternalStorageManager()
    }

    private fun isSharedStoragePath(path: String): Boolean {
        return path.startsWith("/sdcard/") || path == "/sdcard" || path.startsWith("/storage/")
    }
}
