package com.sound2inat.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDraftDao {
    @Insert
    fun insert(draft: PhotoDraftEntity)

    @Update
    fun update(draft: PhotoDraftEntity)

    @Query("SELECT * FROM photo_drafts WHERE id = :id LIMIT 1")
    fun getById(id: String): PhotoDraftEntity?

    @Query("SELECT * FROM photo_drafts ORDER BY observedAtUtcMs DESC")
    fun observeAll(): Flow<List<PhotoDraftEntity>>

    @Transaction
    @Query("SELECT * FROM photo_drafts WHERE id = :id LIMIT 1")
    fun observeWithImages(id: String): Flow<PhotoDraftWithImages?>

    @Query("DELETE FROM photo_drafts WHERE id = :id")
    fun deleteById(id: String): Int

    @Query("UPDATE photo_drafts SET uploadStatus = 'COMPLETE' WHERE id = :draftId")
    fun markPhotoUploadComplete(draftId: String): Int

    @Query("SELECT * FROM photo_drafts WHERE id = :draftId AND uploadStatus = 'INCOMPLETE' LIMIT 1")
    fun observeIncomplete(draftId: String): Flow<PhotoDraftEntity?>

    @Query(
        "UPDATE photo_drafts SET uploadStatus = NULL, inatObservationId = NULL, " +
            "inatObservationUuid = NULL, inatObservationUrl = NULL, inatLastError = NULL, " +
            "status = 'REVIEWED', updatedAtUtcMs = :now WHERE id = :draftId",
    )
    fun clearIncompleteUpload(draftId: String, now: Long): Int
}
