package com.gaiaspa.metrics_detection.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.*
import com.gaiaspa.metrics_detection.BuildConfig
import com.gaiaspa.metrics_detection.FeatureFlags
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.data.model.CalPredict
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.data.repository.LoteRepository
import com.gaiaspa.metrics_detection.ml.MetricsPipeline
import com.gaiaspa.metrics_detection.ml.ImageUtils
import com.gaiaspa.metrics_detection.network.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for the capture and processing flow.
 *
 * Orchestrates image ingestion (single or Frente/Reverso pairs), runs the
 * MetricsPipeline for each image, tracks processing status, and persists
 * completed batches via LoteRepository.
 *
 * The overlay images rendered by C++ are kept as file paths passed through
 * to the final Lote entity. No prediction data (qty, calibre, bins) is
 * altered by the overlay drawing.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Represents a single image in the processing pipeline.
     *
     * @property uri Source URI of the image file.
     * @property normalizedPath Path to the normalized (cropped/resized) copy.
     * @property uploadPath Path to the 1024px clean base generated for upload.
     * @property previewBitmap Downsampled preview shown in the RecyclerView.
     * @property status Current processing status for this image.
     * @property prediction CalPredict result from the MetricsPipeline.
     * @property overlayPath Path to the visual overlay rendered by C++.
     * @property errorMessage Human-readable error if processing failed.
     * @property isPlaceholder True when this slot exists only to maintain
     *   Frente/Reverso pair ordering (no actual image data).
     */
    data class ImagePrediction(
        val uri: Uri,
        val normalizedPath: String? = null,
        val uploadPath: String? = null,
        val previewBitmap: Bitmap? = null,
        val status: Status = Status.PENDING,
        val prediction: CalPredict? = null,
        val overlayPath: String? = null,
        val errorMessage: String? = null,
        val isPlaceholder: Boolean = false
    )

    /** Lifecycle of a single image through the pipeline. */
    enum class Status {
        /** Queued for processing. */
        PENDING,
        /** Normalization (resize/crop) in progress. */
        NORMALIZING,
        /** MetricsPipeline is running. */
        PROCESSING,
        /** Processing completed successfully. */
        DONE,
        /** Processing failed with an error. */
        ERROR
    }

    /**
     * Identifies which side of a racimo pair an image belongs to.
     * A = Frente (front), B = Reverso (back).
     */
    enum class PhotoRole { A, B }

    val imagePredictions = MutableLiveData<List<ImagePrediction>>(emptyList())
    val visibleRacimoCount = MutableLiveData(0)
    val selectedVariety = MutableLiveData<VarietyOption?>(null)
    val company = MutableLiveData<String>("")
    val vessel = MutableLiveData<String>("")
    val block = MutableLiveData<String>("")
    val availableVarieties = MutableLiveData<List<VarietyOption>>(emptyList())
    val isSavingLote = MutableLiveData<Boolean>(false)
    val saveErrorMessage = MutableLiveData<String?>(null)

    private val repository = LoteRepository.getInstance(application)
    private val instanceSeg = MetricsPipeline(application) { Log.d("HomeVM", it) }
    private var processingSnapshotVersion = 0

    private companion object {
        const val MIN_IMAGE_SIDE_PX = 256
    }

    init {
        availableVarieties.value = listOf(
            VarietyOption(0, "ALLISON"), VarietyOption(1, "AUTUMN CRISP"),
            VarietyOption(2, "CRIMSON"), VarietyOption(3, "IVORY"),
            VarietyOption(4, "MAGENTA"), VarietyOption(5, "RED GLOBE"),
            VarietyOption(6, "SCARLOTTA"), VarietyOption(7, "SUPERIOR"),
            VarietyOption(8, "SWEET GLOBE"), VarietyOption(9, "THOMPSON"),
            VarietyOption(10, "TIMCO"), VarietyOption(11, "TIMPSON")
        )
    }

    /**
     * Adds a single image to the processing queue and starts the pipeline.
     * @param path Absolute file path to the source image.
     */
    fun addImage(path: String) {
        val current = imagePredictions.value.orEmpty().toMutableList()
        current.add(ImagePrediction(Uri.fromFile(File(path))))
        imagePredictions.value = current
        updateVisibleRacimosForCurrentImages()
        processAll()
    }

    /**
     * Batch-inserts multiple images into the processing queue.
     * @param paths List of absolute file paths. If empty, no-op.
     */
    fun addImages(paths: List<String>) {
        if (paths.isEmpty()) return
        val current = imagePredictions.value.orEmpty().toMutableList()
        current.addAll(paths.map { ImagePrediction(Uri.fromFile(File(it))) })
        imagePredictions.value = current
        updateVisibleRacimosForCurrentImages()
        processAll()
    }

    /**
     * Ensures visibleRacimoCount is consistent with existing images.
     * Guarded by [FeatureFlags.multiViewFusionEnabled]. Called on
     * initial screen load to restore the count after rotation/restore.
     */
    fun ensureInitialRacimo() {
        if (!FeatureFlags.multiViewFusionEnabled) return
        if (imagePredictions.value.orEmpty().isNotEmpty()) updateVisibleRacimosForCurrentImages()
    }

    /**
     * Adds an empty racimo slot (no photos attached) ahead of guided capture.
     * @return true if a new slot was created, false if an open racimo exists
     *   or multi-view fusion is disabled.
     */
    fun addEmptyRacimo(): Boolean {
        if (!FeatureFlags.multiViewFusionEnabled) return false
        val models = buildRacimoUiModels()
        val hasOpenRacimo = models.any { !it.isProcessed && !it.isComplete }
        if (hasOpenRacimo) return false
        visibleRacimoCount.value = (visibleRacimoCount.value ?: models.size).coerceAtLeast(models.size) + 1
        return true
    }

    /**
     * Inserts or replaces a photo (Frente or Reverso) for a specific racimo.
     * Automatically pads the list with placeholders to maintain pair ordering.
     *
     * @param racimoIndex 1-based racimo position.
     * @param role Frente (A) or Reverso (B).
     * @param path Absolute file path to the new image.
     * @return true if the photo was upserted successfully.
     */
    fun upsertPhotoForRacimo(racimoIndex: Int, role: PhotoRole, path: String): Boolean {
        if (racimoIndex < 1) return false
        val targetIndex = (racimoIndex - 1) * 2 + if (role == PhotoRole.A) 0 else 1
        val current = imagePredictions.value.orEmpty().toMutableList()
        while (current.size < targetIndex) {
            current.add(ImagePrediction(uri = Uri.EMPTY, status = Status.DONE, isPlaceholder = true))
        }

        val newItem = ImagePrediction(Uri.fromFile(File(path)))
        if (targetIndex == current.size) {
            current.add(newItem)
        } else {
            current[targetIndex] = newItem
        }
        imagePredictions.value = current
        visibleRacimoCount.value = maxOf(visibleRacimoCount.value ?: 0, racimoIndex)
        processAll()
        return true
    }

    /**
     * Removes a single photo from a racimo by replacing it with a placeholder.
     * The slot is kept so pair ordering is preserved.
     *
     * @param racimoIndex 1-based racimo position.
     * @param role Frente (A) or Reverso (B).
     * @return true if the slot existed and was cleared.
     */
    fun removePhotoForRacimo(racimoIndex: Int, role: PhotoRole): Boolean {
        if (racimoIndex < 1) return false
        val targetIndex = (racimoIndex - 1) * 2 + if (role == PhotoRole.A) 0 else 1
        val current = imagePredictions.value.orEmpty().toMutableList()
        if (targetIndex !in current.indices) return false

        current[targetIndex] = ImagePrediction(
            uri = Uri.EMPTY,
            status = Status.DONE,
            isPlaceholder = true
        )
        imagePredictions.value = current
        visibleRacimoCount.value = maxOf(visibleRacimoCount.value ?: 0, racimoIndex)
        return true
    }

    /**
     * Swaps Frente and Reverso photos for a racimo and re-runs the pipeline.
     * Both images are marked PENDING for reprocessing after the role swap.
     *
     * @param racimoIndex 1-based racimo position.
     * @return null on success, or a localized error string if the racimo
     *   was not found or has insufficient images.
     */
    fun swapRacimoFrontBack(racimoIndex: Int): String? {
        val context = getApplication<Application>()
        if (racimoIndex < 1) return context.getString(R.string.bunch_not_found)
        val start = (racimoIndex - 1) * 2
        val current = imagePredictions.value.orEmpty().toMutableList()
        if (start !in current.indices && start + 1 !in current.indices) {
            return context.getString(R.string.bunch_not_found)
        }
        while (current.size <= start + 1) {
            current.add(ImagePrediction(uri = Uri.EMPTY, status = Status.DONE, isPlaceholder = true))
        }

        val front = current[start]
        val back = current[start + 1]
        current[start] = back.markPendingAfterRoleChange()
        current[start + 1] = front.markPendingAfterRoleChange()
        imagePredictions.value = current
        processAll()
        return null
    }

    /**
     * Re-runs the pipeline for a specific racimo using its source files.
     * Requires both Frente and Reverso photos to exist in the current list.
     *
     * @param racimoIndex 1-based racimo position.
     * @return null on success, or a localized error string indicating which
     *   image is missing or failed validation.
     */
    fun recalculateRacimo(racimoIndex: Int): String? {
        val context = getApplication<Application>()
        if (racimoIndex < 1) return context.getString(R.string.bunch_not_found)
        val start = (racimoIndex - 1) * 2
        val current = imagePredictions.value.orEmpty().toMutableList()
        val front = current.getOrNull(start)
        val back = current.getOrNull(start + 1)

        val frontFile = front?.bestSourceFile()
            ?: return context.getString(R.string.front_image_missing_continue)
        val backFile = back?.bestSourceFile()
            ?: return context.getString(R.string.back_image_missing_continue)

        validateImageFile(frontFile)?.let { return context.getString(R.string.front_image_failed_continue) }
        validateImageFile(backFile)?.let { return context.getString(R.string.back_image_failed_continue) }

        current[start] = front!!.markPendingForReprocess(frontFile)
        current[start + 1] = back!!.markPendingForReprocess(backFile)
        imagePredictions.value = current
        processAll()
        return null
    }

    /**
     * Removes a single image from the flat list (legacy mode).
     * @param index Position in the imagePredictions list.
     */
    fun removeImageAt(index: Int) {
        val current = imagePredictions.value.orEmpty().toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            imagePredictions.value = current
            updateVisibleRacimosForCurrentImages()
        }
    }

    /**
     * Removes both Frente and Reverso images for a racimo from the list.
     * Adjusts visibleRacimoCount downward if needed.
     *
     * @param racimoIndex 1-based racimo position.
     */
    fun removeRacimoAt(racimoIndex: Int) {
        val start = (racimoIndex - 1) * 2
        val current = imagePredictions.value.orEmpty().toMutableList()
        if (start in current.indices) {
            repeat(2) {
                if (start in current.indices) current.removeAt(start)
            }
        }
        imagePredictions.value = current
        val minCount = ((current.size + 1) / 2).coerceAtLeast(if (current.isEmpty()) 0 else 1)
        visibleRacimoCount.value = minOf((visibleRacimoCount.value ?: 1).coerceAtLeast(1), minCount)
    }

    /** Clears all images and resets visibleRacimoCount to 0. */
    fun clearImages() {
        imagePredictions.value = emptyList()
        visibleRacimoCount.value = 0
    }

    private fun updateVisibleRacimosForCurrentImages() {
        if (!FeatureFlags.multiViewFusionEnabled) return
        val countFromImages = ((imagePredictions.value.orEmpty().size + 1) / 2).coerceAtLeast(1)
        visibleRacimoCount.value = maxOf(visibleRacimoCount.value ?: 0, countFromImages)
    }

    /**
     * Builds UI models for all racimos from the current image list.
     *
     * @param items Image predictions to group into pairs.
     *   Defaults to the current LiveData value.
     * @return List of [RacimoUiModel] with fusion state, warnings,
     *   and per-face image paths.
     */
    fun buildRacimoUiModels(
        items: List<ImagePrediction> = imagePredictions.value.orEmpty()
    ): List<RacimoUiModel> = RacimoFusionMapper.buildUiModels(
        items = items.map { it.toFusionInput() },
        minimumRacimoCount = visibleRacimoCount.value ?: 0
    )

    /**
     * Returns a guide string indicating what to capture next.
     * Example: "Racimo 3 — Photo A (Front)".
     */
    fun nextCaptureGuide(): String {
        val count = imagePredictions.value.orEmpty().size
        val racimoIndex = count / 2 + 1
        val context = getApplication<Application>()
        val role = if (count % 2 == 0) {
            context.getString(R.string.photo_a_front)
        } else {
            context.getString(R.string.photo_b_back)
        }
        return context.getString(R.string.next_capture_guide, racimoIndex, role)
    }

    /**
     * Returns a short prompt ("Capture Front" / "Capture Back")
     * based on whether the next needed photo is Frente or Reverso.
     */
    fun nextCapturePrompt(): String {
        val count = imagePredictions.value.orEmpty().size
        val context = getApplication<Application>()
        return if (count % 2 == 0) {
            context.getString(R.string.capture_front_prompt)
        } else {
            context.getString(R.string.capture_back_prompt)
        }
    }

    /**
     * Returns true if all images are processed and the batch is ready to save.
     * In multi-view mode at least one racimo must be fully fused.
     */
    fun canSaveCurrentBatch(): Boolean {
        val items = imagePredictions.value.orEmpty()
        if (items.isEmpty()) return false
        if (items.any { it.status != Status.DONE }) return false
        if (!FeatureFlags.multiViewFusionEnabled) return true
        val models = buildRacimoUiModels(items)
        val hasProcessedRacimo = models.any { it.isProcessed }
        val hasBlockingRacimo = models.any { it.hasAnyPhoto && !it.isProcessed }
        return hasProcessedRacimo && !hasBlockingRacimo
    }

    /**
     * Launches asynchronous processing for all PENDING or ERROR images.
     * Uses a snapshot version counter to cancel stale launches if the
     * list changes before a previous batch completes.
     */
    fun processAll() {
        val currentVersion = ++processingSnapshotVersion
        viewModelScope.launch(Dispatchers.IO) {
            val list = imagePredictions.value.orEmpty()
            list.forEachIndexed { index, item ->
                if (currentVersion != processingSnapshotVersion) return@launch
                if (!item.isPlaceholder && (item.status == Status.PENDING || item.status == Status.ERROR)) {
                    processImage(index, item)
                }
            }
        }
    }

    private fun createOverlayWorkingCopy(uploadPath: String, lotesDir: File, index: Int): File? {
        val time = System.currentTimeMillis()
        val resFile = File(lotesDir, "res_${time}_$index.jpg")
        if (uploadPath.isBlank()) {
            Log.w("OVERLAY_FLOW", "createOverlayWorkingCopy: uploadPath empty, cannot create overlay copy")
            return null
        }
        val src = File(uploadPath)
        if (!src.exists() || src.length() == 0L) {
            Log.w("OVERLAY_FLOW", "createOverlayWorkingCopy: uploadPath does not exist or is empty: $uploadPath")
            return null
        }
        return try {
            src.copyTo(resFile, overwrite = true).also {
                Log.d("OVERLAY_FLOW", "createOverlayWorkingCopy: ${src.absolutePath} -> ${it.absolutePath} (${it.length()} bytes)")
            }
        } catch (e: Exception) {
            Log.e("OVERLAY_FLOW", "createOverlayWorkingCopy: error copying $uploadPath -> ${resFile.absolutePath}: ${e.message}")
            null
        }
    }

    private fun processImage(index: Int, item: ImagePrediction) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            try {
                updateItemStatus(index, Status.NORMALIZING)
                val lotesDir = File(context.filesDir, "lotes_media").apply { mkdirs() }
                val time = System.currentTimeMillis()
                
                val srcFile = File(lotesDir, "src_${time}_$index.jpg")
                val sourceFile = item.bestSourceFile()
                    ?: throw IllegalArgumentException(context.getString(R.string.image_failed_to_load))
                validateImageFile(sourceFile)?.let { validationError -> throw IllegalArgumentException(validationError) }
                sourceFile.inputStream().use { input ->
                    srcFile.outputStream().use { output -> input.copyTo(output) }
                }

        // 1. GENERATE UPLOAD (clean 1024px base)
        val uploadPath = ImageUtils.generateUpload512(srcFile.absolutePath, lotesDir) ?: ""
                Log.d("OVERLAY_FLOW", "uploadPath generated: ${if (uploadPath.isBlank()) "EMPTY" else uploadPath}")
                
        // 2. PREPARE OVERLAY COPY (independent copy for C++ overlay rendering)
        val resFile = createOverlayWorkingCopy(uploadPath, lotesDir, index)
        val overlayBase = resFile?.absolutePath ?: ""
                Log.d("PIPELINE_INPUT", "imagePath=${srcFile.absolutePath} | visualOverlayBase=$overlayBase | index=$index")

                // Fast temporary preview while pipeline runs
                val tempPreview = ImageUtils.decodeSampledBitmap(srcFile.absolutePath, 300, 300)
                updateItemPreview(index, srcFile.absolutePath, tempPreview)

                updateItemStatus(index, Status.PROCESSING)
                
                // 3. INVOKE PIPELINE
                instanceSeg.invokeFromFile(
                    imagePath = srcFile.absolutePath,
                    smoothEdges = true,
                    varietyId = selectedVariety.value?.id,
                    visualOverlayBase = overlayBase,
                    onSuccess = { success ->
                        viewModelScope.launch(Dispatchers.Default) {
                            val overlayPath = if (overlayBase.isNotEmpty() && File(overlayBase).exists()) {
                                Log.d("OVERLAY_FLOW", "Pipeline OK. overlay=${overlayBase} (${File(overlayBase).length()} bytes)")
                                overlayBase
                            } else {
                                Log.w("OVERLAY_FLOW", "Pipeline OK but overlay does not exist/is empty. Fallback to uploadPath")
                                uploadPath
                            }
                            val finalPreview = if (overlayPath.isNotEmpty()) {
                                ImageUtils.decodeSampledBitmap(overlayPath, 512, 512)
                            } else null
                            
                            val calPredict = success.predictsList.firstOrNull() ?: CalPredict(status = false, error = getApplication<Application>().getString(R.string.prediction_missing))
                            
                            val preview = finalPreview ?: tempPreview ?: BitmapFactory.decodeFile(srcFile.absolutePath)
                            if (preview != null) {
                                updateItemSuccess(index, overlayPath, uploadPath, preview, calPredict)
                            } else {
                                updateItemError(index, getApplication<Application>().getString(R.string.preview_generation_error))
                            }
                        }
                    },
                    onFailure = { err -> updateItemError(index, err) }
                )
            } catch (e: OutOfMemoryError) {
                Log.e("HomeVM", "OutOfMemory processing image", e)
                updateItemError(index, getApplication<Application>().getString(R.string.image_could_not_be_processed))
            } catch (e: Exception) {
                Log.e("HomeVM", "Error in processImage: ${e.message}")
                updateItemError(index, e.message ?: getApplication<Application>().getString(R.string.error_unknown))
            }
        }
    }

    /**
     * Saves the current batch of predictions to the local Room database.
     *
     * Guards: all images must be in DONE state; in multi-view mode the batch
     * must also pass canSaveCurrentBatch(). On success the Lote is inserted
     * locally and a manual WorkManager sync is enqueued.
     */
    fun saveBatch(callback: (Boolean) -> Unit) {
        val currentPredictions = imagePredictions.value.orEmpty()
        val context = getApplication<Application>()
        if (currentPredictions.isEmpty() || currentPredictions.any { it.status != Status.DONE }) {
            saveErrorMessage.value = if (FeatureFlags.multiViewFusionEnabled) {
                context.getString(R.string.photos_processing_save_error)
            } else {
                null
            }
            callback(false)
            return
        }

        if (FeatureFlags.multiViewFusionEnabled && !canSaveCurrentBatch()) {
            val models = buildRacimoUiModels(currentPredictions)
            saveErrorMessage.value = when {
                models.any { it.hasAnyPhoto && !it.isComplete } ->
                    context.getString(R.string.second_photo_required_to_calculate)
                models.any { it.hasAnyPhoto && !it.isProcessed } ->
                    context.getString(R.string.invalid_bunch_message)
                else -> context.getString(R.string.no_processed_bunch_to_save)
            }
            callback(false)
            return
        }

        val payloadForSave = try {
            buildPayloadForSave(currentPredictions)
        } catch (e: IllegalArgumentException) {
            saveErrorMessage.value = context.getString(R.string.batch_prepare_error)
            callback(false)
            return
        } catch (e: Exception) {
            Log.e("HomeVM", "Error preparing predictions: ${e.message}", e)
            saveErrorMessage.value = context.getString(R.string.batch_prepare_error)
            callback(false)
            return
        }

        isSavingLote.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                TokenProvider.init(getApplication())
                val lote = Lote(
                    userId = repository.getCurrentUserId(),
                    company = company.value ?: "No Company",
                    vessel = vessel.value ?: "No Vessel",
                    block = block.value ?: "No Block",
                    varietyId = selectedVariety.value?.id ?: -1,
                    varietyName = selectedVariety.value?.name ?: "UNKNOWN",
                    sourceImages = payloadForSave.sourceImages,
                    normalizedImages = payloadForSave.normalizedImages,
                    uploadImages = payloadForSave.uploadImages,
                    overlayImages = payloadForSave.overlayImages, // ✅ PERSIST THE NATIVE RES_ OR REPRESENTATIVE
                    calPredicts = payloadForSave.calPredicts,
                    synced = false
                )
                repository.insertLocalLote(lote)
                
                if (!BuildConfig.DEMO_MODE) {
                    com.gaiaspa.metrics_detection.worker.SyncManager.enqueueManualSync(getApplication())
                }

                viewModelScope.launch(Dispatchers.Main) {
                    isSavingLote.value = false
                    imagePredictions.value = emptyList()
                    saveErrorMessage.value = null
                    callback(true)
                }
            } catch (e: Exception) {
                Log.e("HomeVM", "Error saving batch: ${e.message}", e)
                viewModelScope.launch(Dispatchers.Main) {
                    isSavingLote.value = false
                    saveErrorMessage.value = e.message ?: context.getString(R.string.batch_save_error)
                    callback(false)
                }
            }
        }
    }

    private fun buildPayloadForSave(items: List<ImagePrediction>): RacimoFusionMapper.SavePayload {
        val inputs = items.map { it.toFusionInput() }
        if (!FeatureFlags.multiViewFusionEnabled) {
            val predictions = items.mapIndexed { index, item ->
                item.prediction ?: throw IllegalArgumentException("Photo ${index + 1} has no prediction")
            }
            return RacimoFusionMapper.buildLegacySavePayload(inputs).copy(calPredicts = predictions)
        }

        return RacimoFusionMapper.buildFusedSavePayload(inputs).also { payload ->
            payload.calPredicts.forEach { prediction ->
                prediction.fusionMetadata?.groups?.forEach { group ->
                    group.warning?.let { warning ->
                        Log.w("MultiViewFusion", "racimoIndex=${group.racimoIndex} warning=$warning")
                    }
                    Log.d(
                        "MultiViewFusion",
                        "racimoIndex=${group.racimoIndex} " +
                            "qtyA=${group.qtyA} qtyB=${group.qtyB} qtyFinal=${group.qtyFinal} " +
                            "selectedImageRole=${group.selectedImageRole} " +
                            "disagreement=${group.disagreement} " +
                            "meanA=${group.meanA} meanB=${group.meanB} meanFinal=${group.meanFinal}"
                    )
                }
            }
        }
    }

    private fun ImagePrediction.toFusionInput(): RacimoFusionMapper.ImageInput {
        return RacimoFusionMapper.ImageInput(
            sourcePath = uri.takeUnless { isPlaceholder }?.toString(),
            normalizedPath = normalizedPath,
            uploadPath = uploadPath,
            overlayPath = overlayPath,
            prediction = prediction
        )
    }

    private fun ImagePrediction.markPendingForReprocess(sourceFile: File): ImagePrediction {
        return copy(
            uri = Uri.fromFile(sourceFile),
            normalizedPath = null,
            uploadPath = null,
            overlayPath = null,
            prediction = null,
            status = Status.PENDING,
            errorMessage = null,
            isPlaceholder = false
        )
    }

    private fun ImagePrediction.markPendingAfterRoleChange(): ImagePrediction {
        val sourceFile = bestSourceFile()
        return if (sourceFile != null) {
            markPendingForReprocess(sourceFile)
        } else {
            copy(
                prediction = null,
                status = if (isPlaceholder) Status.DONE else Status.ERROR,
                errorMessage = getApplication<Application>().getString(R.string.image_failed_to_load)
            )
        }
    }

    private fun ImagePrediction.bestSourceFile(): File? {
        val candidates = listOf(
            uri.path,
            normalizedPath,
            uploadPath,
            overlayPath
        )
        return candidates.asSequence()
            .filterNotNull()
            .map { it.replace("file://", "") }
            .map { File(it) }
            .firstOrNull { it.isFile && it.exists() && it.length() > 0L }
    }

    /**
     * Validates that an image file exists, is readable, and meets the
     * minimum dimension requirement.
     *
     * @param file The image file to validate.
     * @return null if valid, or a localized error string describing
     *   the validation failure.
     */
    fun validateImageFile(file: File): String? {
        return try {
            if (!file.exists() || file.length() <= 0L) return getApplication<Application>().getString(R.string.image_failed_to_load)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                getApplication<Application>().getString(R.string.image_could_not_be_processed)
            } else if (options.outWidth < MIN_IMAGE_SIDE_PX || options.outHeight < MIN_IMAGE_SIDE_PX) {
                getApplication<Application>().getString(R.string.image_too_small)
            } else {
                null
            }
        } catch (e: OutOfMemoryError) {
            Log.e("HomeVM", "OutOfMemory validating image", e)
            getApplication<Application>().getString(R.string.image_could_not_be_processed)
        } catch (e: Exception) {
            Log.e("HomeVM", "Error validating image", e)
            getApplication<Application>().getString(R.string.image_could_not_be_processed)
        }
    }

    private fun updateItemStatus(index: Int, status: Status) {
        viewModelScope.launch(Dispatchers.Main) {
            val list = imagePredictions.value.orEmpty().toMutableList()
            if (index in list.indices) {
                list[index] = list[index].copy(status = status)
                imagePredictions.value = list
            }
        }
    }

    private fun updateItemPreview(index: Int, path: String, preview: Bitmap?) {
        viewModelScope.launch(Dispatchers.Main) {
            val list = imagePredictions.value.orEmpty().toMutableList()
            if (index in list.indices) {
                list[index] = list[index].copy(normalizedPath = path, previewBitmap = preview)
                imagePredictions.value = list
            }
        }
    }

    private fun updateItemSuccess(index: Int, overlayPath: String, uploadPath: String, preview: Bitmap, result: CalPredict) {
        viewModelScope.launch(Dispatchers.Main) {
            Log.d("OVERLAY_UI_FLOW", "[3] UI State Update. finalPath: $overlayPath")
            val list = imagePredictions.value.orEmpty().toMutableList()
            if (index in list.indices) {
                list[index] = list[index].copy(
                    status = Status.DONE,
                    overlayPath = overlayPath,
                    uploadPath = uploadPath,
                    previewBitmap = preview,
                    prediction = result
                )
                imagePredictions.value = list
            }
        }
    }

    private fun updateItemError(index: Int, error: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val list = imagePredictions.value.orEmpty().toMutableList()
            if (index in list.indices) {
                list[index] = list[index].copy(status = Status.ERROR, errorMessage = error)
                imagePredictions.value = list
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        instanceSeg.close()
    }
}
