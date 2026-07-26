package com.example.stayer.pathnet.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Конвертеры Room для списков геометрии.
 * Room converters for geometry collections.
 */
class PathNetConverters {
    private val gson = Gson()

    /**
     * Сериализует список точек в строку.
     * Serializes a list of points to JSON.
     */
    @TypeConverter
    fun fromPoints(points: List<StoredGeoPoint>): String {
        return gson.toJson(points)
    }

    /**
     * Десериализует строку в список точек.
     * Deserializes JSON back into stored points.
     */
    @TypeConverter
    fun toPoints(json: String): List<StoredGeoPoint> {
        val type = object : TypeToken<List<StoredGeoPoint>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    /**
     * Сериализует список строк в JSON.
     * Serializes a string list to JSON.
     */
    @TypeConverter
    fun fromStrings(values: List<String>): String {
        return gson.toJson(values)
    }

    /**
     * Десериализует JSON в список строк.
     * Deserializes JSON into a string list.
     */
    @TypeConverter
    fun toStrings(json: String): List<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }
}
