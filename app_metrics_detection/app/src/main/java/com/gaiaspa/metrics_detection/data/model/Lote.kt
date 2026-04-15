package com.gaiaspa.metrics_detection.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

@Entity(tableName = "lote")
data class Lote(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val userId: String,
    val cloudId: String = "",

    val company: String,
    val vessel: String,
    val block: String,

    val varietyId: Int = -1,
    val varietyName: String = "",

    val predictedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "source_images")
    val sourceImages: List<String> = emptyList(),

    @ColumnInfo(name = "normalized_images")
    val normalizedImages: List<String> = emptyList(),

    @ColumnInfo(name = "overlay_images") // ✅ NUEVO: Para guardar la visualización con contornos
    val overlayImages: List<String> = emptyList(),

    @ColumnInfo(name = "cloudImages")
    val cloudImages: List<String> = emptyList(),

    @ColumnInfo(name = "cal_predicts")
    val calPredicts: List<CalPredict> = emptyList(),

    @ColumnInfo(name = "toDelete")
    val toDelete: Boolean = false,

    @ColumnInfo(name = "toUpdate")
    val toUpdate: Boolean = false,

    @ColumnInfo(name = "synced")
    val synced: Boolean = false,

    @ColumnInfo(name = "syncError")
    val syncError: String? = null
) {
    @get:Ignore
    val images: List<String>
        get() = overlayImages.ifEmpty { normalizedImages.ifEmpty { sourceImages } }
}
