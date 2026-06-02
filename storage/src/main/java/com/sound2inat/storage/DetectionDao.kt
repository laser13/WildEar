package com.sound2inat.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DetectionDao {
    @Insert
    fun insertAll(items: List<DetectionEntity>)

    @Query("SELECT * FROM detections WHERE draftId = :draftId ORDER BY maxConfidence DESC")
    fun observeForDraft(draftId: String): Flow<List<DetectionEntity>>

    @Query("SELECT * FROM detections WHERE draftId = :draftId ORDER BY maxConfidence DESC")
    fun listForDraft(draftId: String): List<DetectionEntity>

    @Query("UPDATE detections SET isSelectedByUser = :selected WHERE id = :id")
    fun setSelected(id: Long, selected: Boolean): Int

    @Query("DELETE FROM detections WHERE draftId = :draftId")
    fun deleteForDraft(draftId: String): Int

    @Query("SELECT draftId AS draftId, COUNT(*) AS count FROM detections GROUP BY draftId")
    fun observeCountsByDraft(): Flow<List<DraftDetectionCount>>

    @Query("UPDATE detections SET regionalStatus = :status WHERE draftId = :draftId AND taxonScientificName = :name")
    fun updateRegionalStatusBySpecies(draftId: String, name: String, status: String?): Int
}

data class DraftDetectionCount(val draftId: String, val count: Int)
