package io.github.xhzair.refresholdphone.stats

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * SQLite persistence for [ListeningSession] snapshots.
 *
 * Main-thread callers enqueue work; every database access runs on a single worker executor, so
 * all writes for one uid stay ordered. Both open and closed snapshots are upserted: an open snapshot
 * preserves progress after a process restart, while a closed one is final. Read callbacks are posted
 * to the main thread and include open and closed sessions inside the requested range;
 * [ListeningStatsAggregator] filters out sessions without positive listening time.
 *
 * [close] stops accepting new work and enqueues the database close behind all previously accepted
 * work. Calls made after close are rejected: writes are logged and dropped, while reads and clears
 * report failure on the main thread instead of leaving their callers waiting forever.
 */
class ListeningStatsStore(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** Guarded by [lock]; set before the close task is enqueued so late work cannot slip in. */
    private var closeRequested = false

    /** Enqueues a full snapshot upsert, replacing any previous snapshot for the session uid. */
    fun upsert(session: ListeningSession) {
        val accepted = enqueue {
            try {
                writableDatabase.insertWithOnConflict(
                    TABLE_NAME,
                    null,
                    toValues(session),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upsert session ${session.uid}", e)
            }
        }
        if (!accepted) {
            Log.w(TAG, "Dropping upsert for session ${session.uid}: store is closed")
        }
    }

    /**
     * Enqueues marking every open session closed at [nowMs]. Stale sessions are closed without
     * fabricating listening time, so only previously recorded progress counts.
     */
    fun closeStaleOpenSessions(nowMs: Long) {
        val accepted = enqueue {
            try {
                writableDatabase.execSQL(
                    "UPDATE $TABLE_NAME SET $COLUMN_CLOSED = $CLOSED, $COLUMN_UPDATED_AT = ? " +
                        "WHERE $COLUMN_CLOSED = $OPEN",
                    arrayOf<Any>(nowMs),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close stale listening sessions", e)
            }
        }
        if (!accepted) {
            Log.w(TAG, "Dropping stale-session close: store is closed")
        }
    }

    /**
     * Enqueues a query for sessions whose [ListeningSession.startedAtMs] falls in
     * `[startMs, endMs)`, ordered oldest first.
     *
     * [onResult] always runs on the main thread with the loaded sessions, or with the failure that
     * prevented the query. Rows with an unknown stored `source_kind` are skipped and logged.
     */
    fun loadSessions(
        startMs: Long,
        endMs: Long,
        onResult: (Result<List<ListeningSession>>) -> Unit,
    ) {
        val accepted = enqueue {
            val result = try {
                Result.success(readSessions(startMs, endMs))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load listening sessions", e)
                Result.failure<List<ListeningSession>>(e)
            }
            mainHandler.post { onResult(result) }
        }
        if (!accepted) {
            Log.w(TAG, "Dropping load request: store is closed")
            mainHandler.post { onResult(closedFailure()) }
        }
    }

    /**
     * Enqueues deleting every persisted session. [onResult] runs on the main thread and receives
     * `true` only when the delete succeeded; failures and post-close calls receive `false`.
     */
    fun clear(onResult: (Boolean) -> Unit = {}) {
        val accepted = enqueue {
            val cleared = try {
                writableDatabase.delete(TABLE_NAME, null, null)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear listening sessions", e)
                false
            }
            mainHandler.post { onResult(cleared) }
        }
        if (!accepted) {
            Log.w(TAG, "Dropping clear request: store is closed")
            mainHandler.post { onResult(false) }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
        db.execSQL(CREATE_INDEX)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    /**
     * Stops accepting new work and releases the database.
     *
     * The close task is enqueued on the same single-thread executor as writes, so it runs only after
     * every previously accepted upsert (including the flush from
     * `ListeningHistoryRepository.shutdown`). If the executor already rejects work, the database is
     * closed synchronously instead. Late callers and late queued work are tolerated so shutdown
     * never crashes the process.
     */
    override fun close() {
        synchronized(lock) {
            if (closeRequested) return
            closeRequested = true
            try {
                executor.execute { closeDatabase() }
            } catch (e: RejectedExecutionException) {
                Log.e(TAG, "Executor rejected close; closing database synchronously", e)
                closeDatabase()
            } finally {
                executor.shutdown()
            }
        }
    }

    /** Kotlin does not allow `super.close()` inside a lambda, so the executor task calls this. */
    private fun closeDatabase() {
        try {
            super.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close listening stats database", e)
        }
    }

    /** Runs [block] on the worker unless [close] already started; returns whether it was accepted. */
    private fun enqueue(block: () -> Unit): Boolean = synchronized(lock) {
        if (closeRequested) {
            false
        } else {
            try {
                executor.execute(block)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enqueue listening stats work", e)
                false
            }
        }
    }

    private fun readSessions(startMs: Long, endMs: Long): List<ListeningSession> =
        readableDatabase.query(
            TABLE_NAME,
            COLUMNS,
            "$COLUMN_STARTED_AT >= ? AND $COLUMN_STARTED_AT < ?",
            arrayOf(startMs.toString(), endMs.toString()),
            null,
            null,
            "$COLUMN_STARTED_AT ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    readSession(cursor)?.let(::add)
                }
            }
        }

    private fun toValues(session: ListeningSession): ContentValues = ContentValues().apply {
        put(COLUMN_UID, session.uid)
        put(COLUMN_SOURCE_KIND, session.sourceKind.name)
        put(COLUMN_PACKAGE_NAME, session.packageName)
        put(COLUMN_TITLE, session.title)
        put(COLUMN_ARTIST, session.artist)
        put(COLUMN_ALBUM, session.album)
        put(COLUMN_STARTED_AT, session.startedAtMs)
        put(COLUMN_UPDATED_AT, session.updatedAtMs)
        put(COLUMN_LISTENED_MS, session.listenedMs)
        put(COLUMN_DURATION_MS, session.durationMs)
        put(COLUMN_LAST_POSITION_MS, session.lastPositionMs)
        put(COLUMN_CLOSED, if (session.closed) CLOSED else OPEN)
    }

    /** Reads one row by column name; returns null when the stored source kind is unknown. */
    private fun readSession(cursor: Cursor): ListeningSession? {
        val sourceKind = sourceKindOf(
            cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SOURCE_KIND)),
        ) ?: return null
        return ListeningSession(
            uid = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_UID)),
            sourceKind = sourceKind,
            packageName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PACKAGE_NAME)),
            title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE)),
            artist = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ARTIST)),
            album = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ALBUM)),
            startedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_STARTED_AT)),
            updatedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
            listenedMs = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_LISTENED_MS)),
            durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_DURATION_MS)),
            lastPositionMs = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_LAST_POSITION_MS)),
            closed = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_CLOSED)) == CLOSED,
        )
    }

    private fun sourceKindOf(raw: String?): ListeningSourceKind? {
        val kind = ListeningSourceKind.entries.firstOrNull { it.name == raw }
        if (kind == null) {
            Log.w(TAG, "Skipping session with unknown source_kind: $raw")
        }
        return kind
    }

    private fun closedFailure(): Result<List<ListeningSession>> =
        Result.failure(IllegalStateException("ListeningStatsStore is closed"))

    private companion object {
        const val TAG = "ListeningStatsStore"
        const val DATABASE_NAME = "listening_stats.db"
        const val DATABASE_VERSION = 1

        const val TABLE_NAME = "listening_sessions"
        const val COLUMN_UID = "uid"
        const val COLUMN_SOURCE_KIND = "source_kind"
        const val COLUMN_PACKAGE_NAME = "package_name"
        const val COLUMN_TITLE = "title"
        const val COLUMN_ARTIST = "artist"
        const val COLUMN_ALBUM = "album"
        const val COLUMN_STARTED_AT = "started_at"
        const val COLUMN_UPDATED_AT = "updated_at"
        const val COLUMN_LISTENED_MS = "listened_ms"
        const val COLUMN_DURATION_MS = "duration_ms"
        const val COLUMN_LAST_POSITION_MS = "last_position_ms"
        const val COLUMN_CLOSED = "closed"

        const val OPEN = 0
        const val CLOSED = 1

        val COLUMNS = arrayOf(
            COLUMN_UID,
            COLUMN_SOURCE_KIND,
            COLUMN_PACKAGE_NAME,
            COLUMN_TITLE,
            COLUMN_ARTIST,
            COLUMN_ALBUM,
            COLUMN_STARTED_AT,
            COLUMN_UPDATED_AT,
            COLUMN_LISTENED_MS,
            COLUMN_DURATION_MS,
            COLUMN_LAST_POSITION_MS,
            COLUMN_CLOSED,
        )

        val CREATE_TABLE = """
            CREATE TABLE listening_sessions(
                uid TEXT PRIMARY KEY,
                source_kind TEXT NOT NULL,
                package_name TEXT,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                listened_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                last_position_ms INTEGER NOT NULL,
                closed INTEGER NOT NULL
            );
        """.trimIndent()

        val CREATE_INDEX = """
            CREATE INDEX idx_listening_started ON listening_sessions(started_at);
        """.trimIndent()
    }
}
