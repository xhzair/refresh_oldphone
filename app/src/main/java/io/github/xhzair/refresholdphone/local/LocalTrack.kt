package io.github.xhzair.refresholdphone.local

data class LocalTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val albumId: Long = 0,
)
