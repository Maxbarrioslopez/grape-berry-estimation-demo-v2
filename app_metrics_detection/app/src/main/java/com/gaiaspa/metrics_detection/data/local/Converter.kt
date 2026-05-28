package com.gaiaspa.metrics_detection.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.gaiaspa.metrics_detection.data.model.CalPredict

/**
 * Type converters for Room that serialize/deserialize lists
 * to JSON strings using Gson.
 *
 * Registered in [AppDatabase] via the [@TypeConverters] annotation.
 * Room automatically invokes the appropriate converter based on the
 * column type and the Kotlin property type.
 *
 * Supported types:
 * - [List]<[CalPredict]>: `cal_predicts` column in the [Lote] entity.
 * - [List]<[String]>: `source_images`, `normalized_images`,
 *   `upload_images`, `overlay_images`, and `cloudImages` columns in the [Lote] entity.
 */
class Converters {
    private val gson = Gson()

    /**
     * Serializes a list of [CalPredict] to a JSON string.
     */
    @TypeConverter
    fun fromCalPredictList(calPredicts: List<CalPredict>): String {
        return gson.toJson(calPredicts)
    }

    /**
     * Deserializes a JSON string into a list of [CalPredict].
     */
    @TypeConverter
    fun toCalPredictList(calPredictsString: String): List<CalPredict> {
        val listType = object : TypeToken<List<CalPredict>>() {}.type
        return gson.fromJson(calPredictsString, listType)
    }

    /**
     * Serializes a list of strings to JSON.
     */
    @TypeConverter
    fun fromStringList(strings: List<String>): String {
        return gson.toJson(strings)
    }

    /**
     * Deserializes a JSON string into a list of strings.
     */
    @TypeConverter
    fun toStringList(stringsString: String): List<String> {
        val listType = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(stringsString, listType)
    }
}
