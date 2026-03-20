package com.gaiaspa.metrics_detection.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "profile",
)
data class Profile(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0, // ID autogenerado
    val userId: String = "undefined",
    val email: String,
    val name: String,
    val lastname: String,
    val role: String,
    val rut: String,
    val isVerified: Boolean,
    val isAvailable: Boolean,
    val createdAt: Long = System.currentTimeMillis(),  // Fecha de creación local, asignada al instanciar
    val updatedAt: Long = System.currentTimeMillis(),   // Puede actualizarse posteriormente, tanto localmente como al sincronizar
    val photoPath: String? // Ruta local o URL de la foto de perfil
)
