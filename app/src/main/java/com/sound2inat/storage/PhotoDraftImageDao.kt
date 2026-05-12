package com.sound2inat.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PhotoDraftImageDao {
    @Insert
    fun insert(image: PhotoDraftImageEntity)

    @Query("SELECT * FROM photo_draft_images WHERE id = :id LIMIT 1")
    fun getById(id: String): PhotoDraftImageEntity?

    @Query("DELETE FROM photo_draft_images WHERE id = :id")
    fun deleteById(id: String): Int

    @Query("DELETE FROM photo_draft_images WHERE photoDraftId = :draftId")
    fun deleteByDraftId(draftId: String): Int

    @Query("SELECT * FROM photo_draft_images WHERE photoDraftId = :draftId ORDER BY sortOrder ASC, takenAtUtcMs ASC")
    fun listForDraft(draftId: String): List<PhotoDraftImageEntity>
}
