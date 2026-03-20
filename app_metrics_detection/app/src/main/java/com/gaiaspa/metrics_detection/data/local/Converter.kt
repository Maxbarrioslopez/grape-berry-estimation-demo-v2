package com.gaiaspa.metrics_detection.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.gaiaspa.metrics_detection.data.model.CalPredict

class Converters {
    private val gson = Gson()

    // Converters para List<CalPredict>
    @TypeConverter
    fun fromCalPredictList(calPredicts: List<CalPredict>): String {
        return gson.toJson(calPredicts)
    }

    @TypeConverter
    fun toCalPredictList(calPredictsString: String): List<CalPredict> {
        val listType = object : TypeToken<List<CalPredict>>() {}.type
        return gson.fromJson(calPredictsString, listType)
    }

    // Converters para List<String>
    @TypeConverter
    fun fromStringList(strings: List<String>): String {
        return gson.toJson(strings)
    }

    @TypeConverter
    fun toStringList(stringsString: String): List<String> {
        val listType = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(stringsString, listType)
    }
}
