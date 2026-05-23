package com.sound2inat.storage

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromStatus(s: DraftStatus): String = s.name

    @TypeConverter
    fun toStatus(v: String): DraftStatus = DraftStatus.valueOf(v)

    @TypeConverter
    fun fromPhotoStatus(s: PhotoDraftStatus): String = s.name

    @TypeConverter
    fun toPhotoStatus(v: String): PhotoDraftStatus = PhotoDraftStatus.valueOf(v)

    @TypeConverter
    fun fromInatUploadStatus(value: InatUploadStatus): String = value.name

    @TypeConverter
    fun toInatUploadStatus(value: String): InatUploadStatus =
        runCatching { InatUploadStatus.valueOf(value) }
            .getOrDefault(InatUploadStatus.COMPLETE)
}
