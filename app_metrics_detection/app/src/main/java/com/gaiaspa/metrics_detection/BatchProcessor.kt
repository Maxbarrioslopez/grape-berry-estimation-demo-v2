package com.gaiaspa.metrics_detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.gaiaspa.metrics_detection.ml.MetricsPipeline
import com.gaiaspa.metrics_detection.ml.RuntimeVarietyCatalog
import com.gaiaspa.metrics_detection.ml.Success
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

class BatchProcessor(
    private val context: Context,
    private val providerPreference: String = MetricsPipeline.DEFAULT_PROVIDER
) {

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

        private const val SEG_MODEL_ASSET = "weights/seg_best.onnx"
        private const val REG_MODEL_ASSET = "weights/best_model_5ch_residual.onnx"
        private const val MODEL_INPUT_SIZE = 512
        private const val SCORE_THRESHOLD = 0.25
        private const val MASK_THRESHOLD = 0.25

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "bmp", "webp")
        private val RUN_ID_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private val appContext = context.applicationContext
    private val pipelineVersion = "${BuildConfig.VERSION_NAME}/MetricsPipeline-JNI"

    suspend fun run(
        inputDirPath: String = DEFAULT_INPUT_DIR,
        outputDirPath: String = DEFAULT_OUTPUT_DIR
    ): BatchRunSummary {
        val runStartedAt = SystemClock.elapsedRealtime()
        val runId = "run_${RUN_ID_FORMAT.format(Date())}"
        val requestedInputRoot = File(inputDirPath).absoluteFile
        val inputRoot = resolveInputRoot(requestedInputRoot)
        val outputRoot = File(outputDirPath).absoluteFile

        ensureDirectory(outputRoot)
        val runRoot = File(outputRoot, runId)
        ensureDirectory(runRoot)

        Log.d(
            TAG,
            "Batch start runId=$runId requestedInput=${requestedInputRoot.absolutePath} resolvedInput=${inputRoot.absolutePath} outputRoot=${outputRoot.absolutePath} runRoot=${runRoot.absolutePath} provider=$providerPreference"
        )

        if (!inputRoot.exists() || !inputRoot.isDirectory) {
            Log.e(
                TAG,
                "Input folder missing or not a directory. requestedInput=${requestedInputRoot.absolutePath} resolvedInput=${inputRoot.absolutePath} outputRoot=${outputRoot.absolutePath}"
            )
            val manifestPath = writeManifest(
                runId = runId,
                timestampUtc = Instant.now().toString(),
                inputRoot = inputRoot.absolutePath,
                outputRoot = outputRoot.absolutePath,
                runRoot = runRoot,
                files = emptyList(),
                processedOk = 0,
                processedError = 0,
                totalImagesFound = 0,
                totalElapsedMs = SystemClock.elapsedRealtime() - runStartedAt
            )
            Log.d(TAG, "Manifest written: $manifestPath")
            Log.d(TAG, "Batch end runId=$runId ok=0 error=0 totalImages=0")
            return BatchRunSummary(
                runId = runId,
                manifestPath = manifestPath,
                totalImagesFound = 0,
                processedOk = 0,
                processedError = 0,
                totalElapsedMs = SystemClock.elapsedRealtime() - runStartedAt
            )
        }

        val imageFiles = inputRoot
            .walkTopDown()
            .filter { it.isFile && IMAGE_EXTENSIONS.contains(it.extension.lowercase(Locale.US)) }
            .sortedBy { it.relativeTo(inputRoot).path.replace(File.separatorChar, '/') }
            .toList()

        Log.d(
            TAG,
            "Input folder found resolvedInput=${inputRoot.absolutePath} outputRoot=${outputRoot.absolutePath} imagesDiscovered=${imageFiles.size} provider=$providerPreference"
        )

        val manifestEntries = mutableListOf<ManifestEntry>()
        var processedOk = 0
        var processedError = 0

        val pipeline = MetricsPipeline(
            context = appContext,
            providerPreference = providerPreference
        ) { msg ->
            Log.d(TAG, msg)
        }

        try {
            imageFiles.forEach { imageFile ->
                val relativePath = imageFile.relativeTo(inputRoot).path.replace(File.separatorChar, '/')
                val varietyId = inferVarietyId(relativePath)
                Log.d(
                    TAG,
                    "File start source=${imageFile.absolutePath} relativePath=$relativePath outputRoot=${runRoot.absolutePath}"
                )

                val fileStartedAt = SystemClock.elapsedRealtime()
                var decodeMs = 0L
                var pipelineMs = 0L
                var decodeBitmap: Bitmap? = null
                var pipelineOutcome = PipelineOutcome()
                val warnings = mutableListOf<String>()

                try {
                    val decodeStartedAt = SystemClock.elapsedRealtime()
                    decodeBitmap = decodeBitmap(imageFile)
                    decodeMs = SystemClock.elapsedRealtime() - decodeStartedAt

                    if (decodeBitmap == null) {
                        pipelineOutcome = PipelineOutcome(errorMessage = "BitmapFactory.decodeFile returned null")
                    } else {
                        if (varietyId == null) {
                            warnings += "Variety id could not be derived from the relative folder. The pipeline fell back to filename inference when possible."
                        }

                        val pipelineStartedAt = SystemClock.elapsedRealtime()
                        pipelineOutcome = invokePipeline(
                            pipeline = pipeline,
                            imageFile = imageFile,
                            bitmap = decodeBitmap!!,
                            varietyId = varietyId
                        )
                        pipelineMs = SystemClock.elapsedRealtime() - pipelineStartedAt
                    }
                } catch (t: Throwable) {
                    pipelineOutcome = PipelineOutcome(errorMessage = t.message ?: "Unexpected batch failure")
                    Log.e(TAG, "File error relativePath=$relativePath", t)
                }

                warnings += buildWarnings(
                    nativeJson = pipelineOutcome.nativeJson,
                    varietyId = varietyId,
                    errorMessage = pipelineOutcome.errorMessage
                )

                val outputJsonFile = File(runRoot, toJsonRelativePath(relativePath))
                ensureDirectory(outputJsonFile.parentFile)

                val initialTotalMs = SystemClock.elapsedRealtime() - fileStartedAt
                val fileJson = buildFileJson(
                    runId = runId,
                    timestampUtc = Instant.now().toString(),
                    inputRootPath = inputRoot.absolutePath,
                    sourceFile = imageFile,
                    relativePath = relativePath,
                    decodeBitmap = decodeBitmap,
                    decodeMs = decodeMs,
                    pipelineMs = pipelineMs,
                    jsonWriteMs = 0L,
                    totalMs = initialTotalMs,
                    warnings = warnings.distinct(),
                    outcome = pipelineOutcome
                )

                var jsonWriteMs = 0L
                try {
                    val jsonWriteStartedAt = SystemClock.elapsedRealtime()
                    outputJsonFile.writeText(fileJson.toString(2))
                    jsonWriteMs = SystemClock.elapsedRealtime() - jsonWriteStartedAt
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed writing JSON relativePath=$relativePath", t)
                    throw t
                }

                val finalTotalMs = SystemClock.elapsedRealtime() - fileStartedAt
                fileJson.getJSONObject("timing_ms").put("json_write", jsonWriteMs)
                fileJson.getJSONObject("timing_ms").put("total", finalTotalMs)
                outputJsonFile.writeText(fileJson.toString(2))

                val status = fileJson.getString("status")
                if (status == "ok") {
                    processedOk += 1
                    Log.d(TAG, "File success relativePath=$relativePath json=${outputJsonFile.absolutePath}")
                } else {
                    processedError += 1
                    Log.e(TAG, "File error relativePath=$relativePath json=${outputJsonFile.absolutePath}")
                }

                manifestEntries += ManifestEntry(
                    relativePath = relativePath,
                    jsonPath = outputJsonFile.relativeTo(runRoot).path.replace(File.separatorChar, '/'),
                    status = status
                )

                recycleQuietly(decodeBitmap)
                recycleQuietly(pipelineOutcome.success?.imagePro?.second)
                cleanupPipelineCacheArtifacts()
            }
        } finally {
            pipeline.close()
        }

        val manifestPath = writeManifest(
            runId = runId,
            timestampUtc = Instant.now().toString(),
            inputRoot = inputRoot.absolutePath,
            outputRoot = outputRoot.absolutePath,
            runRoot = runRoot,
            files = manifestEntries,
            processedOk = processedOk,
            processedError = processedError,
            totalImagesFound = imageFiles.size,
            totalElapsedMs = SystemClock.elapsedRealtime() - runStartedAt
        )

        Log.d(TAG, "Manifest written: $manifestPath")
        Log.d(
            TAG,
            "Batch end runId=$runId ok=$processedOk error=$processedError totalImages=${imageFiles.size}"
        )

        return BatchRunSummary(
            runId = runId,
            manifestPath = manifestPath,
            totalImagesFound = imageFiles.size,
            processedOk = processedOk,
            processedError = processedError,
            totalElapsedMs = SystemClock.elapsedRealtime() - runStartedAt
        )
    }

    private suspend fun invokePipeline(
        pipeline: MetricsPipeline,
        imageFile: File,
        bitmap: Bitmap,
        varietyId: Int?
    ): PipelineOutcome {
        val debugFile = File(appContext.cacheDir, "debug_kotlin.json")
        debugFile.delete()

        val callbackOutcome = suspendCancellableCoroutine<PipelineOutcome> { continuation ->
            try {
                pipeline.invokeFile(
                    imagePath = imageFile.absolutePath,
                    inputFrame = bitmap,
                    smoothEdges = true,
                    varietyId = varietyId,
                    useDepth = false,
                    onSuccess = { success ->
                        if (continuation.isActive) {
                            continuation.resume(PipelineOutcome(success = success))
                        }
                    },
                    onFailure = { error ->
                        if (continuation.isActive) {
                            continuation.resume(PipelineOutcome(errorMessage = error))
                        }
                    }
                )
            } catch (t: Throwable) {
                if (continuation.isActive) {
                    continuation.resume(
                        PipelineOutcome(errorMessage = t.message ?: "Pipeline invocation failed")
                    )
                }
            }
        }

        val nativeJson = readNativeJson(debugFile)
        return callbackOutcome.copy(
            errorMessage = callbackOutcome.errorMessage ?: nativeJson?.optStringOrNull("error"),
            nativeJson = nativeJson
        )
    }

    private fun buildFileJson(
        runId: String,
        timestampUtc: String,
        inputRootPath: String,
        sourceFile: File,
        relativePath: String,
        decodeBitmap: Bitmap?,
        decodeMs: Long,
        pipelineMs: Long,
        jsonWriteMs: Long,
        totalMs: Long,
        warnings: List<String>,
        outcome: PipelineOutcome
    ): JSONObject {
        val nativeJson = outcome.nativeJson
        val predict = outcome.success?.predictsList?.firstOrNull()

        val rawHistogram = nativeJson.optDoubleListOrNull("hist_counts_float")
        val histogramCounts = nativeJson.optIntListOrNull("pred") ?: predict?.pred
        val histogramBins = nativeJson.optIntListOrNull("bins")
            ?: predict?.bins?.map { it.toInt() }
        val rawCountTotal = nativeJson.optDoubleOrNull("count_total_raw")
        val totalCount = nativeJson.optIntOrNull("count_total") ?: predict?.qty
        val varietyName = nativeJson.optStringOrNull("variety")
            ?: predict?.bunchColor?.takeIf { it.isNotBlank() }
            ?: inferVarietyName(relativePath)
        val varietyIndex = nativeJson.optIntOrNull("variety_idx")
            ?: inferVarietyId(relativePath)
            ?: RuntimeVarietyCatalog.idOrNull(varietyName)
        val grapeDetections = nativeJson.optIntOrNull("num_grape_det")
        val mmPerPx = nativeJson.optDoubleOrNull("mm_per_px")

        val status = when {
            !outcome.errorMessage.isNullOrBlank() -> "error"
            nativeJson != null && nativeJson.optBoolean("status", false).not() -> "error"
            else -> "ok"
        }
        val errorMessage = outcome.errorMessage
            ?: nativeJson.optStringOrNull("error")
                ?.takeIf { status == "error" }

        return JSONObject()
            .put("run_id", runId)
            .put("timestamp_utc", timestampUtc)
            .put("pipeline_version", pipelineVersion)
            .put("status", status)
            .put("warnings", toJsonArray(warnings))
            .put(
                "source",
                JSONObject()
                    .put("input_root", inputRootPath)
                    .put("relative_path", relativePath)
                    .put("folder", relativeFolder(relativePath))
                    .put("filename", sourceFile.name)
                    .put("basename", sourceFile.nameWithoutExtension)
                    .put("extension", sourceFile.extension.lowercase(Locale.US))
                    .put("size_bytes", sourceFile.length())
            )
            .put(
                "timing_ms",
                JSONObject()
                    .put("decode", decodeMs)
                    .put("pipeline_total", pipelineMs)
                    .put("json_write", jsonWriteMs)
                    .put("total", totalMs)
            )
            .put(
                "result",
                JSONObject()
                    .putNullable("qty_total", totalCount)
                    .putNullable("qty_raw_model", rawCountTotal)
                    .putNullable("qty_after_bucket_shrink", null)
                    .putNullable("qty_final", totalCount)
                    .putNullable("variety_index", varietyIndex)
                    .putNullable("variety_name", varietyName)
                    .putNullable("caliber_code", null)
                    .putNullable("caliber_label", null)
            )
            .put(
                "histogram",
                JSONObject()
                    .putNullable("bin_edges", null)
                    .putNullable("bin_labels", histogramBins?.map { it.toString() }?.let(::toJsonArray))
                    .putNullable("raw", rawHistogram?.let(::toJsonArray))
                    .putNullable("clamped", null)
                    .putNullable("final", histogramCounts?.let(::toJsonArray))
                    .putNullable("counts", histogramCounts?.let(::toJsonArray))
                    .putNullable("sum", null)
                    .putNullable("non_zero_bins", null)
                    .putNullable("dominant_bin_index", null)
                    .putNullable("dominant_bin_label", null)
            )
            .put(
                "segmentation",
                JSONObject()
                    .put("image_width", decodeBitmap?.width ?: 0)
                    .put("image_height", decodeBitmap?.height ?: 0)
                    .put("model_input_width", MODEL_INPUT_SIZE)
                    .put("model_input_height", MODEL_INPUT_SIZE)
                    .putNullable("instances_detected_raw", null)
                    .putNullable("instances_after_threshold", null)
                    .putNullable("instances_after_nms", null)
                    .putNullable("instances_selected_final", grapeDetections)
                    .put("score_threshold", SCORE_THRESHOLD)
                    .put("mask_threshold", MASK_THRESHOLD)
                    .putNullable("nms_iou_threshold", null)
            )
            .put(
                "selection",
                JSONObject()
                    .putNullable("candidate_count", null)
                    .putNullable("selected_count", null)
                    .putNullable("rejected_count", null)
                    .putNullable("rejection_reasons", null)
            )
            .put(
                "features",
                JSONObject()
                    .putNullable("cluster", null)
                    .putNullable("grapes", null)
                    .put(
                        "scale",
                        JSONObject()
                            .putNullable("mm_per_px", mmPerPx)
                            .putNullable("reference_detected", null)
                    )
                    .putNullable("raw_features", null)
                    .putNullable("scaled_features", null)
            )
            .put(
                "model_debug",
                JSONObject()
                    .put("segmentation_model", SEG_MODEL_ASSET)
                    .put("regression_model", REG_MODEL_ASSET)
                    .putNullable("provider", nativeJson.optStringOrNull("provider"))
                    .putNullable("input_tensor_shape", nativeJson.optIntListOrNull("input_tensor_shape")?.let(::toJsonArray))
                    .putNullable("raw_outputs_present", nativeJson.optBooleanOrNull("raw_outputs_present"))
            )
            .put(
                "artifacts",
                JSONObject()
                    .putNullable("overlay_image", null)
                    .putNullable("mask_image", null)
                    .putNullable("crop_image", null)
            )
            .putNullable("error", errorMessage)
    }

    private fun buildWarnings(
        nativeJson: JSONObject?,
        varietyId: Int?,
        errorMessage: String?
    ): List<String> {
        val warnings = mutableListOf<String>()
        if (nativeJson.optDoubleOrNull("count_total_raw") == null) {
            warnings += "Current pipeline does not expose raw count_total; qty_raw_model was written as null."
        }
        warnings += "qty_after_bucket_shrink is not exposed by the current runtime; that field was written as null."
        if (nativeJson.optDoubleListOrNull("hist_counts_float") == null) {
            warnings += "Current pipeline does not expose the raw float histogram; histogram.raw was written as null."
        }
        warnings += "Histogram bin edges and summary fields like sum, non_zero_bins, and dominant_bin_* are not directly exposed; those fields were written as null."
        warnings += "The JNI bridge only exposes the final grape detection count; earlier segmentation counters and nms_iou_threshold were written as null."
        warnings += "Selection details, caliber summary fields, and raw/scaled feature tables are not directly exposed by the current pipeline; those fields were written as null."
        if (nativeJson.optStringOrNull("provider") == null ||
            nativeJson.optIntListOrNull("input_tensor_shape") == null ||
            nativeJson.optBooleanOrNull("raw_outputs_present") == null
        ) {
            warnings += "Some runtime debug fields are not directly exposed by the current MetricsPipeline bridge; missing fields were written as null."
        }
        warnings += "reference_detected is not exposed by the current runtime; that field was written as null."
        warnings += "Artifacts are not exported by this batch runner; overlay_image, mask_image, and crop_image were written as null."
        if (varietyId == null) {
            warnings += "Variety id could not be derived from the relative folder. If filename inference also fails, variety fields may stay null."
        }
        if (nativeJson.optDoubleOrNull("mm_per_px") == null) {
            warnings += "Current native pipeline does not expose mm_per_px; that field was written as null."
        }
        if (!errorMessage.isNullOrBlank()) {
            warnings += "Processing ended with error: $errorMessage"
        }
        return warnings.distinct()
    }

    private fun inferVarietyId(relativePath: String): Int? {
        val topLevelFolder = relativePath.substringBefore('/').takeIf { it.isNotBlank() } ?: return null
        return RuntimeVarietyCatalog.idOrNull(topLevelFolder)
    }

    private fun inferVarietyName(relativePath: String): String? {
        val varietyId = inferVarietyId(relativePath) ?: return null
        return RuntimeVarietyCatalog.nameOrNull(varietyId)
    }

    private fun resolveInputRoot(requestedInputRoot: File): File {
        if (!requestedInputRoot.exists() || !requestedInputRoot.isDirectory) {
            return requestedInputRoot
        }
        if (requestedInputRoot.name.equals("images", ignoreCase = true)) {
            return requestedInputRoot
        }

        val imagesSubdir = File(requestedInputRoot, "images")
        return if (imagesSubdir.exists() && imagesSubdir.isDirectory) {
            imagesSubdir
        } else {
            requestedInputRoot
        }
    }

    private fun decodeBitmap(imageFile: File): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(imageFile.absolutePath, options) ?: return null
        return if (decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            val copied = decoded.copy(Bitmap.Config.ARGB_8888, false)
            decoded.recycle()
            copied
        }
    }

    private fun readNativeJson(debugFile: File): JSONObject? {
        if (!debugFile.exists()) return null

        return try {
            val debugRoot = JSONObject(debugFile.readText())
            val raw = debugRoot.opt("native_json_raw")
            when (raw) {
                is JSONObject -> raw
                is String -> raw.takeIf { it.isNotBlank() }?.let(::JSONObject)
                else -> null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed reading debug_kotlin.json", t)
            null
        }
    }

    private fun writeManifest(
        runId: String,
        timestampUtc: String,
        inputRoot: String,
        outputRoot: String,
        runRoot: File,
        files: List<ManifestEntry>,
        processedOk: Int,
        processedError: Int,
        totalImagesFound: Int,
        totalElapsedMs: Long
    ): String {
        val manifestFile = File(runRoot, "manifest.json")
        val filesJson = JSONArray()
        files.forEach { entry ->
            filesJson.put(
                JSONObject()
                    .put("relative_path", entry.relativePath)
                    .put("json_path", entry.jsonPath)
                    .put("status", entry.status)
            )
        }

        val manifestJson = JSONObject()
            .put("run_id", runId)
            .put("timestamp_utc", timestampUtc)
            .put("input_root", inputRoot)
            .put("output_root", outputRoot)
            .put("pipeline_version", pipelineVersion)
            .put("total_images_found", totalImagesFound)
            .put("processed_ok", processedOk)
            .put("processed_error", processedError)
            .put("total_elapsed_ms", totalElapsedMs)
            .put("files", filesJson)

        manifestFile.writeText(manifestJson.toString(2))
        return manifestFile.absolutePath
    }

    private fun cleanupPipelineCacheArtifacts() {
        val cacheDir = appContext.cacheDir
        cacheDir.listFiles()
            ?.filter { file ->
                file.name == "debug_kotlin.json" ||
                    file.name == "cpp_seg_overlay_last.png" ||
                    file.name.startsWith("cpp_input_")
            }
            ?.forEach { file ->
                file.delete()
            }
    }

    private fun recycleQuietly(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private fun ensureDirectory(dir: File?) {
        requireNotNull(dir) { "Directory reference is null" }
        if (dir.exists()) {
            if (!dir.isDirectory) {
                throw IOException("Path is not a directory: ${dir.absolutePath}")
            }
            return
        }
        if (!dir.mkdirs()) {
            throw IOException("Unable to create directory: ${dir.absolutePath}")
        }
    }

    private fun relativeFolder(relativePath: String): String {
        val slashIndex = relativePath.lastIndexOf('/')
        return if (slashIndex >= 0) {
            relativePath.substring(0, slashIndex)
        } else {
            ""
        }
    }

    private fun toJsonRelativePath(relativePath: String): String {
        val dotIndex = relativePath.lastIndexOf('.')
        return if (dotIndex > 0) {
            "${relativePath.substring(0, dotIndex)}.json"
        } else {
            "$relativePath.json"
        }
    }

    private fun JSONObject?.optStringOrNull(name: String): String? {
        if (this == null || !has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    private fun JSONObject?.optIntOrNull(name: String): Int? {
        if (this == null || !has(name) || isNull(name)) return null
        return optInt(name)
    }

    private fun JSONObject?.optDoubleOrNull(name: String): Double? {
        if (this == null || !has(name) || isNull(name)) return null
        return optDouble(name)
    }

    private fun JSONObject?.optBooleanOrNull(name: String): Boolean? {
        if (this == null || !has(name) || isNull(name)) return null
        return optBoolean(name)
    }

    private fun JSONObject?.optIntListOrNull(name: String): List<Int>? {
        val array = this?.optJSONArray(name) ?: return null
        return List(array.length()) { idx -> array.optInt(idx) }
    }

    private fun JSONObject?.optDoubleListOrNull(name: String): List<Double>? {
        val array = this?.optJSONArray(name) ?: return null
        return List(array.length()) { idx -> array.optDouble(idx) }
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject {
        put(name, value ?: JSONObject.NULL)
        return this
    }

    private fun toJsonArray(values: Iterable<*>): JSONArray {
        val array = JSONArray()
        values.forEach { value ->
            array.put(value ?: JSONObject.NULL)
        }
        return array
    }
}
