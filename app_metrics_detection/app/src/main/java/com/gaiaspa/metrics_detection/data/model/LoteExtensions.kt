package com.gaiaspa.metrics_detection.data.model

import com.gaiaspa.metrics_detection.data.model.request.BatchLoteGrapeRequest
import com.gaiaspa.metrics_detection.data.model.response.CalPredictResponse
import com.gaiaspa.metrics_detection.data.model.response.LoteResponse
import com.gaiaspa.metrics_detection.ml.RuntimeVarietyCatalog
import java.time.Instant

fun Lote.toBatchLoteGrapeRequest(): BatchLoteGrapeRequest {
    val normalizedVariety = RuntimeVarietyCatalog.normalize(this.varietyName)
        ?: this.varietyName.trim()

    return BatchLoteGrapeRequest(
        userId = this.userId,
        company = this.company,
        vessel = this.vessel,
        block = this.block,
        variety = normalizedVariety,
        calPredicts = this.calPredicts.toList(),
        predictedAt = this.predictedAt
    )
}

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
        normalizedImages = downloadedImages, // Cambiado de images a normalizedImages
        varietyId = varietyId,
        varietyName = varietyName
    )
}

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
