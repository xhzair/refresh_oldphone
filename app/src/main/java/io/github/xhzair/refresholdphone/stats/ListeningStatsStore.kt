package io.github.xhzair.refresholdphone.stats

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * SQLite persistence for [ListeningSession] snapshots.
 *
 * Main-thread callers enqueue work; every database access runs on a single worker executor, so
 * all writes for one uid stay ordered. Both open and closed snapshots are upserted: an open snapshot
 * preserves progress after a process restart, while a closed one is final. Read callbacks are posted
 * to the main thread and include open and closed sessions inside the requested range;
 * [ListeningStatsAggregator] filters out sessions without positive listening time.
 */
class ListeningStatsStore(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Enqueues a full snapshot upsert, replacing any previous snapshot for the session uid. */
    fun upsert(session: ListeningSession) {
        enqueue {
            runCatching {
                writableDatabase.insertWithOnConflict(
                    TABLE_NAME,
                    null,
                    toValues(session),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    /**
     * Enqueues marking every open session closed at [nowMs]. Stale sessions are closed without
     * fabricating listening time, so only previously recorded progress counts.
     */
    fun closeStaleOpenSessions(nowMs: Long) {
        enqueue {
            runCatching {
                writableDatabase.update(
                    TABLE_NAME,
                    ContentValues().apply {
                        put(COLUMN_CLOSED, CLOSED)
                        put(COLUMN_UPDATED_AT, nowMs)
                    },
                    "$COLUMN_CLOSED = ?",
                    arrayOf(OPEN.toString()),
                )
            }
        }
    }

    /**
     * Enqueues a query for sessions whose [ListeningSession.startedAtMs] falls in
     * `[startMs, endMs)`, ordered oldest first. [callback] always runs on the main thread.
     */
    fun loadSessions(startMs: Long, endMs: Long, callback: (List<ListeningSession>) -> Unit) {
        enqueue {
            val sessions: List<ListeningSession> = runCatching {
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
                            add(readSession(cursor))
                        }
                    }
                }
            }.getOrDefault(emptyList())
            mainHandler.post { callback(sessions) }
        }
    }

    /** Enqueues deleting every persisted session, then posts [callback] on the main thread. */
    fun clear(callback: (() -> Unit)? = null) {
        enqueue {
            runCatching { writableDatabase.delete(TABLE_NAME, null, null) }
            if (callback != null) {
                mainHandler.post { callback() }
            }
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
     * Stops accepting new work and releases the database. Late callers and late queued work are
     * tolerated so shutdown never crashes the process.
     */
    override fun close() {
        runCatching { executor.shutdown() }
        runCatching { super.close() }
    }

    private fun enqueue(block: () -> Unit) {
        runCatching { executor.execute(block) }
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

    private fun readSession(cursor: Cursor): ListeningSession = ListeningSession(
        uid = cursor.getString(INDEX_UID),
        sourceKind = sourceKindOf(cursor.getString(INDEX_SOURCE_KIND)),
        packageName = cursor.getString(INDEX_PACKAGE_NAME),
        title = cursor.getString(INDEX_TITLE),
        artist = cursor.getString(INDEX_ARTIST),
        album = cursor.getString(INDEX_ALBUM),
        startedAtMs = cursor.getLong(INDEX_STARTED_AT),
        updatedAtMs = cursor.getLong(INDEX_UPDATED_AT),
        listenedMs = cursor.getLong(INDEX_LISTENED_MS),
        durationMs = cursor.getLong(INDEX_DURATION_MS),
        lastPositionMs = cursor.getLong(INDEX_LAST_POSITION_MS),
        closed = cursor.getInt(INDEX_CLOSED) == CLOSED,
    )

    private fun sourceKindOf(raw: String?): ListeningSourceKind =
        if (raw == ListeningSourceKind.EXTERNAL.name) ListeningSourceKind.EXTERNAL
        else ListeningSourceKind.LOCAL

    private companion object {
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

        const val INDEX_UID = 0
        const val INDEX_SOURCE_KIND = 1
        const val INDEX_PACKAGE_NAME = 2
        const val INDEX_TITLE = 3
        const val INDEX_ARTIST = 4
        const val INDEX_ALBUM = 5
        const val INDEX_STARTED_AT = 6
        const val INDEX_UPDATED_AT = 7
        const val INDEX_LISTENED_MS = 8
        const val INDEX_DURATION_MS = 9
        const val INDEX_LAST_POSITION_MS = 10
        const val INDEX_CLOSED = 11

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
