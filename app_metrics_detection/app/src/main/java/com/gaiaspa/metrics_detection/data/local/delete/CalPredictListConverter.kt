package com.gaiaspa.metrics_detection.data.local.delete
import androidx.room.TypeConverter
import com.gaiaspa.metrics_detection.data.model.CalPredict
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken



class CalPredictListConverter {

    @TypeConverter
    fun fromListCalPredict(value: List<CalPredict>?): String {
        if (value == null) return "[]"
        val gson = Gson()
        return gson.toJson(value)
    }

    @TypeConverter
    fun toListCalPredict(value: String): List<CalPredict> {
        if (value.isBlank()) return emptyList()
        val gson = Gson()
        val type = object : TypeToken<List<CalPredict>>() {}.type
        return gson.fromJson(value, type)
    }
}