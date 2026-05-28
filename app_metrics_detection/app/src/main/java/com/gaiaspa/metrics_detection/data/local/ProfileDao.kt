package com.gaiaspa.metrics_detection.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gaiaspa.metrics_detection.data.model.Profile

@Dao
interface ProfileDao {

    /**
     * Inserts or updates the profile. There will always be only one profile.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertProfile(profile: Profile)

    /**
     * Retrieves the existing profile.
     *
     * @return The profile or null if it does not exist.
     */
    @Query("SELECT * FROM profile LIMIT 1")
    fun getProfile(): Profile?

    /**
     * Deletes the existing profile.
     */
    @Query("DELETE FROM profile")
    fun deleteProfile()
}
