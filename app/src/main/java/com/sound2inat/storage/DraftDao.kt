package com.sound2inat.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {
    @Insert
    fun insert(d: DraftEntity)

    @Update
    fun update(d: DraftEntity)

    @Delete
    fun delete(d: DraftEntity)

    @Query("SELECT * FROM drafts WHERE id = :id LIMIT 1")
    fun getById(id: String): DraftEntity?

    @Query("SELECT * FROM drafts ORDER BY recordedAtUtcMs DESC")
    fun observeAll(): Flow<List<DraftEntity>>

    @Query("DELETE FROM drafts WHERE id = :id")
    fun deleteById(id: String): Int

    @Query(
        "UPDATE drafts SET status = :newStatus " +
            "WHERE id = :id AND status = :expectedStatus",
    )
    fun updateStatusConditional(id: String, newStatus: DraftStatus, expectedStatus: DraftStatus): Int

    @Query("UPDATE drafts SET paletteName = :name, updatedAtUtcMs = :ts WHERE id = :id")
    fun updatePalette(id: String, name: String?, ts: Long): Int

    @Query("UPDATE drafts SET spectrogramGainDb = :gain, updatedAtUtcMs = :ts WHERE id = :id")
    fun updateSpectrogramGain(id: String, gain: Float?, ts: Long): Int
}
