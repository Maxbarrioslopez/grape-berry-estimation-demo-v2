package com.gaiaspa.metrics_detection.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.gaiaspa.metrics_detection.data.model.CalPredict

/**
 * Conversores de tipos para Room que serializan/deserializan listas
 * a cadenas JSON usando Gson.
 *
 * Registrados en [AppDatabase] mediante la anotación [@TypeConverters].
 * Room invoca automáticamente el conversor adecuado según el tipo de la
 * columna y el tipo Kotlin de la propiedad.
 *
 * Los tipos soportados son:
 * - [List]<[CalPredict]>: columna `cal_predicts` en la entidad [Lote].
 * - [List]<[String]>: columnas `source_images`, `normalized_images`,
 *   `upload_images`, `overlay_images` y `cloudImages` en la entidad [Lote].
 */
class Converters {
    private val gson = Gson()

    /**
     * Serializa una lista de [CalPredict] a una cadena JSON.
     */
    @TypeConverter
    fun fromCalPredictList(calPredicts: List<CalPredict>): String {
        return gson.toJson(calPredicts)
    }

    /**
     * Deserializa una cadena JSON en una lista de [CalPredict].
     */
    @TypeConverter
    fun toCalPredictList(calPredictsString: String): List<CalPredict> {
        val listType = object : TypeToken<List<CalPredict>>() {}.type
        return gson.fromJson(calPredictsString, listType)
    }

    /**
     * Serializa una lista de cadenas a JSON.
     */
    @TypeConverter
    fun fromStringList(strings: List<String>): String {
        return gson.toJson(strings)
    }

    /**
     * Deserializa una cadena JSON en una lista de cadenas.
     */
    @TypeConverter
    fun toStringList(stringsString: String): List<String> {
        val listType = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(stringsString, listType)
    }
}
