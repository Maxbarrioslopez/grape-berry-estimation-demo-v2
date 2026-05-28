package com.gaiaspa.metrics_detection.data.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Multi-view fusion engine for A/B caliber predictions.
 *
 * Takes two [CalPredict] predictions (Photo A and Photo B of the same cluster)
 * and combines them into a single fused prediction by averaging counts,
 * statistics, and histograms. Disagreement between views is calculated as an
 * internal quality metric.
 *
 * The typical usage flow is to invoke [fusePairwise] with a flat list of
 * predictions where each consecutive pair corresponds to the two views of
 * the same cluster.
 */
object FusionEngine {
    /**
     * [disagreement] keeps the raw QA/debug value for logs and metadata. It is
     * intentionally not part of the user-facing UI.
     *
     * When [warning] is not null, the fused quantity is still the valid A/B
     * average, but pred/bins are intentionally empty. Callers should propagate
     * the warning to logs.
     */
    data class Result(
        val fused: CalPredict,
        val disagreement: Float,
        val warning: String? = null
    ) {
        val disagreementUi: Float = disagreement.coerceIn(0f, 1f)
    }

    /**
     * Fuses two predictions A and B from the same cluster into a single prediction.
     *
     * Averages quantities (`qty`), statistics (`mean`, `std`, `mode`), and
     * histograms (`pred`, `bins`). Disagreement is calculated as the absolute
     * difference between quantities divided by the maximum of `qtyFinal` and 1.
     *
     * If histograms are incompatible (different size, bin boundaries diverging
     * by more than 0.0001f, or empty lists), `pred` and `bins` are emptied in the
     * result and a warning is emitted. The fused quantity and statistics are
     * preserved even in this case.
     *
     * @param a Prediction from Photo A. Must not be null and must have `status = true`.
     * @param b Prediction from Photo B. Must not be null and must have `status = true`.
     * @return [Result] with the fused prediction, disagreement, and an optional warning.
     * @throws IllegalArgumentException if any prediction is null or has `status = false`.
     */
    fun fuse(a: CalPredict?, b: CalPredict?): Result {
        requireNotNull(a) { "Null prediction A" }
        requireNotNull(b) { "Null prediction B" }
        require(a.status) { a.error.ifBlank { "Prediction A with error" } }
        require(b.status) { b.error.ifBlank { "Prediction B with error" } }

        val qtyFinal = ((a.qty + b.qty) / 2f).roundToInt().coerceAtLeast(0)
        val disagreement = abs(a.qty - b.qty).toFloat() / max(qtyFinal, 1)
        val compatibleBins = a.bins.isNotEmpty() &&
            a.bins.size == b.bins.size &&
            a.pred.size == a.bins.size &&
            b.pred.size == b.bins.size &&
            a.bins.zip(b.bins).all { (left, right) -> abs(left - right) < 0.0001f }

        if (!compatibleBins) {
            return Result(
                fused = CalPredict(
                    status = true,
                    bunchColor = a.bunchColor.ifBlank { b.bunchColor },
                    qty = qtyFinal,
                    mean = ((a.mean + b.mean) / 2f),
                    std = ((a.std + b.std) / 2f),
                    mode = ((a.mode + b.mode) / 2f),
                    pred = emptyList(),
                    bins = emptyList()
                ),
                disagreement = disagreement,
                warning = "Insufficient or incompatible histogram; qtyFinal preserved and stats fallback used"
            )
        }

        val predFinal = a.pred.zip(b.pred).map { (left, right) ->
            ((left.coerceAtLeast(0) + right.coerceAtLeast(0)) / 2f).roundToInt()
        }

        return Result(
            fused = CalPredict(
                status = true,
                bunchColor = a.bunchColor.ifBlank { b.bunchColor },
                qty = qtyFinal,
                mean = ((a.mean + b.mean) / 2f),
                std = ((a.std + b.std) / 2f),
                mode = ((a.mode + b.mode) / 2f),
                pred = predFinal,
                bins = a.bins
            ),
            disagreement = disagreement
        )
    }

    /**
     * Individual fusion result for a cluster within a lot.
     *
     * @property prediction Fused prediction ready for storage/display.
     * @property group Detailed metadata of the fused pair.
     * @property fusionResult Raw result from [fuse] with disagreement and warning.
     */
    data class PairwiseResult(
        val prediction: CalPredict,
        val group: FusionGroupMetadata,
        val fusionResult: Result
    )

    /**
     * Fuses a flat list of predictions by pairing them sequentially in twos.
     *
     * Each pair (indices 0-1, 2-3, ...) is interpreted as views A and B of the same
     * cluster. The input list must have an even length.
     *
     * @param predictions Flat list of predictions to fuse in consecutive pairs.
     * @return List of [PairwiseResult], one per fused cluster.
     * @throws IllegalArgumentException if the list has an odd length.
     */
    fun fusePairwise(predictions: List<CalPredict>): List<PairwiseResult> {
        require(predictions.size % 2 == 0) { "Each cluster must have Photo A and Photo B" }

        val partial = predictions.chunked(2).mapIndexed { clusterIndex, pair ->
            require(pair.size == 2) { "Each cluster must have Photo A and Photo B" }
            val predA = pair[0]
            val predB = pair[1]
            require(predA.status) { predA.error.ifBlank { "Photo A of cluster ${clusterIndex + 1} has an error" } }
            require(predB.status) { predB.error.ifBlank { "Photo B of cluster ${clusterIndex + 1} has an error" } }

            val result = fuse(predA, predB)
            val group = FusionGroupMetadata(
                racimoIndex = clusterIndex + 1,
                viewAImageIndex = clusterIndex * 2,
                viewBImageIndex = clusterIndex * 2 + 1,
                fusedPredictionIndex = clusterIndex,
                qtyA = predA.qty,
                qtyB = predB.qty,
                qtyFinal = result.fused.qty,
                disagreement = result.disagreement,
                disagreementUi = result.disagreementUi,
                meanA = predA.mean,
                meanB = predB.mean,
                meanFinal = result.fused.mean,
                modeA = predA.mode,
                modeB = predB.mode,
                modeFinal = result.fused.mode,
                stdA = predA.std,
                stdB = predB.std,
                stdFinal = result.fused.std,
                warning = result.warning
            )
            result to group
        }

        val metadata = FusionMetadata(groups = partial.map { it.second })
        return partial.map { (result, group) ->
            PairwiseResult(
                prediction = result.fused.copy(fusionMetadata = metadata),
                group = group,
                fusionResult = result
            )
        }
    }

}
