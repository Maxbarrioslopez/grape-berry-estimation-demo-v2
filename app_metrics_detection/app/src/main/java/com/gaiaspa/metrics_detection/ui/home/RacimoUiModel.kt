package com.gaiaspa.metrics_detection.ui.home

import com.gaiaspa.metrics_detection.data.model.CalPredict

/**
 * UI-oriented representation of a racimo (bunch) in the capture screen.
 *
 * Groups a Frente/Reverso image pair with their individual and fused
 * prediction results. Used by [ImagePredictionAdapter] to render each
 * racimo card with per-face status badges, action buttons, and the
 * fused CalPredict metrics.
 *
 * @property racimoIndex 1-based display index of the racimo.
 * @property isComplete True when both Frente and Reverso images are present.
 * @property hasAnyPhoto True when at least one face has an image.
 * @property imageAPath Best display path for the Frente image.
 * @property imageBPath Best display path for the Reverso image.
 * @property overlayAPath C++ overlay path for the Frente face.
 * @property overlayBPath C++ overlay path for the Reverso face.
 * @property predA Individual CalPredict for the Frente face.
 * @property predB Individual CalPredict for the Reverso face.
 * @property fusedPrediction Merged CalPredict from FusionEngine.
 * @property fusionWarning Low-quality/disagreement warning from fusion.
 * @property selectedImageRole "A" or "B" — the face closest to the fused result.
 * @property selectedImagePath Best display path for the representative face.
 * @property state Aggregate state derived from both faces and fusion result.
 * @property imageAState Per-face state for Frente.
 * @property imageBState Per-face state for Reverso.
 */
data class RacimoUiModel(
    val racimoIndex: Int,
    val isComplete: Boolean,
    val hasAnyPhoto: Boolean,
    val imageAPath: String?,
    val imageBPath: String?,
    val overlayAPath: String?,
    val overlayBPath: String?,
    val predA: CalPredict?,
    val predB: CalPredict?,
    val fusedPrediction: CalPredict?,
    val fusionWarning: String?,
    val selectedImageRole: String?,
    val selectedImagePath: String?,
    val state: State,
    val imageAState: ImageState,
    val imageBState: ImageState
) {
    /** Aggregate state summarizing the racimo's processing outcome. */
    enum class State {
        /** No images attached to this racimo slot. */
        EMPTY,
        /** Both faces processed and fused successfully. */
        COMPLETE,
        /** Frente image is missing. */
        FRONT_MISSING,
        /** Reverso image is missing. */
        BACK_MISSING,
        /** Frente image failed processing. */
        FRONT_ERROR,
        /** Reverso image failed processing. */
        BACK_ERROR,
        /** Fused result has a quality warning (e.g. high disagreement). */
        LOW_QUALITY,
        /** Both faces present but fusion produced no valid berries. */
        PROCESSING_FAILED,
        /** One or both faces are still being processed. */
        PROCESSING
    }

    /** Per-face image state for UI status badges. */
    enum class ImageState {
        /** Image processed and prediction is valid. */
        VALID,
        /** No image attached for this face. */
        MISSING,
        /** Image processing failed. */
        FAILED,
        /** Image has a quality warning. */
        LOW_QUALITY,
        /** Still processing. */
        PROCESSING
    }

    /** Convenience: true when the fused prediction is valid and usable. */
    val isProcessed: Boolean
        get() = hasValidFusedPrediction

    /**
     * Resolves the most representative image path for compact display.
     * Prefers the fusion-selected role, falls back to any available face.
     */
    val representativeImagePath: String?
        get() = selectedImagePath ?: when (selectedImageRole) {
            "A" -> imageAPath ?: overlayAPath
            "B" -> imageBPath ?: overlayBPath
            else -> imageAPath ?: overlayAPath ?: imageBPath ?: overlayBPath
        }

    /**
     * True when the racimo is COMPLETE or LOW_QUALITY and the fused
     * prediction has a positive berry count.
     */
    val hasValidFusedPrediction: Boolean
        get() = (state == State.COMPLETE || state == State.LOW_QUALITY) &&
            fusedPrediction?.status == true &&
            fusedPrediction.qty > 0
}
