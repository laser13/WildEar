package com.sound2inat.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftPhotoDao {
    @Insert
    fun insert(photo: DraftPhotoEntity)

    @Query("DELETE FROM draft_photos WHERE id = :id")
    fun deleteById(id: String): Int

    @Query("DELETE FROM draft_photos WHERE draftId = :draftId")
    fun deleteByDraftId(draftId: String): Int

    @Query("SELECT * FROM draft_photos WHERE draftId = :draftId ORDER BY takenAtMs ASC")
    fun photosForDraft(draftId: String): Flow<List<DraftPhotoEntity>>

    @Query("SELECT * FROM draft_photos WHERE draftId = :draftId ORDER BY takenAtMs ASC")
    fun listForDraft(draftId: String): List<DraftPhotoEntity>
}
