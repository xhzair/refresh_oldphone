package io.github.xhzair.refresholdphone.media

import android.graphics.Bitmap

data class PlaybackControls(
    val canPlayPause: Boolean = false,
    val canSkipPrevious: Boolean = false,
    val canSkipNext: Boolean = false,
)

data class PlaybackSnapshot(
    val packageName: String,
    val providerLabel: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val controls: PlaybackControls,
    val artwork: Bitmap? = null,
)

internal object PlaybackActions {
    // PlaybackState's stable public action bit values, kept primitive for JVM tests.
    const val PAUSE = 2L
    const val PLAY = 4L
    const val PREVIOUS = 16L
    const val NEXT = 32L
    const val PLAY_PAUSE = 512L
}

internal object PlaybackSnapshotMapper {
    fun map(packageName: String, providerLabel: String, title: String?, artist: String?,
            durationMs: Long, positionMs: Long, playing: Boolean, actions: Long): PlaybackSnapshot {
        val label = providerLabel.trim().ifEmpty { packageName }
        val duration = durationMs.coerceAtLeast(0)
        val position = positionMs.coerceAtLeast(0).let {
            if (duration > 0) it.coerceAtMost(duration) else it
        }
        return PlaybackSnapshot(packageName, label, title?.trim().orEmpty().ifEmpty { label },
            artist?.trim().orEmpty(), duration, position, playing,
            PlaybackControls(
                actions and (PlaybackActions.PLAY or PlaybackActions.PAUSE or PlaybackActions.PLAY_PAUSE) != 0L,
                actions and PlaybackActions.PREVIOUS != 0L,
                actions and PlaybackActions.NEXT != 0L,
            ))
    }
}
