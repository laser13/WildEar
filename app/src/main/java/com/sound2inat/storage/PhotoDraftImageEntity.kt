package com.sound2inat.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photo_draft_images",
    foreignKeys = [
        ForeignKey(
            entity = PhotoDraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["photoDraftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("photoDraftId")],
)
data class PhotoDraftImageEntity(
    @PrimaryKey val id: String,
    val photoDraftId: String,
    val photoPath: String,
    val takenAtUtcMs: Long,
    val sortOrder: Int,
    val width: Int?,
    val height: Int?,
    val mimeType: String = "image/jpeg",
)
