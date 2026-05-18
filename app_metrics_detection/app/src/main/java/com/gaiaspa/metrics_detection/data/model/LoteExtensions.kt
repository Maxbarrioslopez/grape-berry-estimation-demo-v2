package com.gaiaspa.metrics_detection.data.model

import com.gaiaspa.metrics_detection.data.model.request.BatchLoteGrapeRequest
import com.gaiaspa.metrics_detection.data.model.response.CalPredictResponse
import com.gaiaspa.metrics_detection.data.model.response.LoteResponse
import com.gaiaspa.metrics_detection.ml.RuntimeVarietyCatalog
import java.time.Instant

/**
 * Funciones de extensión para mapeo entre modelos de dominio, DTOs de red
 * y respuestas del backend.
 *
 * Centralizan la lógica de conversión evitando esparcirla en ViewModels o
 * repositorios. Las reglas de normalización de variedades se delegan en
 * [RuntimeVarietyCatalog].
 */

/**
 * Convierte un [Lote] local en el DTO [BatchLoteGrapeRequest] requerido
 * por el endpoint de envío al backend.
 *
 * La variedad se normaliza a través de [RuntimeVarietyCatalog.normalize];
 * si no se encuentra en el catálogo, se utiliza el nombre crudo recortado.
 *
 * @return DTO listo para serializar y enviar al backend.
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
 * Convierte una respuesta [LoteResponse] del backend en la entidad local [Lote].
 *
 * La variedad se infiere del campo `variety` del lote o, si está ausente, del
 * `bunchColor` de la primera predicción. En ambos casos se normaliza a través
 * de [RuntimeVarietyCatalog] para obtener un identificador canónico.
 *
 * @param downloadedImages Lista de rutas locales de imágenes ya descargadas
 *                         desde las URLs provistas por el backend.
 * @return Entidad [Lote] lista para insertar en la base de datos local.
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
        normalizedImages = downloadedImages, // Cambiado de images a normalizedImages
        varietyId = varietyId,
        varietyName = varietyName
    )
}

/**
 * Convierte un DTO [CalPredictResponse] del backend en el modelo de dominio [CalPredict].
 *
 * Los campos opcionales de la respuesta se reemplazan por valores por defecto seguros
 * (cadena vacía para `error`, `"Unknown Color"` para `bunchColor`, 0 para `qty`, etc.)
 * para garantizar que la entidad local siempre sea válida.
 *
 * @return [CalPredict] con valores por defecto aplicados donde la respuesta es nula.
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
