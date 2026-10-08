package com.pincatcher.data

/**
 * Query surface over [PincatcherDatabase].
 *
 * Deliberately thin: no repository layer, no Flow-of-Flow yet. The capture
 * engine does not exist, so there is no live stream to observe, and adding an
 * abstraction before the producer exists is speculative.
 */
class FlowStore(private val db: PincatcherDatabase) {

    fun insert(flow: Flow): Long =
        db.writableDatabase.insertOrThrow("flows", null, flow.toContentValues())

    /**
     * Opens a capture session and returns its id.
     *
     * Every flow belongs to one, so a second run cannot be confused with the
     * first. `targetPackages` records what was being captured: a capture of one
     * app and a capture of everything produce very different traffic, and a row
     * that cannot answer "which?" is not much of a record.
     */
    fun startSession(
        name: String,
        mode: String,
        targetPackages: List<String>,
        storagePolicy: String? = null,
        startedAt: Long = System.currentTimeMillis(),
    ): Long = db.writableDatabase.insertOrThrow(
        "sessions",
        null,
        android.content.ContentValues().apply {
            put("name", name)
            put("started_at", startedAt)
            put("mode", mode)
            put("target_packages", targetPackages.joinToString(","))
            put("storage_policy", storagePolicy)
        },
    )

    fun endSession(id: Long, endedAt: Long = System.currentTimeMillis()): Int =
        db.writableDatabase.update(
            "sessions",
            android.content.ContentValues().apply { put("ended_at", endedAt) },
            "id = ?",
            arrayOf(id.toString()),
        )

    fun mostRecentSession(): Long? = db.readableDatabase
        .rawQuery("SELECT id FROM sessions ORDER BY started_at DESC LIMIT 1", null)
        .use { if (it.moveToFirst()) it.getLong(0) else null }

    fun sessionCount(sessionId: Long): Long = db.readableDatabase
        .rawQuery("SELECT COUNT(*) FROM flows WHERE session_id = ?", arrayOf(sessionId.toString()))
        .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun byId(id: Long): Flow? =
        db.readableDatabase.rawQuery("SELECT * FROM flows WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) Flow.from(it) else null }

    fun recent(sessionId: Long, limit: Int = 200): List<Flow> =
        db.readableDatabase.rawQuery(
            "SELECT * FROM flows WHERE session_id = ? ORDER BY start_time DESC LIMIT ?",
            arrayOf(sessionId.toString(), limit.toString()),
        ).mapRows(Flow::from)

    /**
     * Full-text search over URL and headers.
     *
     * [query] is user input in FTS5-style MATCH syntax, so a bare `app.v2` or
     * `a-b` would be parsed as a column filter or a negated token and silently
     * match nothing. Wrapping it in a quoted string makes it a literal phrase;
     * inner quotes are doubled per FTS escaping rules.
     */
    fun search(query: String, limit: Int = 200): List<Flow> {
        val literal = buildString {
            append('"')
            query.trim().forEach { if (it == '"') append("\"\"") else append(it) }
            append('"')
        }
        return db.readableDatabase.rawQuery(
            """
            SELECT f.* FROM flows f
            JOIN flows_fts t ON f.id = t.docid
            WHERE flows_fts MATCH ?
            ORDER BY f.start_time DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(literal, limit.toString()),
        ).mapRows(Flow::from)
    }

    fun host(host: String, limit: Int = 200): List<Flow> =
        db.readableDatabase.rawQuery(
            "SELECT * FROM flows WHERE host = ? ORDER BY start_time DESC LIMIT ?",
            arrayOf(host, limit.toString()),
        ).mapRows(Flow::from)

    /** Applies the ring-buffer limits, oldest flow first. */
    fun trim(maxFlows: Int, olderThan: Long): Int {
        val writable = db.writableDatabase
        var deleted = writable.delete("flows", "start_time < ?", arrayOf(olderThan.toString()))
        writable.execSQL(
            """
            DELETE FROM flows WHERE id NOT IN (
                SELECT id FROM flows ORDER BY start_time DESC LIMIT ?
            )
            """.trimIndent(),
            arrayOf<Any>(maxFlows),
        )
        return deleted
    }
}