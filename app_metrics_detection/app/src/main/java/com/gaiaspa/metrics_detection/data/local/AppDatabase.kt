package com.gaiaspa.metrics_detection.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.data.model.Profile

@Database(
    entities = [Profile::class, Lote::class],
    version = 10, // Incrementado de 9 a 10 para resolver el error de integridad
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun loteDao(): LoteDao
    abstract fun profileDao(): ProfileDao
}
