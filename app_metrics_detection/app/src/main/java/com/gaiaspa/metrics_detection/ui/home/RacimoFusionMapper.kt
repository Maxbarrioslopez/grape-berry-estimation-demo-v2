package com.gaiaspa.metrics_detection.ui.home

import com.gaiaspa.metrics_detection.data.model.CalPredict
import com.gaiaspa.metrics_detection.data.model.FusionEngine
import com.gaiaspa.metrics_detection.data.model.FusionGroupMetadata
import com.gaiaspa.metrics_detection.data.model.FusionMetadata
import kotlin.math.abs
import kotlin.math.max

/**
 * Stateless mapper that translates between the flat list of
 * [HomeViewModel.ImagePrediction] items and the racimo-grouped
 * [RacimoUiModel] and [SavePayload] representations.
 *
 * In multi-view fusion mode each racimo is represented by two
 * consecutive entries (Frente at even index, Reverso at odd index).
 * This mapper pairs them, runs [FusionEngine.fuse], derives the
 * aggregate state, and selects a representative face for display
 * and persistence.
 */
object RacimoFusionMapper {

    /**
     * Lightweight projection of an [ImagePrediction] for the mapper.
     * Contains only the paths and prediction needed for grouping/fusion.
     */
    data class ImageInput(
        val sourcePath: String?,
        val normalizedPath: String?,
        val uploadPath: String?,
        val overlayPath: String?,
        val prediction: CalPredict?
    )

    /**
     * Output container for the save operation.
     * Holds parallel lists of paths and their corresponding predictions,
     * ordered so that index N corresponds across all lists.
     */
    data class SavePayload(
        val sourceImages: List<String>,
        val normalizedImages: List<String>,
        val uploadImages: List<String>,
        val overlayImages: List<String>,
        val calPredicts: List<CalPredict>
    )

    /**
     * Builds [RacimoUiModel] instances by pairing consecutive
     * [ImageInput] items. Empty/incomplete slots are represented
     * as models with MISSING or EMPTY states so the UI can show
     * placeholder cards.
     *
     * @param items Flat list of image inputs. Each pair (even, odd)
     *   forms a racimo.
     * @param minimumRacimoCount Ensures at least this many models
     *   are returned, padding with EMPTY state entries if needed.
     */
    fun buildUiModels(items: List<ImageInput>, minimumRacimoCount: Int = 0): List<RacimoUiModel> {
        val racimoCount = max(minimumRacimoCount, (items.size + 1) / 2)
        return (0 until racimoCount).map { index ->
            val itemA = items.getOrNull(index * 2)?.takeUnless { it.isEmptySlot() }
            val itemB = items.getOrNull(index * 2 + 1)?.takeUnless { it.isEmptySlot() }
            val predA = itemA?.prediction
            val predB = itemB?.prediction
            val isComplete = itemA != null && itemB != null
            val result = if (isComplete && predA?.status == true && predB?.status == true) {
                runCatching { FusionEngine.fuse(predA, predB) }.getOrNull()
            } else {
                null
            }
            val imageAState = imageState(itemA, predA)
            val imageBState = imageState(itemB, predB)
            val state = bunchState(
                itemA = itemA,
                itemB = itemB,
                imageAState = imageAState,
                imageBState = imageBState,
                result = result
            )
            val selectedRole = result?.takeIf { it.fused.status && it.fused.qty > 0 }?.let {
                chooseRepresentativeRole(predA?.qty ?: 0, predB?.qty ?: 0, it.fused.qty)
            }
            val selectedItem = when (selectedRole) {
                "A" -> itemA
                "B" -> itemB
                else -> null
            }

            RacimoUiModel(
                racimoIndex = index + 1,
                isComplete = isComplete,
                hasAnyPhoto = itemA != null || itemB != null,
                imageAPath = itemA?.bestDisplayPath(),
                imageBPath = itemB?.bestDisplayPath(),
                overlayAPath = itemA?.overlayPath,
                overlayBPath = itemB?.overlayPath,
                predA = predA,
                predB = predB,
                fusedPrediction = result?.fused,
                fusionWarning = result?.warning,
                selectedImageRole = selectedRole,
                selectedImagePath = selectedItem?.bestDisplayPath(),
                state = state,
                imageAState = imageAState,
                imageBState = imageBState
            )
        }
    }

    /**
     * Builds a [SavePayload] for the legacy (single-image, non-fused) flow.
     * Simply collects non-null paths and predictions from all items.
     */
    fun buildLegacySavePayload(items: List<ImageInput>): SavePayload {
        return SavePayload(
            sourceImages = items.mapNotNull { it.sourcePath },
            normalizedImages = items.mapNotNull { it.normalizedPath },
            uploadImages = items.mapNotNull { it.uploadPath },
            overlayImages = items.mapNotNull { it.overlayPath },
            calPredicts = items.mapNotNull { it.prediction }
        )
    }

    /**
     * Builds a [SavePayload] for the multi-view fusion flow.
     *
     * Pairs consecutive items, runs [FusionEngine.fuse] on each pair,
     * selects a representative face, and attaches [FusionMetadata]
     * to each fused prediction so the backend can reconstruct individual
     * view contributions.
     *
     * @throws IllegalArgumentException if the list is empty, has odd size,
     *   or any racimo is missing a prediction or valid detection.
     */
    fun buildFusedSavePayload(items: List<ImageInput>): SavePayload {
        require(items.isNotEmpty()) { "Debes agregar al menos un racimo" }
        require(items.size % 2 == 0) { "Cada racimo debe tener Frente y Reverso" }

        val pairResults = items.chunked(2).mapIndexed { index, pair ->
            val itemA = pair.getOrNull(0) ?: throw IllegalArgumentException("Falta el Frente del racimo ${index + 1}")
            val itemB = pair.getOrNull(1) ?: throw IllegalArgumentException("Falta el Reverso del racimo ${index + 1}")
            val predA = itemA.prediction ?: throw IllegalArgumentException("El Frente del racimo ${index + 1} no tiene predicción")
            val predB = itemB.prediction ?: throw IllegalArgumentException("El Reverso del racimo ${index + 1} no tiene predicción")
            require(predA.status) { predA.error.ifBlank { "El Frente del racimo ${index + 1} tiene error" } }
            require(predB.status) { predB.error.ifBlank { "El Reverso del racimo ${index + 1} tiene error" } }

            val result = FusionEngine.fuse(predA, predB)
            require(result.fused.qty > 0) { "El racimo ${index + 1} no tiene detección válida" }
            val selectedRole = chooseRepresentativeRole(predA.qty, predB.qty, result.fused.qty)
            PairResult(
                racimoIndex = index + 1,
                itemA = itemA,
                itemB = itemB,
                predA = predA,
                predB = predB,
                result = result,
                selectedImageRole = selectedRole
            )
        }

        val groups = pairResults.mapIndexed { fusedIndex, pair ->
            FusionGroupMetadata(
                racimoIndex = pair.racimoIndex,
                viewAImageIndex = fusedIndex * 2,
                viewBImageIndex = fusedIndex * 2 + 1,
                fusedPredictionIndex = fusedIndex,
                qtyA = pair.predA.qty,
                qtyB = pair.predB.qty,
                qtyFinal = pair.result.fused.qty,
                disagreement = pair.result.disagreement,
                disagreementUi = pair.result.disagreementUi,
                meanA = pair.predA.mean,
                meanB = pair.predB.mean,
                meanFinal = pair.result.fused.mean,
                modeA = pair.predA.mode,
                modeB = pair.predB.mode,
                modeFinal = pair.result.fused.mode,
                stdA = pair.predA.std,
                stdB = pair.predB.std,
                stdFinal = pair.result.fused.std,
                warning = pair.result.warning,
                originalViewAImageIndex = fusedIndex * 2,
                originalViewBImageIndex = fusedIndex * 2 + 1,
                selectedImageRole = pair.selectedImageRole,
                viewASourcePath = pair.itemA.localSourcePath(),
                viewBSourcePath = pair.itemB.localSourcePath(),
                viewAUploadPath = pair.itemA.uploadPath,
                viewBUploadPath = pair.itemB.uploadPath,
                viewAOverlayPath = pair.itemA.overlayPath,
                viewBOverlayPath = pair.itemB.overlayPath
            )
        }
        val metadata = FusionMetadata(groups = groups)

        return SavePayload(
            sourceImages = pairResults.map { it.selectedItem().sourcePathForSave() },
            normalizedImages = pairResults.map { it.selectedItem().normalizedPathForSave() },
            uploadImages = pairResults.map { it.selectedItem().uploadPathForSave() },
            overlayImages = pairResults.map { it.selectedItem().overlayPathForSave() },
            calPredicts = pairResults.map { it.result.fused.copy(fusionMetadata = metadata) }
        )
    }

    /**
     * Selects the face (A or B) whose individual berry count is closest
     * to the fused result. Used as the representative image for display
     * and persistence.
     */
    fun chooseRepresentativeRole(qtyA: Int, qtyB: Int, qtyFinal: Int): String {
        return if (abs(qtyA - qtyFinal) <= abs(qtyB - qtyFinal)) "A" else "B"
    }

    private data class PairResult(
        val racimoIndex: Int,
        val itemA: ImageInput,
        val itemB: ImageInput,
        val predA: CalPredict,
        val predB: CalPredict,
        val result: FusionEngine.Result,
        val selectedImageRole: String
    ) {
        fun selectedItem(): ImageInput = if (selectedImageRole == "B") itemB else itemA
    }

    private fun ImageInput.bestDisplayPath(): String? =
        firstPath(overlayPath, uploadPath, normalizedPath, sourcePath)

    private fun ImageInput.localSourcePath(): String? =
        firstPath(normalizedPath, sourcePath)

    private fun ImageInput.sourcePathForSave(): String =
        firstPath(sourcePath, normalizedPath, uploadPath, overlayPath).orEmpty()

    private fun ImageInput.normalizedPathForSave(): String =
        firstPath(normalizedPath, sourcePath, uploadPath, overlayPath).orEmpty()

    private fun ImageInput.uploadPathForSave(): String =
        firstPath(uploadPath, normalizedPath, sourcePath, overlayPath).orEmpty()

    private fun ImageInput.overlayPathForSave(): String =
        firstPath(overlayPath, uploadPath, normalizedPath, sourcePath).orEmpty()

    private fun firstPath(vararg paths: String?): String? =
        paths.firstOrNull { !it.isNullOrBlank() }

    private fun ImageInput.isEmptySlot(): Boolean =
        sourcePath.isNullOrBlank() &&
            normalizedPath.isNullOrBlank() &&
            uploadPath.isNullOrBlank() &&
            overlayPath.isNullOrBlank() &&
            prediction == null

    private fun imageState(item: ImageInput?, prediction: CalPredict?): RacimoUiModel.ImageState {
        return when {
            item == null -> RacimoUiModel.ImageState.MISSING
            prediction == null -> RacimoUiModel.ImageState.PROCESSING
            prediction.status && prediction.qty > 0 -> RacimoUiModel.ImageState.VALID
            else -> RacimoUiModel.ImageState.FAILED
        }
    }

    private fun bunchState(
        itemA: ImageInput?,
        itemB: ImageInput?,
        imageAState: RacimoUiModel.ImageState,
        imageBState: RacimoUiModel.ImageState,
        result: FusionEngine.Result?
    ): RacimoUiModel.State {
        return when {
            itemA == null && itemB == null -> RacimoUiModel.State.EMPTY
            itemA == null -> RacimoUiModel.State.FRONT_MISSING
            itemB == null -> RacimoUiModel.State.BACK_MISSING
            imageAState == RacimoUiModel.ImageState.FAILED -> RacimoUiModel.State.FRONT_ERROR
            imageBState == RacimoUiModel.ImageState.FAILED -> RacimoUiModel.State.BACK_ERROR
            imageAState == RacimoUiModel.ImageState.PROCESSING ||
                imageBState == RacimoUiModel.ImageState.PROCESSING -> RacimoUiModel.State.PROCESSING
            result?.warning != null -> RacimoUiModel.State.LOW_QUALITY
            result?.fused?.status == true && result.fused.qty > 0 -> RacimoUiModel.State.COMPLETE
            else -> RacimoUiModel.State.PROCESSING_FAILED
        }
    }
}
