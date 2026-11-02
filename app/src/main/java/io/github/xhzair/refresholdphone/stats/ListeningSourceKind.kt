package io.github.xhzair.refresholdphone.stats

enum class ListeningSourceKind { LOCAL, EXTERNAL }

data class PlaybackObservation(
    val sourceKind: ListeningSourceKind,
    val packageName: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
)
