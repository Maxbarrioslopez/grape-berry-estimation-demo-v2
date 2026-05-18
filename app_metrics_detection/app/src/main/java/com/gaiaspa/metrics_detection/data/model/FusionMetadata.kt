package com.gaiaspa.metrics_detection.data.model

/**
 * Metadatos agregados del proceso de fusión multivista A/B.
 *
 * Describe la versión del algoritmo de fusión, la regla de agrupamiento usada
 * y los resultados por cada par de vistas (Foto A / Foto B). Se incrusta en
 * cada [CalPredict] fusionado para trazabilidad completa.
 *
 * @property fusionVersion Identificador de la versión del motor de fusión.
 * @property groupingRule Regla aplicada para emparejar las vistas (siempre
 *                        `pairwise_chronological` en la implementación actual).
 * @property groups Metadatos individuales por cada racimo fusionado.
 */
data class FusionMetadata(
    val fusionVersion: String = "multiview_v1",
    val groupingRule: String = "pairwise_chronological",
    val groups: List<FusionGroupMetadata> = emptyList()
)

/**
 * Metadatos por cada racimo procesado durante la fusión A/B.
 *
 * Registra los valores originales de cada vista, el resultado fusionado y las
 * rutas de las imágenes fuente/subida/overlay para depuración y auditoría.
 *
 * @property racimoIndex Índice 1-based del racimo dentro del lote.
 * @property viewAImageIndex Índice de la imagen correspondiente a la Foto A.
 * @property viewBImageIndex Índice de la imagen correspondiente a la Foto B.
 * @property fusedPredictionIndex Índice de la predicción fusionada resultante.
 * @property qtyA Cantidad de bayas estimadas por la Foto A.
 * @property qtyB Cantidad de bayas estimadas por la Foto B.
 * @property qtyFinal Cantidad fusionada (promedio redondeado de A y B).
 * @property disagreement Desacuerdo bruto entre A y B (valor absoluto / qtyFinal).
 * @property disagreementUi Desacuerdo acotado a [0,1] para presentación en UI.
 * @property meanA Mean del histograma de la Foto A.
 * @property meanB Mean del histograma de la Foto B.
 * @property meanFinal Mean fusionada (promedio de A y B).
 * @property modeA Moda del histograma de la Foto A.
 * @property modeB Moda del histograma de la Foto B.
 * @property modeFinal Moda fusionada (promedio de A y B).
 * @property stdA Desviación estándar de la Foto A.
 * @property stdB Desviación estándar de la Foto B.
 * @property stdFinal Desviación estándar fusionada (promedio de A y B).
 * @property warning Advertencia generada durante la fusión (ej. histogramas incompatibles).
 * @property originalViewAImageIndex Índice original de la Foto A antes de reordenamiento.
 * @property originalViewBImageIndex Índice original de la Foto B antes de reordenamiento.
 * @property selectedImageRole Rol de la imagen seleccionada para representación visual.
 * @property viewASourcePath Ruta local del archivo fuente de la Foto A.
 * @property viewBSourcePath Ruta local del archivo fuente de la Foto B.
 * @property viewAUploadPath Ruta de subida de la Foto A.
 * @property viewBUploadPath Ruta de subida de la Foto B.
 * @property viewAOverlayPath Ruta del overlay visual generado para la Foto A.
 * @property viewBOverlayPath Ruta del overlay visual generado para la Foto B.
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
