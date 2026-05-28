package com.gaiaspa.metrics_detection.data.model.response

import com.google.gson.annotations.SerializedName

data class LoteResponse(
    @SerializedName("_id")
    val loteId: String,

    @SerializedName("userId")
    val userId: String,

    @SerializedName("company")
    val company: String,

    @SerializedName("vessel")
    val vessel: String,

    @SerializedName("block")
    val block: String,

    @SerializedName("variety")
    val variety: String? = null,

    @SerializedName("predicts")
    val predicts: List<PredictItem>,

    @SerializedName("createdAt")
    val createdAt: String,

    @SerializedName("updatedAt")
    val updatedAt: String,

    @SerializedName("predictedAt")
    val predictedAt: String

)

data class PredictItem(
    @SerializedName("image")
    val image: ImageResponse,
    @SerializedName("predict")
    val predict: CalPredictResponse
)

data class ImageResponse(
    @SerializedName("imagePath")
    val imagePath: String,
    @SerializedName("imageKey")
    val imageKey: String
)

data class CalPredictResponse(
    @SerializedName("status")
    val status: Boolean,

    @SerializedName("error")
    val error: String?,

    @SerializedName("bunchColor")
    val bunchColor: String?,

    @SerializedName("qty")
    val qty: Int?,

    @SerializedName("std")
    val std: Float?,

    @SerializedName("mean")
    val mean: Float?,

    @SerializedName("mode")
    val mode: Float?,

    @SerializedName("pred")
    val pred: List<Int> = emptyList(),

    @SerializedName("bins")
    val bins: List<Float> = emptyList(),

    @SerializedName("predictedAt")
    val predictedAt: Long,  // Local creation date, assigned on instantiation

    @SerializedName("createdAt")
    val createdAt: Long,  // Local creation date, assigned on instantiation

    @SerializedName("updatedAt")
    val updatedAt: Long,   // May be updated later, both locally and on sync
)
