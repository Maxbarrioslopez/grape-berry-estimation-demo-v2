package com.gaiaspa.metrics_detection.data.local.delete

import androidx.room.TypeConverter

class ListIntConverter {
    @TypeConverter
    fun fromListInt(value: List<Int>?): String {
        return value?.joinToString(",") ?: ""
    }

    @TypeConverter
    fun toListInt(value: String): List<Int> {
        if (value.isEmpty()) return emptyList()
        return value.split(",").map { it.toInt() }
    }
}