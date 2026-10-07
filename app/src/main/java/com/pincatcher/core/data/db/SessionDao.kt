package com.pincatcher.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.pincatcher.core.data.db.entity.SessionEntity

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun close(id: Long, endedAt: Long)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 1")
    suspend fun mostRecent(): SessionEntity?
}
