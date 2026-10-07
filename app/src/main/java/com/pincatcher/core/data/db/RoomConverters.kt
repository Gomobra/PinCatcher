package com.pincatcher.core.data.db

import androidx.room.TypeConverter

/**
 * Room has no converters to register yet - every column is a primitive or
 * String. This exists so the `@TypeConverters` reference on the database
 * stays valid and later additions have an obvious home.
 */
object RoomConverters {
    @TypeConverter
    fun stringListToString(value: List<String>?): String? =
        value?.takeIf { it.isNotEmpty() }?.joinToString(",")

    @TypeConverter
    fun stringToStringList(value: String?): List<String>? =
        value?.takeIf { it.isNotBlank() }?.split(",")
}
