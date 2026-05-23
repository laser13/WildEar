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

    /**
     * Idempotency helper: returns the existing row for a given
     * `(draftId, scientificName)` pair, or null if none was persisted yet.
     * Used by [com.sound2inat.inat.INatSubmitter] to skip a duplicate
     * `createObservation` call when a prior submission already landed the
     * species on iNaturalist but the local persist step never completed.
     */
    @Query(
        "SELECT * FROM inat_observations WHERE draftId = :draftId " +
            "AND taxonScientificName = :species LIMIT 1",
    )
    fun findForDraftAndSpecies(draftId: String, species: String): InatObservationEntity?

    @Query("SELECT * FROM inat_observations WHERE draftId = :draftId ORDER BY id ASC")
    fun observeForDraft(draftId: String): Flow<List<InatObservationEntity>>

    @Query("DELETE FROM inat_observations WHERE draftId = :draftId")
    fun deleteForDraft(draftId: String): Int

    /**
     * Targeted delete for a single (draftId, species) pair. Used by
     * [com.sound2inat.inat.INatSubmitter] to wipe a malformed idempotency
     * row for one species without touching sibling rows belonging to other
     * species of the same draft (which the incremental flow depends on).
     */
    @Query(
        "DELETE FROM inat_observations WHERE draftId = :draftId " +
            "AND taxonScientificName = :species",
    )
    fun deleteForDraftAndSpecies(draftId: String, species: String): Int

    /**
     * Per-draft observation counts, refreshed whenever any row in the table
     * changes. Drafts with zero observations are absent from the result —
     * UI callers default to `0` for missing keys.
     */
    @Query("SELECT draftId AS draftId, COUNT(*) AS count FROM inat_observations GROUP BY draftId")
    fun observeCountsByDraft(): Flow<List<DraftObservationCount>>

    @Query(
        "UPDATE inat_observations SET uploadStatus = 'COMPLETE' " +
            "WHERE id = :rowId",
    )
    fun markComplete(rowId: Long): Int

    @Query(
        "SELECT * FROM inat_observations WHERE draftId = :draftId " +
            "AND uploadStatus = 'INCOMPLETE' ORDER BY id ASC",
    )
    fun observeIncompleteForDraft(draftId: String): Flow<List<InatObservationEntity>>

    @Query("DELETE FROM inat_observations WHERE id = :rowId")
    fun deleteById(rowId: Long): Int
}

data class DraftObservationCount(val draftId: String, val count: Int)
