package com.gaiaspa.metrics_detection.data.model.request

import com.gaiaspa.metrics_detection.data.model.CalPredict
import com.google.gson.annotations.SerializedName

data class BatchLoteGrapeRequest(
    @SerializedName("userId")
    val userId: String = "undefined",

    @SerializedName("company")
    val company: String,

    @SerializedName("vessel")
    val vessel: String,

    @SerializedName("block")
    val block: String,

    @SerializedName("variety")
    val variety: String,

    @SerializedName("calPredicts")
    val calPredicts: List<CalPredict>,

    @SerializedName("predictedAt")
    val predictedAt: Long,  // Fecha de creación local, asignada al instanciar
)
