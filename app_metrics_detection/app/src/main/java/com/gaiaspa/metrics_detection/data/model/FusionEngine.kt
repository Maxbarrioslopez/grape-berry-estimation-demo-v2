package com.gaiaspa.metrics_detection.data.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Motor de fusión multivista para predicciones de calibre A/B.
 *
 * Toma dos predicciones [CalPredict] (Foto A y Foto B del mismo racimo) y
 * las combina en una única predicción fusionada promediando cantidades,
 * estadísticas e histogramas. El desacuerdo entre vistas se calcula como métrica
 * de calidad interna.
 *
 * El flujo típico de uso es invocar [fusePairwise] con una lista plana de
 * predicciones donde cada par consecutivo corresponde a las dos vistas de un
 * mismo racimo.
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
     * Fusiona dos predicciones A y B del mismo racimo en una única predicción.
     *
     * Promedia cantidades (`qty`), estadísticas (`mean`, `std`, `mode`) e
     * histogramas (`pred`, `bins`). El desacuerdo se calcula como la diferencia
     * absoluta entre las cantidades dividida por el máximo entre `qtyFinal` y 1.
     *
     * Si los histogramas no son compatibles (distinto tamaño, bins divergentes
     * por más de 0.0001f, o listas vacías), `pred` y `bins` se vacían en el
     * resultado y se emite una advertencia. La cantidad fusionada y las
     * estadísticas se preservan incluso en este caso.
     *
     * @param a Predicción de la Foto A. No debe ser nula y debe tener `status = true`.
     * @param b Predicción de la Foto B. No debe ser nula y debe tener `status = true`.
     * @return [Result] con la predicción fusionada, el desacuerdo y una advertencia opcional.
     * @throws IllegalArgumentException si alguna predicción es nula o tiene `status = false`.
     */
    fun fuse(a: CalPredict?, b: CalPredict?): Result {
        requireNotNull(a) { "Prediccion A nula" }
        requireNotNull(b) { "Prediccion B nula" }
        require(a.status) { a.error.ifBlank { "Prediccion A con error" } }
        require(b.status) { b.error.ifBlank { "Prediccion B con error" } }

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
                warning = "Histograma insuficiente o incompatible; se conserva qtyFinal y se usa fallback de stats"
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
     * Resultado individual de la fusión de un racimo dentro de un lote.
     *
     * @property prediction Predicción fusionada lista para almacenar/visualizar.
     * @property group Metadatos detallados del par fusionado.
     * @property fusionResult Resultado crudo de [fuse] con el desacuerdo y la advertencia.
     */
    data class PairwiseResult(
        val prediction: CalPredict,
        val group: FusionGroupMetadata,
        val fusionResult: Result
    )

    /**
     * Fusiona una lista plana de predicciones emparejándolas secuencialmente de a dos.
     *
     * Cada par (índices 0-1, 2-3, ...) se interpreta como las vistas A y B de un mismo
     * racimo. La lista de entrada debe tener longitud par.
     *
     * @param predictions Lista plana de predicciones a fusionar por pares consecutivos.
     * @return Lista de [PairwiseResult], uno por cada racimo fusionado.
     * @throws IllegalArgumentException si la lista tiene longitud impar.
     */
    fun fusePairwise(predictions: List<CalPredict>): List<PairwiseResult> {
        require(predictions.size % 2 == 0) { "Cada racimo debe tener Foto A y Foto B" }

        val partial = predictions.chunked(2).mapIndexed { racimoIndex, pair ->
            require(pair.size == 2) { "Cada racimo debe tener Foto A y Foto B" }
            val predA = pair[0]
            val predB = pair[1]
            require(predA.status) { predA.error.ifBlank { "La Foto A del racimo ${racimoIndex + 1} tiene error" } }
            require(predB.status) { predB.error.ifBlank { "La Foto B del racimo ${racimoIndex + 1} tiene error" } }

            val result = fuse(predA, predB)
            val group = FusionGroupMetadata(
                racimoIndex = racimoIndex + 1,
                viewAImageIndex = racimoIndex * 2,
                viewBImageIndex = racimoIndex * 2 + 1,
                fusedPredictionIndex = racimoIndex,
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
