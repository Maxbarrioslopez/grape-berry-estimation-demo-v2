package com.gaiaspa.metrics_detection

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.gaiaspa.metrics_detection.ml.MetricsPipeline
import com.gaiaspa.metrics_detection.ml.RuntimeVarietyCatalog
import com.gaiaspa.metrics_detection.ml.Success
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/**
 * Forensic batch processor for offline data-only analysis.
 *
 * Walks an input directory of images, runs each through MetricsPipeline,
 * and writes structured JSON output (qty, calibre, histogram) without
 * saving visual overlay evidence. Used for internal benchmarking and
 * ground-truth comparison (CSV manifest).
 *
 * Not exposed to external apps (exported=false in manifest).
 */
class BatchProcessor(
    private val context: Context,
    private val providerPreference: String = MetricsPipeline.DEFAULT_PROVIDER
) {

    /**
     * Summary statistics produced at the end of a batch run.
     *
     * @property runId Unique run identifier (timestamp-based).
     * @property manifestPath Absolute path to the JSON manifest file.
     * @property totalImagesFound Total image files discovered in the input tree.
     * @property processedOk Number of files that completed without error.
     * @property processedError Number of files that failed during processing.
     * @property totalElapsedMs Wall-clock time for the entire batch run.
     */
    data class BatchRunSummary(
        val runId: String,
        val manifestPath: String,
        val totalImagesFound: Int,
        val processedOk: Int,
        val processedError: Int,
        val totalElapsedMs: Long
    )

    private data class PipelineOutcome(
        val success: Success? = null,
        val errorMessage: String? = null,
        val nativeJson: JSONObject? = null
    )

    private data class ManifestEntry(
        val relativePath: String,
        val jsonPath: String,
        val status: String
    )

    companion object {
        const val TAG = "BATCH"
        const val DEFAULT_INPUT_DIR = "/sdcard/Download/opt_uvas_input"
        const val DEFAULT_OUTPUT_DIR = "/sdcard/Download/opt_uvas_output"
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "bmp", "webp")
        private val RUN_ID_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val BIN_LABELS = (7..32).map { it.toString() }
    }

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val pipelineVersion = "4.9/Forense-SoloDatos"
    private var gtMap: Map<String, Double> = emptyMap()

    /**
     * Executes a full forensic batch run.
     *
     * Walks the input directory recursively, processes every supported image file
     * through [MetricsPipeline.invokeFromFile], and writes per-file JSON results
     * plus a run-level manifest to the output directory.
     *
     * Ground-truth comparison is performed if a `manifest_subsample.csv` file is
     * present in the parent of the input root (column: `grape_count_total`).
     *
     * Bitmap resources are explicitly recycled after each file and the GC is
     * triggered every 10 files to keep memory pressure low on large batches.
     *
     * @param inputDirPath Root directory containing input images (recursive).
     * @param outputDirPath Root directory where a `run_<timestamp>/` folder will be created.
     * @return A [BatchRunSummary] with counts, paths, and elapsed time.
     */
    suspend fun run(
        inputDirPath: String = DEFAULT_INPUT_DIR,
        outputDirPath: String = DEFAULT_OUTPUT_DIR
    ): BatchRunSummary {
        val runStartedAt = SystemClock.elapsedRealtime()
        val runId = "run_${RUN_ID_FORMAT.format(Date())}"
        val inputRoot = resolveInputRoot(File(inputDirPath).absoluteFile)
        val outputRoot = File(outputDirPath).absoluteFile

        ensureDirectory(outputRoot)
        val runRoot = File(outputRoot, runId)
        ensureDirectory(runRoot)

        loadGroundTruth(inputRoot)

        Log.d(TAG, "Batch start runId=$runId | Version=$pipelineVersion")

        if (!inputRoot.exists() || !inputRoot.isDirectory) {
            return BatchRunSummary(runId, "", 0, 0, 0, 0)
        }

        val imageFiles = inputRoot.walkTopDown()
            .filter { it.isFile && IMAGE_EXTENSIONS.contains(it.extension.lowercase(Locale.US)) }
            .sortedBy { it.relativeTo(inputRoot).path }
            .toList()

        val manifestEntries = mutableListOf<ManifestEntry>()
        var processedOk = 0
        var processedError = 0

        val pipeline = MetricsPipeline(context = appContext, providerPreference = providerPreference) { Log.d(TAG, it) }

        try {
            imageFiles.forEachIndexed { index, imageFile ->
                val relativePath = imageFile.relativeTo(inputRoot).path.replace(File.separatorChar, '/')
                val varietyId = inferVarietyId(relativePath)
                val fileStartedAt = SystemClock.elapsedRealtime()
                
                Log.d(TAG, "Forense Processing [${index + 1}/${imageFiles.size}] File: ${imageFile.name}")
                
                val pipelineOutcome = invokePipeline(pipeline, imageFile, varietyId)
                
                val outputJsonFile = File(runRoot, toJsonRelativePath(relativePath))
                ensureDirectory(outputJsonFile.parentFile)

                // --- REMOVED: VISUAL EVIDENCE RESCUE (DATA ONLY) ---
                // Not calling rescuFile for jpg/png images

                val fileJson = buildFileJson(
                    runId = runId,
                    inputRootPath = inputRoot.absolutePath,
                    imageFile = imageFile,
                    relativePath = relativePath,
                    totalMs = SystemClock.elapsedRealtime() - fileStartedAt,
                    outcome = pipelineOutcome
                )
                
                val predQty = fileJson.getJSONObject("result").optDouble("qty_total", 0.0)
                val fileName = imageFile.name.lowercase()
                gtMap[fileName]?.let { gtQty ->
                    val err = predQty - gtQty
                    Log.i(TAG, "[GT-CHECK] File: $fileName | PRED: $predQty | GT: $gtQty | ERR: ${String.format("%.1f", err)}")
                }

                outputJsonFile.writeText(fileJson.toString(2))

                if (fileJson.getString("status") == "ok") processedOk++ else processedError++
                manifestEntries += ManifestEntry(relativePath, outputJsonFile.relativeTo(runRoot).path, fileJson.getString("status"))
                
                pipelineOutcome.success?.let { success ->
                    success.imageOrig?.recycle()
                    success.imagePro.first?.recycle()
                    success.imagePro.second?.recycle()
                }
                
                cleanupPipelineCacheArtifacts()
                if (index % 10 == 0) System.gc()
                delay(50)
            }
        } finally {
            pipeline.close()
        }

        val manifestPath = writeManifest(runId, Instant.now().toString(), inputRoot.absolutePath, outputRoot.absolutePath, runRoot, manifestEntries, processedOk, processedError, imageFiles.size, SystemClock.elapsedRealtime() - runStartedAt)
        return BatchRunSummary(runId, manifestPath, imageFiles.size, processedOk, processedError, SystemClock.elapsedRealtime() - runStartedAt)
    }

    private fun loadGroundTruth(root: File) {
        val csvFile = File(root.parentFile, "manifest_subsample.csv")
        if (!csvFile.exists()) return
        try {
            val lines = csvFile.readLines()
            if (lines.isEmpty()) return
            val headers = lines[0].split(",")
            val pathIdx = headers.indexOf("subsample_output_relpath")
            val countIdx = headers.indexOf("grape_count_total")
            if (pathIdx != -1 && countIdx != -1) {
                gtMap = lines.drop(1).mapNotNull { line ->
                    val parts = line.split(",")
                    if (parts.size > pathIdx && parts.size > countIdx) {
                        val name = File(parts[pathIdx]).name.lowercase()
                        val count = parts[countIdx].toDoubleOrNull() ?: 0.0
                        name to count
                    } else null
                }.toMap()
            }
        } catch (e: Exception) {}
    }

    private suspend fun invokePipeline(pipeline: MetricsPipeline, imageFile: File, varietyId: Int?): PipelineOutcome {
        return suspendCancellableCoroutine { continuation ->
            try {
                pipeline.invokeFromFile(imagePath = imageFile.absolutePath, smoothEdges = true, varietyId = varietyId,
                    onSuccess = { success ->
                        val nativeJson = try { success.rawJson?.let { JSONObject(it) } } catch (e: Exception) { null }
                        continuation.resume(PipelineOutcome(success = success, nativeJson = nativeJson))
                    },
                    onFailure = { error -> continuation.resume(PipelineOutcome(errorMessage = error)) }
                )
            } catch (t: Throwable) { continuation.resume(PipelineOutcome(errorMessage = t.message)) }
        }
    }

    private fun buildFileJson(runId: String, inputRootPath: String, imageFile: File, relativePath: String, totalMs: Long, outcome: PipelineOutcome): JSONObject {
        val native = outcome.nativeJson
        val status = if (outcome.errorMessage == null && (native == null || native.optBoolean("status", true))) "ok" else "error"

        val json = JSONObject()
        json.put("run_id", runId)
        json.put("status", status)
        json.put("seg_count_base", native?.optInt("seg_count_base", 0))
        
        json.put("source", JSONObject().apply {
            put("filename", imageFile.name)
            put("relative_path", relativePath)
            // No longer linking visual evidence filenames
        })

        json.put("result", JSONObject().apply {
            put("qty_total", native?.optDouble("count_total", 0.0))
            put("variety_name", native?.optString("variety", "UNK"))
            // Added mean, mode, and std to the output JSON
            put("mean", native?.optDouble("mean", 0.0))
            put("mode", native?.optDouble("mode", 0.0))
            put("std", native?.optDouble("std", 0.0))
        })

        json.put("detections", native?.optJSONArray("detections") ?: JSONArray())

        json.put("segmentation", JSONObject().apply {
            put("num_grape_det", native?.optInt("num_grape_det", 0))
            put("num_pingpong_det", native?.optInt("num_pingpong_det", 0))
        })

        val exifObj = JSONObject()
        try {
            val exif = ExifInterface(imageFile.absolutePath)
            exifObj.put("iso", exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS))
            exifObj.put("exposure", exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
        } catch (e: Exception) {}
        json.put("exif", exifObj)
        json.put("inference_ms", native?.optLong("inf_ms", 0))
        json.put("throttling_warning", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE else false)

        json.put("histogram", JSONObject().apply {
            put("hist_prob", native?.optJSONArray("hist_prob") ?: JSONArray())
            put("pred", native?.optJSONArray("pred") ?: JSONArray())
            put("bins", native?.optJSONArray("bins") ?: JSONArray())
        })

        json.put("error", outcome.errorMessage ?: JSONObject.NULL)
        return json
    }

    /** Extracts the variety ID from the first path segment (folder name convention). */
    private fun inferVarietyId(path: String): Int? = RuntimeVarietyCatalog.idOrNull(path.substringBefore('/', ""))
    /** If the root contains an `images/` subdirectory, use it as the scan root. */
    private fun resolveInputRoot(root: File): File = if (File(root, "images").exists()) File(root, "images") else root
    private fun ensureDirectory(dir: File?) { if (dir != null && !dir.exists()) dir.mkdirs() }
    /** Replaces the file extension with `.json` for output file naming. */
    private fun toJsonRelativePath(path: String): String = "${path.substringBeforeLast('.')}.json"

    private fun writeManifest(runId: String, ts: String, input: String, output: String, runRoot: File, files: List<ManifestEntry>, ok: Int, err: Int, total: Int, ms: Long): String {
        val file = File(runRoot, "manifest.json")
        val json = JSONObject().put("run_id", runId).put("total_images_found", total).put("processed_ok", ok).put("processed_error", err).put("files", JSONArray().apply { files.forEach { put(JSONObject().put("relative_path", it.relativePath).put("json_path", it.jsonPath).put("status", it.status)) } })
        file.writeText(json.toString(2))
        return file.absolutePath
    }

    private fun cleanupPipelineCacheArtifacts() {
        appContext.cacheDir.listFiles()?.filter { it.name.contains("last") || it.name.startsWith("jni_") }?.forEach { it.delete() }
    }
}
