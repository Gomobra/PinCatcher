package com.pincatcher.core.data.db

/**
 * Content-addressed body storage.
 *
 * `hash` is SHA-256 of the raw bytes, so a body repeated across flows is
 * stored once. `refcount` tracks how many flow rows point at it; the ring
 * buffer's GC unlinks the blob file and drops the row once it hits zero.
 */
class BodyStore(private val db: PincatcherDatabase) {

    data class Body(
        val hash: String,
        val sizeRaw: Long,
        val sizeStored: Long,
        val encoding: String?,
        val contentType: String?,
        val refcount: Int,
        val createdAt: Long,
        val filePath: String?,
    )

    /**
     * Stores a body unless its hash is already present.
     *
     * Returns true when a new blob was written (dedup miss), false when an
     * identical body was already stored. The caller increments the refcount
     * either way - a dedup hit still needs its reference counted.
     */
    fun storeOrDeduplicate(body: Body): Boolean {
        val written = db.writableDatabase.insertWithOnConflict(
            "bodies",
            null,
            body.toContentValues(),
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
        )
        return written != -1L
    }

    fun refcount(hash: String): Int =
        db.readableDatabase.rawQuery("SELECT refcount FROM bodies WHERE hash = ?", arrayOf(hash))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun bumpRefcount(hash: String, delta: Int) {
        db.writableDatabase.execSQL("UPDATE bodies SET refcount = refcount + ? WHERE hash = ?", arrayOf<Any>(delta, hash))
    }

    /**
     * Recomputes every refcount from the flows table.
     *
     * One statement, and it cannot drift. The incremental counter is the fast
     * path on write; this is the repair job after a purge or a crash part-way
     * through a batch.
     */
    fun recountAll(): Int = db.writableDatabase.compileStatement(
        """
        UPDATE bodies SET refcount = (
            SELECT COUNT(*) FROM flows
            WHERE req_body_hash = bodies.hash OR res_body_hash = bodies.hash
        )
        """.trimIndent(),
    ).use { it.executeUpdateDelete() }

    fun storedBytes(): Long = db.readableDatabase
        .rawQuery("SELECT COALESCE(SUM(size_stored), 0) FROM bodies", null)
        .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun savedByDedupAndCompression(): Long = db.readableDatabase
        .rawQuery("SELECT COALESCE(SUM(size_raw - size_stored), 0) FROM bodies", null)
        .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Drops unreferenced bodies older than [olderThan]; returns the hashes whose blobs to unlink. */
    fun collectOrphans(olderThan: Long): List<String> = db.readableDatabase.rawQuery(
        "SELECT hash FROM bodies WHERE refcount = 0 AND created_at < ?",
        arrayOf(olderThan.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun deleteOrphans(olderThan: Long): Int =
        db.writableDatabase.delete("bodies", "refcount = 0 AND created_at < ?", arrayOf(olderThan.toString()))

    fun clear() {
        db.writableDatabase.delete("bodies", null, null)
    }

    private fun Body.toContentValues() = android.content.ContentValues().apply {
        put("hash", hash)
        put("size_raw", sizeRaw)
        put("size_stored", sizeStored)
        put("encoding", encoding)
        put("content_type", contentType)
        put("refcount", refcount)
        put("created_at", createdAt)
        put("file_path", filePath)
    }
}