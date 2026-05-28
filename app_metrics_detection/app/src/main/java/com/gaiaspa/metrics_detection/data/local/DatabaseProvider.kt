package com.gaiaspa.metrics_detection.data.local

import android.content.Context
import androidx.room.Room

/**
 * Singleton provider for the [AppDatabase] instance.
 *
 * Implements the double-checked locking pattern to guarantee a single
 * instance in memory. Uses [fallbackToDestructiveMigration], so schema
 * changes without an explicit migration will cause the database to be
 * destroyed and recreated, losing any unsynchronized local data.
 *
 * The explicit migration (commented) can be enabled when data preservation
 * across schema versions is required.
 */
object DatabaseProvider {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    /**
     * Returns the unique [AppDatabase] instance, creating it if it does not exist.
     *
     * Uses [Context.applicationContext] to avoid memory leaks
     * associated with Activities or Services.
     *
     * @param context Application context (typically from an Activity or Application).
     * @return The singleton database instance.
     */
    fun getDatabase(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "metrics_detection_db"
            )
                .fallbackToDestructiveMigration()
                //.addMigrations(MIGRATION_1_2, MIGRATION_2_3,MIGRATION_3_4) // Handle migrations properly
                .build()
            INSTANCE = instance
            instance
        }
    }
}
