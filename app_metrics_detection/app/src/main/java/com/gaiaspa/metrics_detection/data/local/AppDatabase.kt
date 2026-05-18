package com.gaiaspa.metrics_detection.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.data.model.Profile

/**
 * AppDatabase - v12 FIXED
 * Versión 12: Sincronizada con Profile (rut nullable) y Lote (upload_images).
 * Utiliza fallbackToDestructiveMigration en DatabaseProvider para resolver conflictos de esquema.
 */
@Database(
    entities = [Profile::class, Lote::class],
    version = 12,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    /**
     * DAO para operaciones CRUD y de sincronización sobre la entidad [Lote].
     */
    abstract fun loteDao(): LoteDao

    /**
     * DAO para operaciones sobre la entidad [Profile] (datos del usuario).
     */
    abstract fun profileDao(): ProfileDao
}
