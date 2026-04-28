package com.sound2inat.storage

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromStatus(s: DraftStatus): String = s.name

    @TypeConverter
    fun toStatus(v: String): DraftStatus = DraftStatus.valueOf(v)
}
