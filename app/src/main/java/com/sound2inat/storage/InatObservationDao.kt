package com.sound2inat.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InatObservationDao {
    @Insert
    fun insert(row: InatObservationEntity): Long

    @Query("SELECT * FROM inat_observations WHERE draftId = :draftId ORDER BY id ASC")
    fun listForDraft(draftId: String): List<InatObservationEntity>

    @Query("SELECT * FROM inat_observations WHERE draftId = :draftId ORDER BY id ASC")
    fun observeForDraft(draftId: String): Flow<List<InatObservationEntity>>

    @Query("DELETE FROM inat_observations WHERE draftId = :draftId")
    fun deleteForDraft(draftId: String): Int

    /**
     * Per-draft observation counts, refreshed whenever any row in the table
     * changes. Drafts with zero observations are absent from the result —
     * UI callers default to `0` for missing keys.
     */
    @Query("SELECT draftId AS draftId, COUNT(*) AS count FROM inat_observations GROUP BY draftId")
    fun observeCountsByDraft(): Flow<List<DraftObservationCount>>
}

data class DraftObservationCount(val draftId: String, val count: Int)
