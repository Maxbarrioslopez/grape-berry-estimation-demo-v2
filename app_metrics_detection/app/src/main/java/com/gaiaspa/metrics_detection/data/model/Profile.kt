package com.gaiaspa.metrics_detection.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Profile - v8.7 FIXED (Null rut crash)
 * Se hace 'rut' opcional (String?) para evitar crashes por NullPointerException
 * durante el mapeo de la respuesta del servidor o inserción en Room.
 */
@Entity(tableName = "profile")
data class Profile(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val userId: String = "undefined",
    val email: String,
    val name: String,
    val lastname: String,
    val role: String,
    val rut: String? = null, // ✅ FIXED: Hecho opcional para evitar NPE
    val isVerified: Boolean,
    val isAvailable: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val photoPath: String?
)
