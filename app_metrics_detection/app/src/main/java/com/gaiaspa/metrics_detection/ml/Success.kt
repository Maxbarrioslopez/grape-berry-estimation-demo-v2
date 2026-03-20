package com.gaiaspa.metrics_detection.ml
import android.graphics.Bitmap

data class Success(
    val preProcessTime: Long,
    val interfaceTime: Long,
    val postProcessTime: Long,
    val results: List<SegmentationResult>,
    val depthMap: Bitmap?,
    val mmPerPx: Float,
    val predictsList: List<com.gaiaspa.metrics_detection.data.model.CalPredict>,
    val imageOrig: Bitmap,
    val imagePro: Pair<Bitmap, Bitmap?>

)