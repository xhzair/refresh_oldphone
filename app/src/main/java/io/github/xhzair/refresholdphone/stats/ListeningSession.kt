package io.github.xhzair.refresholdphone.stats

data class ListeningSession(
    val uid: String,
    val sourceKind: ListeningSourceKind,
    val packageName: String?,
    val title: String,
    val artist: String,
    val album: String,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val lastPositionMs: Long,
    val closed: Boolean,
)
