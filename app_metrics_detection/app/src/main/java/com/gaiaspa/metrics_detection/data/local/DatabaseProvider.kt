package com.gaiaspa.metrics_detection.data.local

import android.content.Context
import androidx.room.Room

/**
 * Proveedor singleton de la instancia de [AppDatabase].
 *
 * Implementa el patrón double-checked locking para garantizar una única
 * instancia en memoria. Utiliza [fallbackToDestructiveMigration] por lo que
 * los cambios de esquema que no tengan una migración explícita provocarán
 * la destrucción y recreación de la base de datos, perdiendo los datos locales
 * no sincronizados.
 *
 * La migración explícita (comentada) se puede activar cuando se requiera
 * preservar datos entre versiones de esquema.
 */
object DatabaseProvider {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    /**
     * Retorna la instancia única de [AppDatabase], creándola si no existe.
     *
     * Utiliza [Context.applicationContext] para evitar fugas de memoria
     * asociadas a Activities o Services.
     *
     * @param context Contexto de la aplicación (normalmente desde un Activity o Application).
     * @return La instancia singleton de la base de datos.
     */
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
