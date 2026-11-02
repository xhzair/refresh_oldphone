package io.github.xhzair.refresholdphone.stats

import android.content.Context
import io.github.xhzair.refresholdphone.local.LocalTrack
import java.util.Calendar
import java.util.TimeZone

/**
 * Owns the listening-history store and session tracker for the application process.
 *
 * Local and external playback are reported here; the tracker turns observations into session
 * snapshots that [ListeningStatsStore] persists off the main thread. Month reads are also
 * asynchronous and their callbacks are already delivered on the main thread.
 */
class ListeningHistoryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = ListeningStatsStore(appContext)
    private val tracker = ListeningSessionTracker(store::upsert)

    /** Closes sessions left open by a previous process; no listening time is invented. */
    fun closeStaleOnStart() {
        store.closeStaleOpenSessions(System.currentTimeMillis())
    }

    /** Reports the current local-playback state for [track]. */
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
    fun onExternal(observation: PlaybackObservation) {
        if (observation.sourceKind != ListeningSourceKind.EXTERNAL) return
        tracker.onObserved(observation, System.currentTimeMillis())
    }

    /** Closes the active session if any, persisting its final state. */
    fun flush() = tracker.flush(System.currentTimeMillis())

    /** Loads the persisted summary for a month; [callback] runs on the main thread. */
    fun loadMonth(year: Int, monthZeroBased: Int, callback: (MonthlyListeningSummary) -> Unit) {
        val zone = TimeZone.getDefault()
        val calendar = Calendar.getInstance(zone).apply {
            clear()
            set(year, monthZeroBased, 1, 0, 0, 0)
        }
        val start = calendar.timeInMillis
        calendar.add(Calendar.MONTH, 1)
        val end = calendar.timeInMillis
        store.loadSessions(start, end) { sessions ->
            callback(ListeningStatsAggregator.summarize(sessions, year, monthZeroBased, zone))
        }
    }

    /** Deletes all persisted sessions; [callback] runs on the main thread when done. */
    fun clear(callback: (() -> Unit)? = null) = store.clear(callback)

    /** Persists any active session and releases the store. */
    fun shutdown() {
        flush()
        store.close()
    }
}
