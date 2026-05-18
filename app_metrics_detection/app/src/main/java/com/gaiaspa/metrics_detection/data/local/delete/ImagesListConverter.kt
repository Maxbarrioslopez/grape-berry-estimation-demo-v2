package com.gaiaspa.metrics_detection.data.local.delete

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ImagesListConverter {

    @TypeConverter
    fun fromListImages(value: List<String>?): String {
        if (value == null) return "[]"
        val gson = Gson()
        return gson.toJson(value)
    }

    @TypeConverter
    fun toListImages(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val gson = Gson()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, type)
    }
}