package io.github.xhzair.refresholdphone.media

internal enum class CandidateSessionEvent { PLAYBACK, METADATA, DESTROYED }

/** Main-thread confined token ownership and selection policy. */
internal class PlaybackSessionCandidates<K, S>(
    private val tokenOf: (S) -> K,
    private val describe: (S) -> PlaybackSessionCandidate,
    private val observe: (S, (CandidateSessionEvent) -> Unit) -> (() -> Unit),
    private val onChanged: (S, CandidateSessionEvent) -> Unit,
) {
    private class Observation<S>(val session: S, var detach: (() -> Unit)? = null)
    private val observations = linkedMapOf<K, Observation<S>>()
    private var order = emptyList<K>()

    fun sync(sessions: List<S>) {
        val incoming = sessions.distinctBy(tokenOf)
        order = incoming.map(tokenOf)
        observations.keys.toList().filterNot(order::contains).forEach(::remove)
        incoming.forEach { session ->
            val token = tokenOf(session)
            if (token !in observations) {
                val observation = Observation(session)
                observations[token] = observation
                val detach = observe(session) { event ->
                    // Observation identity fences callbacks even when the same token is re-added.
                    if (observations[token] === observation) {
                        if (event == CandidateSessionEvent.DESTROYED) remove(token)
                        onChanged(session, event)
                    }
                }
                observation.detach = detach
                if (observations[token] !== observation) detach()
            }
        }
    }

    private fun remove(token: K) {
        val observation = observations.remove(token) ?: return
        observation.detach?.invoke()
    }

    fun clear() {
        val previous = observations.values.toList()
        observations.clear()
        order = emptyList()
        previous.forEach { it.detach?.invoke() }
    }

    fun values(): List<S> = order.mapNotNull { observations[it]?.session }

    fun selected(): S? {
        val sessions = values()
        return selectPlaybackSession(sessions.map(describe))?.let(sessions::get)
    }
}
