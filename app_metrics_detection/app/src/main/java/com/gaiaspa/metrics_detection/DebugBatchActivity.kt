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

class DebugBatchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INPUT_DIR = "inputDir"
        const val EXTRA_OUTPUT_DIR = "outputDir"
        const val EXTRA_PROVIDER = "provider"
    }

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            text = "Starting debug batch..."
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
        statusView.text = "Running debug batch...\ninput=${java.io.File(inputDir).absolutePath}\noutput=${java.io.File(outputDir).absolutePath}\nprovider=$provider"

        lifecycleScope.launch {
            if (!hasRequiredStorageAccess(inputDir, outputDir)) {
                val message = "Missing shared-storage access for debug batch."
                Log.e(BatchProcessor.TAG, message)
                statusView.text = message
                finish()
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
                statusView.text = "Batch finished.\nrunId=${summary.runId}\nok=${summary.processedOk}\nerror=${summary.processedError}"
                Log.d(
                    BatchProcessor.TAG,
                    "DebugBatchActivity end runId=${summary.runId} ok=${summary.processedOk} error=${summary.processedError} manifest=${summary.manifestPath}"
                )
            } catch (t: Throwable) {
                val message = t.message ?: "Debug batch failed"
                statusView.text = "Batch failed.\n$message"
                Log.e(BatchProcessor.TAG, "DebugBatchActivity failure", t)
            } finally {
                finish()
            }
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
