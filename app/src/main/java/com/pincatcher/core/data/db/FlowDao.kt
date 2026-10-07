package com.pincatcher.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.pincatcher.core.data.db.entity.FlowEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FlowDao {

    @Insert
    suspend fun insert(flow: FlowEntity): Long

    @Query("SELECT * FROM flows WHERE sessionId = :sessionId ORDER BY startTime DESC LIMIT :limit")
    fun observeRecent(sessionId: Long, limit: Int): Flow<List<FlowEntity>>

    @Query("SELECT * FROM flows WHERE id = :id")
    suspend fun byId(id: Long): FlowEntity?

    /**
     * Full-text search. Returns whole flow rows, not FTS columns - an
     * external-content FTS table cannot project its own columns, and the
     * columns we need already live on `flows`.
     *
     * The MATCH argument must be FTS query syntax, so a user typing `a.b` needs
     * it quoted: pass `"""${query.trim().replace("\"", "\"\"")}"""`.
     */
    @Query(
        """
        SELECT f.* FROM flows f
        JOIN flows_fts t ON f.id = t.rowid
        WHERE flows_fts MATCH :query
        ORDER BY f.startTime DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 200): List<FlowEntity>

    @Query("DELETE FROM flows WHERE startTime < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int

    @Query("DELETE FROM flows WHERE sessionId = :sessionId")
    suspend fun purgeSession(sessionId: Long): Int

    /**
     * Ring-buffer trim. Runs in one transaction so the FTS index never keeps
     * rows whose flow row is already gone.
     */
    @Transaction
    suspend fun trimToBudget(maxFlows: Int, maxAgeMs: Long, now: Long): Int {
        val cutoff = now - maxAgeMs
        var deleted = purgeOlderThan(cutoff)
        deleted += purgeExcess(maxFlows)
        return deleted
    }

    @Query(
        """
        DELETE FROM flows WHERE id NOT IN (
            SELECT id FROM flows ORDER BY startTime DESC LIMIT :maxFlows
        )
        """,
    )
    suspend fun purgeExcess(maxFlows: Int): Int
}
