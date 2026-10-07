package com.pincatcher.data

import android.content.ContentValues
import android.database.Cursor

/** One HTTP(S) exchange, as stored. */
data class Flow(
    val id: Long = 0,
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
) {
    companion object {
        fun from(cursor: Cursor): Flow = Flow(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            sessionId = cursor.getLong(cursor.getColumnIndexOrThrow("session_id")),
            seq = cursor.getLong(cursor.getColumnIndexOrThrow("seq")),
            method = cursor.getString(cursor.getColumnIndexOrThrow("method")),
            scheme = cursor.getString(cursor.getColumnIndexOrThrow("scheme")),
            host = cursor.getString(cursor.getColumnIndexOrThrow("host")),
            path = cursor.getString(cursor.getColumnIndexOrThrow("path")),
            query = cursor.getStringOrNull("query"),
            url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
            statusCode = cursor.getIntOrNull("status_code"),
            reqHeaders = cursor.getStringOrNull("req_headers"),
            resHeaders = cursor.getStringOrNull("res_headers"),
            reqBodyHash = cursor.getStringOrNull("req_body_hash"),
            resBodyHash = cursor.getStringOrNull("res_body_hash"),
            reqSize = cursor.getLongOrNull("req_size"),
            resSize = cursor.getLongOrNull("res_size"),
            startTime = cursor.getLong(cursor.getColumnIndexOrThrow("start_time")),
            endTime = cursor.getLongOrNull("end_time"),
            durationMs = cursor.getLongOrNull("duration_ms"),
            appUid = cursor.getIntOrNull("app_uid"),
            appPackage = cursor.getStringOrNull("app_package"),
            error = cursor.getStringOrNull("error"),
        )

        /** Builds the URL that both `flows.url` and the FTS index are fed. */
        fun urlOf(scheme: String, host: String, path: String, query: String?): String =
            buildString {
                append(scheme).append("://").append(host).append(path)
                if (!query.isNullOrBlank()) append('?').append(query)
            }
    }

    fun toContentValues(): ContentValues = ContentValues().apply {
        put("session_id", sessionId)
        put("seq", seq)
        put("method", method)
        put("scheme", scheme)
        put("host", host)
        put("path", path)
        put("query", query)
        put("url", url)
        put("status_code", statusCode)
        put("req_headers", reqHeaders)
        put("res_headers", resHeaders)
        put("req_body_hash", reqBodyHash)
        put("res_body_hash", resBodyHash)
        put("req_size", reqSize)
        put("res_size", resSize)
        put("start_time", startTime)
        put("end_time", endTime)
        put("duration_ms", durationMs)
        put("app_uid", appUid)
        put("app_package", appPackage)
        put("error", error)
    }
}

internal fun Cursor.getStringOrNull(column: String): String? {
    val i = getColumnIndex(column)
    return if (i < 0 || isNull(i)) null else getString(i)
}

internal fun Cursor.getIntOrNull(column: String): Int? {
    val i = getColumnIndex(column)
    return if (i < 0 || isNull(i)) null else getInt(i)
}

internal fun Cursor.getLongOrNull(column: String): Long? {
    val i = getColumnIndex(column)
    return if (i < 0 || isNull(i)) null else getLong(i)
}

internal fun Cursor.mapRows(transform: (Cursor) -> Flow): List<Flow> = use { c ->
    buildList { while (c.moveToNext()) add(transform(c)) }
}