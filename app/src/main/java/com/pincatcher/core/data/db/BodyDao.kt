package com.pincatcher.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.pincatcher.core.data.db.entity.BodyEntity

@Dao
interface BodyDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(body: BodyEntity): Long

    @Query("SELECT * FROM bodies WHERE hash = :hash")
    suspend fun byHash(hash: String): BodyEntity?

    @Query("SELECT refcount FROM bodies WHERE hash = :hash")
    suspend fun refcount(hash: String): Int?

    @Query("UPDATE bodies SET refcount = refcount + :delta WHERE hash = :hash")
    suspend fun bumpRefcount(hash: String, delta: Int)

    /**
     * Recomputes refcounts from the flows table rather than trusting the
     * running counter. One statement, always correct, and cheap at the 5k-flow
     * cap. The counter is kept in sync incrementally on write for the fast
     * path; this is the repair job after a purge or a crash mid-transaction.
     */
    @Query(
        """
        UPDATE bodies SET refcount = (
            SELECT COUNT(*) FROM flows
            WHERE req_body_hash = bodies.hash OR res_body_hash = bodies.hash
        )
        """,
    )
    suspend fun recountAll(): Int

    @Query("SELECT COUNT(*) FROM bodies WHERE refcount = 0 AND createdAt < :olderThan")
    suspend fun countOrphans(olderThan: Long): Int

    @Query("DELETE FROM bodies WHERE refcount = 0 AND createdAt < :olderThan")
    suspend fun deleteOrphans(olderThan: Long): List<BodyEntity>

    @Query("SELECT SUM(sizeStored) FROM bodies")
    suspend fun storedBytes(): Long?

    @Query("SELECT SUM(sizeRaw - sizeStored) FROM bodies")
    suspend fun savedBytes(): Long?

    @Query("DELETE FROM bodies")
    suspend fun clear()

    @Transaction
    suspend fun store(body: BodyEntity): Boolean {
        val rowId = insertIfAbsent(body)
        // IGNORE returns -1 when the hash was already present, i.e. dedup hit.
        return rowId != -1L
    }
}
