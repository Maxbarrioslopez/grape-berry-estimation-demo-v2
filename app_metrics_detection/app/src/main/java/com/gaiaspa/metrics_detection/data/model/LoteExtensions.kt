package com.gaiaspa.metrics_detection.data.model

import com.gaiaspa.metrics_detection.data.model.request.BatchLoteGrapeRequest
import com.gaiaspa.metrics_detection.data.model.response.CalPredictResponse
import com.gaiaspa.metrics_detection.data.model.response.LoteResponse
import com.gaiaspa.metrics_detection.ml.RuntimeVarietyCatalog
import java.time.Instant

/**
 * Extension functions for mapping between domain models, network DTOs,
 * and backend responses.
 *
 * Centralizes conversion logic, avoiding scattering it across ViewModels or
 * repositories. Variety normalization rules are delegated to
 * [RuntimeVarietyCatalog].
 */

/**
 * Converts a local [Lote] into the [BatchLoteGrapeRequest] DTO required
 * by the backend upload endpoint.
 *
 * The variety is normalized through [RuntimeVarietyCatalog.normalize];
 * if not found in the catalog, the raw trimmed name is used.
 *
 * @return DTO ready to serialize and send to the backend.
 */
fun Lote.toBatchLoteGrapeRequest(): BatchLoteGrapeRequest {
    val normalizedVariety = RuntimeVarietyCatalog.normalize(this.varietyName)
        ?: this.varietyName.trim()

    return BatchLoteGrapeRequest(
        userId = this.userId,
        company = this.company,
        vessel = this.vessel,
        block = this.block,
        variety = normalizedVariety,
        // Multi-View Fusion V1 metadata, when present, travels inside each CalPredict.
        // Backend support is intentionally deferred; current backend may ignore it.
        calPredicts = this.calPredicts.toList(),
        predictedAt = this.predictedAt
    )
}

/**
 * Converts a backend [LoteResponse] into the local [Lote] entity.
 *
 * The variety is inferred from the lot's `variety` field or, if absent, from
 * the `bunchColor` of the first prediction. In both cases it is normalized
 * through [RuntimeVarietyCatalog] to obtain a canonical identifier.
 *
 * @param downloadedImages List of local paths of images already downloaded
 *                         from the URLs provided by the backend.
 * @return [Lote] entity ready to insert into the local database.
 */
fun LoteResponse.toLocalLote(downloadedImages: List<String>): Lote {
    val rawVariety = variety?.takeIf { it.isNotBlank() }
        ?: predicts.asSequence()
            .mapNotNull { it.predict.bunchColor?.takeIf(String::isNotBlank) }
            .firstOrNull()

    val canonicalVariety = RuntimeVarietyCatalog.normalize(rawVariety)
    val varietyId = canonicalVariety?.let(RuntimeVarietyCatalog::idOrNull) ?: -1
    val varietyName = when {
        canonicalVariety != null -> RuntimeVarietyCatalog.toUiName(canonicalVariety)
        rawVariety != null -> rawVariety.trim()
        else -> ""
    }

    return Lote(
        predictedAt = Instant.parse(predictedAt).toEpochMilli(),
        cloudImages = predicts.map { it.image.imagePath },
        userId = userId,
        cloudId = loteId,
        vessel = vessel,
        block = block,
        company = company,
        calPredicts = predicts.map { it.predict.toCalpredicts() },
        synced = true,
        toDelete = false,
        toUpdate = false,
        normalizedImages = downloadedImages, // Changed from images to normalizedImages
        varietyId = varietyId,
        varietyName = varietyName
    )
}

/**
 * Converts a backend [CalPredictResponse] DTO into the [CalPredict] domain model.
 *
 * Optional fields in the response are replaced with safe default values
 * (empty string for `error`, `"Unknown Color"` for `bunchColor`, 0 for `qty`, etc.)
 * to guarantee the local entity is always valid.
 *
 * @return [CalPredict] with default values applied where the response is null.
 */
fun CalPredictResponse.toCalpredicts(): CalPredict {
    return CalPredict(
        error = this.error ?: "Unknown Error",
        status = this.status,
        bunchColor = this.bunchColor ?: "Unknown Color",
        qty = this.qty ?: 0,
        std = this.std ?: 0f,
        mean = this.mean ?: 0f,
        mode = this.mode ?: 0f,
        pred = this.pred.toList(),
        bins = this.bins.toList()
    )
}
