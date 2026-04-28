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
}
