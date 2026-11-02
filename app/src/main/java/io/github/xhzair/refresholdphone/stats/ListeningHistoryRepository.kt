package io.github.xhzair.refresholdphone.stats

import android.content.Context
import androidx.annotation.MainThread
import io.github.xhzair.refresholdphone.local.LocalTrack
import java.util.Calendar
import java.util.TimeZone

/**
 * Owns the listening-history store and session tracker for the application process.
 *
 * Every public method is main-thread confined. [ListeningSessionTracker] keeps its active session
 * in memory and [ListeningStatsStore] enqueues database work onto a single worker executor, so
 * callers never block on disk I/O. Month-read and clear callbacks are posted back to the main
 * thread.
 */
class ListeningHistoryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = ListeningStatsStore(appContext)
    private val tracker = ListeningSessionTracker(store::upsert)

    /** Closes sessions left open by a previous process; no listening time is invented. */
    @MainThread
    fun closeStaleOnStart() {
        store.closeStaleOpenSessions(System.currentTimeMillis())
    }

    /** Reports the current local-playback state for [track]. */
    @MainThread
    fun onLocal(track: LocalTrack, playing: Boolean, positionMs: Long) {
        tracker.onObserved(
            PlaybackObservation(
                sourceKind = ListeningSourceKind.LOCAL,
                packageName = null,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                positionMs = positionMs,
                playing = playing,
            ),
            System.currentTimeMillis(),
        )
    }

    /** Reports an external playback observation; non-external observations are ignored. */
    @MainThread
    fun onExternal(observation: PlaybackObservation) {
        if (observation.sourceKind != ListeningSourceKind.EXTERNAL) return
        tracker.onObserved(observation, System.currentTimeMillis())
    }

    /** Closes the active session if any, persisting its final state. */
    @MainThread
    fun flush() = tracker.flush(System.currentTimeMillis())

    /** Closes the active session only when it belongs to an external source. */
    @MainThread
    fun flushExternal() = tracker.flush(System.currentTimeMillis(), ListeningSourceKind.EXTERNAL)

    /** Closes the active session only when it belongs to local playback. */
    @MainThread
    fun flushLocal() = tracker.flush(System.currentTimeMillis(), ListeningSourceKind.LOCAL)

    /**
     * Loads the persisted summary for a month and posts it to [onResult] on the main thread.
     *
     * The month window is computed here for the query; [ListeningStatsAggregator.summarize]
     * re-derives the same window when filtering, so both calculations must stay aligned.
     *
     * @throws IllegalArgumentException if [monthZeroBased] is outside `0..11`.
     */
    @MainThread
    fun loadMonth(
        year: Int,
        monthZeroBased: Int,
        onResult: (Result<MonthlyListeningSummary>) -> Unit,
    ) {
        require(monthZeroBased in 0..11) { "monthZeroBased must be in 0..11" }
        val zone = TimeZone.getDefault()
        val calendar = Calendar.getInstance(zone).apply {
            clear()
            set(year, monthZeroBased, 1, 0, 0, 0)
        }
        val start = calendar.timeInMillis
        calendar.add(Calendar.MONTH, 1)
        val end = calendar.timeInMillis
        store.loadSessions(start, end) { result ->
            onResult(
                result.map { sessions ->
                    ListeningStatsAggregator.summarize(sessions, year, monthZeroBased, zone)
                },
            )
        }
    }

    /**
     * Clears persisted history and the tracker's active session.
     *
     * The tracker is flushed first so its active session becomes a final closed snapshot and is
     * cleared from memory; the delete is then enqueued behind that upsert, so a pre-clear session
     * cannot reappear when playback is observed again. [onResult] runs on the main thread and
     * receives `true` only when the delete succeeded.
     */
    @MainThread
    fun clear(onResult: (Boolean) -> Unit = {}) {
        tracker.flush(System.currentTimeMillis())
        store.clear(onResult)
    }

    /**
     * Persists any active session and releases the store.
     *
     * The flush upsert is enqueued before the store's close task; the store's single-thread
     * executor preserves that FIFO order so the final snapshot is written before the database
     * closes.
     */
    @MainThread
    fun shutdown() {
        flush()
        store.close()
    }
}
