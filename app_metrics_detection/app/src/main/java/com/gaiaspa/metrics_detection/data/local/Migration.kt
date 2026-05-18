// Migration.kt
package com.gaiaspa.metrics_detection.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Agregar la nueva columna 'toDelete' con valor por defecto 0 (false)
        database.execSQL("ALTER TABLE Lote ADD COLUMN toDelete INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Crear la tabla 'profile'
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `profile` (
                `email` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `lastname` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `rut` TEXT NOT NULL,
                `isVerified` INTEGER NOT NULL,
                `isAvailable` INTEGER NOT NULL,
                `created_at` TEXT NOT NULL,
                `updated_at` TEXT NOT NULL,
                `photo_path` TEXT,
                PRIMARY KEY(`email`)
            )
            """.trimIndent()
        )
    }
}
//val MIGRATION_3_4 = object : Migration(3, 4) {
//    override fun migrate(database: SupportSQLiteDatabase) {
//        database.beginTransaction()
//        try {
//            // Sentencias SQL de migración
//            database.execSQL("ALTER TABLE Lote ADD COLUMN userId TEXT NOT NULL DEFAULT ''")
//            database.execSQL("ALTER TABLE Lote ADD COLUMN userId TEXT NOT NULL DEFAULT ''")
//            database.setTransactionSuccessful()
//        } finally {
//            database.endTransaction()
//        }
//    }
//}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.beginTransaction()
        try {
            // Añadir la columna 'userId' a la tabla 'Lote' con valor por defecto ''
            database.execSQL(
                "ALTER TABLE Lote ADD COLUMN userId TEXT NOT NULL DEFAULT 'undefined'"
            )

            // Añadir la columna 'userId' a la tabla 'Profile' con valor por defecto ''
            database.execSQL(
                "ALTER TABLE Profile ADD COLUMN userId TEXT NOT NULL DEFAULT 'undefined'"
            )

            // Crear el índice único en 'userId' en la tabla 'Profile'
            //database.execSQL(
            //    "CREATE UNIQUE INDEX IF NOT EXISTS index_profile_userId ON Profile(userId ASC)"
            //)

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }
}
