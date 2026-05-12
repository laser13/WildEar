package com.sound2inat.storage

import androidx.room.Embedded
import androidx.room.Relation

data class PhotoDraftWithImages(
    @Embedded val draft: PhotoDraftEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "photoDraftId",
    )
    val images: List<PhotoDraftImageEntity>,
)
