package com.pincatcher.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sessions",
    indices = [Index("startedAt")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val mode: String,
    val targetPackages: String? = null,
    val storagePolicy: String? = null,
)

/**
 * One HTTP(S) exchange.
 *
 * `url` is denormalised into its own column rather than derived from
 * scheme/host/path at query time. Two reasons: the FTS index needs a real
 * column (an external-content FTS table resolves columns against the content
 * table by name, and a concat-only column does not exist there), and
 * `WHERE url LIKE ?` stays index-usable.
 */
@Entity(
    tableName = "flows",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("host"),
        Index("statusCode"),
        Index("startTime"),
        Index("method"),
        Index("appPackage"),
    ],
)
data class FlowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val seq: Long,
    val method: String,
    val scheme: String,
    val host: String,
    val path: String,
    val query: String? = null,
    val url: String,
    val statusCode: Int? = null,
    val reqHeaders: String? = null,
    val resHeaders: String? = null,
    val reqBodyHash: String? = null,
    val resBodyHash: String? = null,
    val reqSize: Long? = null,
    val resSize: Long? = null,
    val startTime: Long,
    val endTime: Long? = null,
    val durationMs: Long? = null,
    val appUid: Int? = null,
    val appPackage: String? = null,
    val error: String? = null,
)

/**
 * Content-addressed body storage. `hash` is SHA-256 of the raw bytes, so an
 * identical body is stored once no matter how many flows reference it.
 */
@Entity(
    tableName = "bodies",
    indices = [Index("refcount"), Index("createdAt")],
)
data class BodyEntity(
    @PrimaryKey val hash: String,
    val sizeRaw: Long,
    val sizeStored: Long,
    val encoding: String? = null,
    val contentType: String? = null,
    val refcount: Int = 0,
    val createdAt: Long,
    val filePath: String? = null,
)
