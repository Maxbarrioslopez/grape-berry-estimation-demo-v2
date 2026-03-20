package com.gaiaspa.metrics_detection.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.data.model.Profile

@Database(
    entities = [Profile::class, Lote::class],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun loteDao(): LoteDao

}
