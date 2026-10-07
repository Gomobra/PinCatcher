package com.pincatcher.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey
import androidx.room.FtsOptions

/**
 * Full-text index over captured flows.
 *
 * ### Why FTS4 and not FTS5
 * The platform SQLite shipped with Android is not compiled with FTS5; only
 * `BundledSQLiteDriver` provides it. That would mean shipping our own SQLite
 * (NDK build, ~2 MB). FTS4 is present on every API level we support, so the
 * platform driver is enough.
 *
 * ### Why external content (`contentEntity = FlowEntity::class`)
 * Room then owns the INSERT/UPDATE/DELETE sync triggers. The PRD's hand-written
 * DDL only had INSERT and DELETE, which silently leaves stale rows in the index
 * after any update - a flow edited by a replay or rewrite rule stops matching
 * its new URL while still matching its old one.
 *
 * Being external content also means the FTS table stores no text of its own.
 * The trade-off is that an FTS table in this mode cannot project its own
 * columns: `SELECT url FROM flows_fts WHERE flows_fts MATCH ?` fails, and
 * `snippet()` is unavailable. That costs nothing here because [FlowEntity]
 * already carries `url`, `reqHeaders` and `resHeaders` as real columns - the
 * query joins back to `flows` and match highlighting is done in Kotlin.
 *
 * Note FTS4 has no `content_rowid` parameter (that is FTS5-only); the
 * content table's `INTEGER PRIMARY KEY` is used implicitly.
 */
@Entity(tableName = "flows_fts")
@Fts4(
    contentEntity = FlowEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
)
data class FlowSearchEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,

    val url: String,
    val reqHeaders: String?,
    val resHeaders: String?,
)
