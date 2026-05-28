package com.gaiaspa.metrics_detection.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.data.model.Profile

/**
 * AppDatabase - v12 FIXED
 * Version 12: Synchronized with Profile (rut nullable) and Lote (upload_images).
 * Uses fallbackToDestructiveMigration in DatabaseProvider to resolve schema conflicts.
 */
@Database(
    entities = [Profile::class, Lote::class],
    version = 12,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    /**
     * DAO for CRUD and synchronization operations on the [Lote] entity.
     */
    abstract fun loteDao(): LoteDao

    /**
     * DAO for operations on the [Profile] entity (user data).
     */
    abstract fun profileDao(): ProfileDao
}
