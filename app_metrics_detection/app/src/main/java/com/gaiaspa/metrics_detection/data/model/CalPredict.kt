package com.gaiaspa.metrics_detection.data.model

/**
 * Modelo de datos que representa el resultado de calibre de un racimo de uvas.
 *
 * Cada instancia corresponde a una predicción proveniente del backend o del motor
 * local. Es almacenable como JSON dentro de la columna `cal_predicts` de un [Lote]
 * mediante los [Converters] de Room.
 *
 * @property error Mensaje de error en caso de que la predicción fallara.
 * @property status `true` si la predicción fue exitosa.
 * @property bunchColor Color del racimo detectado.
 * @property qty Cantidad de bayas estimadas para el racimo.
 * @property std Desviación estándar del histograma de calibres.
 * @property mean Media del histograma de calibres.
 * @property mode Moda del histograma de calibres.
 * @property pred Conteos por cada bin del histograma.
 * @property bins Límites de bins del histograma de calibres.
 * @property fusionMetadata Metadatos de fusión multivista, si la predicción fue
 *                          generada por [FusionEngine].
 */
data class CalPredict(
    val error: String = "",
    val status: Boolean = false,
    val bunchColor: String = "",
    val qty: Int = 0,
    val std: Float = 0f,
    val mean: Float = 0f,
    val mode: Float = 0f,
    val pred: List<Int> = emptyList(),
    val bins: List<Float> = emptyList(),
    val fusionMetadata: FusionMetadata? = null
)
