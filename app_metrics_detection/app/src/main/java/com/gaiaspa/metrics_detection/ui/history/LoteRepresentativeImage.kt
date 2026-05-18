package com.gaiaspa.metrics_detection.ui.history

import com.gaiaspa.metrics_detection.data.model.CalPredict
import com.gaiaspa.metrics_detection.data.model.Lote
import java.io.File

private fun CalPredict.fusionGroupForIndex(index: Int) =
    fusionMetadata?.groups?.firstOrNull { it.fusedPredictionIndex == index }
        ?: fusionMetadata?.groups?.getOrNull(index)

fun Lote.representativeImagePathForPrediction(index: Int): String? {
    val prediction = calPredicts.getOrNull(index)
    val group = prediction?.fusionGroupForIndex(index)
    val metadataPath = when (group?.selectedImageRole) {
        "B" -> firstExistingPath(group.viewBOverlayPath, group.viewBUploadPath, group.viewBSourcePath)
        "A" -> firstExistingPath(group.viewAOverlayPath, group.viewAUploadPath, group.viewASourcePath)
        else -> null
    }
    if (!metadataPath.isNullOrBlank()) return metadataPath

    val candidates = images
    val imageIndex = when {
        candidates.size == calPredicts.size -> index
        candidates.size == calPredicts.size * 2 -> index * 2
        else -> index
    }
    return candidates.getOrNull(imageIndex)?.takeIf { it.isNotBlank() }
        ?: candidates.firstOrNull { it.isNotBlank() }
}

fun Lote.representativeImagePath(): String? {
    calPredicts.indices.forEach { index ->
        representativeImagePathForPrediction(index)?.let { return it }
    }
    return images.firstOrNull { it.isNotBlank() }
}

private fun firstExistingPath(vararg paths: String?): String? {
    return paths.firstOrNull { path ->
        val cleanPath = path?.replace("file://", "").orEmpty()
        cleanPath.isNotBlank() && File(cleanPath).exists()
    } ?: paths.firstOrNull { !it.isNullOrBlank() }
}
