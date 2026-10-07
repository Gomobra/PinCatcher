package com.pincatcher.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * PinCatcher's storage, on the platform SQLite directly.
 *
 * Room was tried first and dropped for two reasons.
 *
 * 1. It cannot be compiled on-device. Room verifies queries by opening an
 *    in-memory database through its bundled xerial sqlite-jdbc, which is a
 *    glibc-linked native library. PinCatcher is developed on Android (bionic),
 *    where that `dlopen` fails on `libm.so.6`, and glibc's libc cannot be mixed
 *    into a bionic process. Removing the KSP step also removed the slowest task
 *    in the build.
 *
 * 2. The parts of this schema that matter are exactly the parts Room handles
 *    worst. The flow index is an FTS4 *external content* table, and Room's
 *    support there amounts to whatever its generated triggers do - which we
 *    would be reading out of a build directory to audit anyway.
 *
 * [SQLiteOpenHelper] is the framework class Room is a code generator on top of,
 * so the only thing given up is the generator.
 */
class PincatcherDatabase(context: Context) :
    SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        // Flow rows are deleted individually by the ring buffer, not by
        // cascading from their session, so the constraint is only there to
        // catch a bug that would orphan rows.
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.inTransaction {
            SCHEMA.forEach(db::execSQL)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first shipped schema; nothing to migrate from yet.
    }

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    companion object {
        const val NAME = "pincatcher.db"
        const val VERSION = 1

        private val SCHEMA = listOf(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                mode TEXT NOT NULL,
                target_packages TEXT,
                storage_policy TEXT
            )
            """,
            "CREATE INDEX idx_sessions_started ON sessions(started_at DESC)",

            """
            CREATE TABLE flows (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                seq INTEGER NOT NULL,
                method TEXT NOT NULL,
                scheme TEXT NOT NULL,
                host TEXT NOT NULL,
                path TEXT NOT NULL,
                query TEXT,
                -- Denormalised on purpose: the FTS index resolves its columns
                -- against this table by name, so the URL has to exist here as a
                -- real column rather than being concatenated at index time.
                url TEXT NOT NULL,
                status_code INTEGER,
                req_headers TEXT,
                res_headers TEXT,
                req_body_hash TEXT,
                res_body_hash TEXT,
                req_size INTEGER,
                res_size INTEGER,
                start_time INTEGER NOT NULL,
                end_time INTEGER,
                duration_ms INTEGER,
                app_uid INTEGER,
                app_package TEXT,
                error TEXT,
                FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """,
            "CREATE INDEX idx_flows_session ON flows(session_id)",
            "CREATE INDEX idx_flows_host ON flows(host)",
            "CREATE INDEX idx_flows_status ON flows(status_code)",
            "CREATE INDEX idx_flows_start ON flows(start_time DESC)",
            "CREATE INDEX idx_flows_app ON flows(app_package)",

            """
            CREATE TABLE bodies (
                hash TEXT PRIMARY KEY,
                size_raw INTEGER NOT NULL,
                size_stored INTEGER NOT NULL,
                encoding TEXT,
                content_type TEXT,
                refcount INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                file_path TEXT
            )
            """,
            "CREATE INDEX idx_bodies_refcount ON bodies(refcount)",
            "CREATE INDEX idx_bodies_created ON bodies(created_at)",

            """
            CREATE TABLE rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                matcher TEXT NOT NULL,
                action TEXT NOT NULL,
                priority INTEGER NOT NULL DEFAULT 100,
                created_at INTEGER
            )
            """,

            // FTS4, not FTS5: the platform SQLite on Android is not compiled
            // with FTS5, and pulling in BundledSQLiteDriver for it would mean
            // shipping our own SQLite (NDK, ~2 MB) for no added capability.
            // FTS4 also has no `content_rowid` parameter - that is FTS5-only -
            // and addresses the content table's INTEGER PRIMARY KEY as `docid`.
            """
            CREATE VIRTUAL TABLE flows_fts USING fts4(
                url,
                req_headers,
                res_headers,
                content='flows',
                tokenize='unicode61'
            )
            """,

            // The BEFORE/AFTER split is load-bearing. In external-content mode
            // FTS4 re-reads the content table to work out which tokens to drop,
            // so index removal has to happen while the row still holds its old
            // text. Deleting by docid from an AFTER UPDATE reads the *new* text
            // and leaves the old tokens behind - verified against SQLite, the
            // flow then matches both its old and its new URL.
            """
            CREATE TRIGGER flows_fts_ai AFTER INSERT ON flows BEGIN
                INSERT INTO flows_fts(docid, url, req_headers, res_headers)
                VALUES (new.id, new.url, new.req_headers, new.res_headers);
            END
            """,
            """
            CREATE TRIGGER flows_fts_ad BEFORE DELETE ON flows BEGIN
                DELETE FROM flows_fts WHERE docid = old.id;
            END
            """,
            """
            CREATE TRIGGER flows_fts_au1 BEFORE UPDATE ON flows BEGIN
                DELETE FROM flows_fts WHERE docid = old.id;
            END
            """,
            """
            CREATE TRIGGER flows_fts_au2 AFTER UPDATE ON flows BEGIN
                INSERT INTO flows_fts(docid, url, req_headers, res_headers)
                VALUES (new.id, new.url, new.req_headers, new.res_headers);
            END
            """,
        )
    }
}