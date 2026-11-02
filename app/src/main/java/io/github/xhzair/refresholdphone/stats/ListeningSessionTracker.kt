package io.github.xhzair.refresholdphone.stats

import java.util.UUID

class ListeningSessionTracker(
    private val store: (ListeningSession) -> Unit,
    private val uidFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Active(
        val uid: String,
        val identity: String,
        var observation: PlaybackObservation,
        val startedAtMs: Long,
        var lastObservedAtMs: Long,
        var playing: Boolean,
        var listenedMs: Long,
        var lastPositionMs: Long,
    )

    private var active: Active? = null

    fun onObserved(observation: PlaybackObservation, nowMs: Long) {
        if (observation.title.isBlank() || nowMs < 0L) return
        val identity = identityOf(observation)
        val current = active
        if (current != null && current.identity != identity) {
            // A paused snapshot from a source that is no longer active (for example a late
            // pause from the previous source after a playback switch) must not close the active
            // session. Only a playing observation may switch identities.
            if (!observation.playing) return
            closeCurrent(nowMs)
        }
        val existing = active
        if (existing == null) {
            if (!observation.playing) return
            active = Active(
                uid = uidFactory(),
                identity = identity,
                observation = observation,
                startedAtMs = nowMs,
                lastObservedAtMs = nowMs,
                playing = true,
                listenedMs = 0,
                lastPositionMs = observation.positionMs,
            )
        } else {
            if (existing.playing) {
                existing.listenedMs += (nowMs - existing.lastObservedAtMs).coerceAtLeast(0)
            }
            existing.playing = observation.playing
            existing.observation = observation
            existing.lastPositionMs = observation.positionMs
            existing.lastObservedAtMs = nowMs
            persist(closed = false)
        }
    }

    /**
     * Closes the active session, optionally only when it belongs to [sourceKind].
     *
     * A scoped flush ignores an active session from another source, so each producer can flush
     * only the source it owns independently. A null [sourceKind] keeps the full-flush behavior
     * used for clear and shutdown.
     */
    fun flush(nowMs: Long, sourceKind: ListeningSourceKind? = null) {
        val current = active ?: return
        if (sourceKind != null && current.observation.sourceKind != sourceKind) return
        closeCurrent(nowMs)
    }

    private fun closeCurrent(nowMs: Long) {
        val current = active ?: return
        if (current.playing) {
            current.listenedMs += (nowMs - current.lastObservedAtMs).coerceAtLeast(0)
        }
        active = null
        persist(current, closed = true, nowMs = nowMs)
    }

    private fun persist(closed: Boolean) {
        active?.let { persist(it, closed, it.lastObservedAtMs) }
    }

    private fun persist(active: Active, closed: Boolean, nowMs: Long) {
        store(
            ListeningSession(
                uid = active.uid,
                sourceKind = active.observation.sourceKind,
                packageName = active.observation.packageName,
                title = active.observation.title,
                artist = active.observation.artist,
                album = active.observation.album,
                startedAtMs = active.startedAtMs,
                updatedAtMs = nowMs,
                listenedMs = active.listenedMs,
                durationMs = active.observation.durationMs,
                lastPositionMs = active.lastPositionMs,
                closed = closed,
            ),
        )
    }

    private fun identityOf(observation: PlaybackObservation): String =
        listOf(
            observation.sourceKind.name,
            observation.packageName.orEmpty(),
            observation.title.trim(),
            observation.artist.trim(),
            observation.album.trim(),
            observation.durationMs.toString(),
        ).joinToString("\u0000")
}
