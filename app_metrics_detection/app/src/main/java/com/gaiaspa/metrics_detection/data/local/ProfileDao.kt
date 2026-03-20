package com.gaiaspa.metrics_detection.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gaiaspa.metrics_detection.data.model.Profile

@Dao
interface ProfileDao {

    /**
     * Inserta o actualiza el perfil. Siempre habrá solo un perfil.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertProfile(profile: Profile)

    /**
     * Obtiene el perfil existente.
     *
     * @return El perfil o null si no existe.
     */
    @Query("SELECT * FROM profile LIMIT 1")
    fun getProfile(): Profile?

    /**
     * Elimina el perfil existente.
     */
    @Query("DELETE FROM profile")
    fun deleteProfile()
}
