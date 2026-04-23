package com.gaiaspa.metrics_detection.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

/**
 * Lote - v11.0 ARCHITECTURAL FIX
 * Se redefine la propiedad 'images' para priorizar el overlay visual (res_)
 * generado por C++ sobre la imagen limpia de subida.
 */
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

    @ColumnInfo(name = "upload_images") 
    val uploadImages: List<String> = emptyList(), 

    @ColumnInfo(name = "overlay_images")
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
    /**
     * Propiedad dinámica para UI.
     * ✅ FIXED: Prioriza overlayImages (res_ con óvalos nativos)
     * para que el usuario siempre vea las detecciones en el historial.
     */
    @get:Ignore
    val images: List<String>
        get() = overlayImages.ifEmpty { uploadImages.ifEmpty { normalizedImages.ifEmpty { sourceImages } } }
}
