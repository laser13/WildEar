package com.sound2inat.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [DraftEntity::class, DetectionEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class Sound2iNatDb : RoomDatabase() {
    abstract fun drafts(): DraftDao
    abstract fun detections(): DetectionDao
}
