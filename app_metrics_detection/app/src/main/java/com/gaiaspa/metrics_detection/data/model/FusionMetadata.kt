package com.gaiaspa.metrics_detection.data.model

/**
 * Aggregated metadata of the A/B multi-view fusion process.
 *
 * Describes the fusion algorithm version, the grouping rule used, and the
 * results for each pair of views (Photo A / Photo B). Embedded in each fused
 * [CalPredict] for full traceability.
 *
 * @property fusionVersion Identifier for the fusion engine version.
 * @property groupingRule Rule applied to pair the views (always
 *                        `pairwise_chronological` in the current implementation).
 * @property groups Individual metadata per fused cluster.
 */
data class FusionMetadata(
    val fusionVersion: String = "multiview_v1",
    val groupingRule: String = "pairwise_chronological",
    val groups: List<FusionGroupMetadata> = emptyList()
)

/**
 * Metadata per cluster processed during A/B fusion.
 *
 * Records the original values of each view, the fused result, and the paths
 * of the source/upload/overlay images for debugging and auditing.
 *
 * @property racimoIndex 1-based index of the cluster within the lot.
 * @property viewAImageIndex Index of the image corresponding to Photo A.
 * @property viewBImageIndex Index of the image corresponding to Photo B.
 * @property fusedPredictionIndex Index of the resulting fused prediction.
 * @property qtyA Estimated berry count by Photo A.
 * @property qtyB Estimated berry count by Photo B.
 * @property qtyFinal Fused quantity (rounded average of A and B).
 * @property disagreement Raw disagreement between A and B (absolute value / qtyFinal).
 * @property disagreementUi Disagreement clamped to [0,1] for UI display.
 * @property meanA Mean of the Photo A histogram.
 * @property meanB Mean of the Photo B histogram.
 * @property meanFinal Fused mean (average of A and B).
 * @property modeA Mode of the Photo A histogram.
 * @property modeB Mode of the Photo B histogram.
 * @property modeFinal Fused mode (average of A and B).
 * @property stdA Standard deviation of Photo A.
 * @property stdB Standard deviation of Photo B.
 * @property stdFinal Fused standard deviation (average of A and B).
 * @property warning Warning generated during fusion (e.g., incompatible histograms).
 * @property originalViewAImageIndex Original index of Photo A before reordering.
 * @property originalViewBImageIndex Original index of Photo B before reordering.
 * @property selectedImageRole Role of the selected image for visual representation.
 * @property viewASourcePath Local path of the Photo A source file.
 * @property viewBSourcePath Local path of the Photo B source file.
 * @property viewAUploadPath Upload path of Photo A.
 * @property viewBUploadPath Upload path of Photo B.
 * @property viewAOverlayPath Path of the visual overlay generated for Photo A.
 * @property viewBOverlayPath Path of the visual overlay generated for Photo B.
 */
data class FusionGroupMetadata(
    val racimoIndex: Int,
    val viewAImageIndex: Int,
    val viewBImageIndex: Int,
    val fusedPredictionIndex: Int,
    val qtyA: Int,
    val qtyB: Int,
    val qtyFinal: Int,
    val disagreement: Float,
    val disagreementUi: Float,
    val meanA: Float,
    val meanB: Float,
    val meanFinal: Float,
    val modeA: Float,
    val modeB: Float,
    val modeFinal: Float,
    val stdA: Float,
    val stdB: Float,
    val stdFinal: Float,
    val warning: String? = null,
    val originalViewAImageIndex: Int? = null,
    val originalViewBImageIndex: Int? = null,
    val selectedImageRole: String? = null,
    val viewASourcePath: String? = null,
    val viewBSourcePath: String? = null,
    val viewAUploadPath: String? = null,
    val viewBUploadPath: String? = null,
    val viewAOverlayPath: String? = null,
    val viewBOverlayPath: String? = null
)
