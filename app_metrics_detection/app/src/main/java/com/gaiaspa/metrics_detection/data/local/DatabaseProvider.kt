package com.gaiaspa.metrics_detection.data.local

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "metrics_detection_db"
            )
                .fallbackToDestructiveMigration()
                //.addMigrations(MIGRATION_1_2, MIGRATION_2_3,MIGRATION_3_4) // Maneja migraciones adecuadamente
                .build()
            INSTANCE = instance
            instance
        }
    }
}
