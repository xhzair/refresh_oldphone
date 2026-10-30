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
    /** `SystemClock.elapsedRealtime()` when [positionMs] was sampled by the player. */
    val positionUpdatedAtMs: Long = 0L,
    /** Playback speed reported by the session; used only for local progress interpolation. */
    val playbackSpeed: Float = 1f,
)

internal object PlaybackActions {
    // PlaybackState's stable public action bit values, kept primitive for JVM tests.
    const val PAUSE = 2L
    const val PLAY = 4L
    const val PREVIOUS = 16L
    const val NEXT = 32L
    const val PLAY_PAUSE = 512L
}

internal enum class PlaybackCommand { PLAY, PAUSE }

internal fun playPauseCommand(playing: Boolean, actions: Long): PlaybackCommand? {
    val required = if (playing) PlaybackActions.PAUSE else PlaybackActions.PLAY
    if (actions and (required or PlaybackActions.PLAY_PAUSE) == 0L) return null
    return if (playing) PlaybackCommand.PAUSE else PlaybackCommand.PLAY
}

internal object PlaybackSnapshotMapper {
    fun map(packageName: String, providerLabel: String, title: String?, artist: String?,
            durationMs: Long, positionMs: Long, playing: Boolean, actions: Long,
            positionUpdatedAtMs: Long = 0L, playbackSpeed: Float = 1f): PlaybackSnapshot {
        val label = providerLabel.trim().ifEmpty { packageName }
        val duration = durationMs.coerceAtLeast(0)
        val position = positionMs.coerceAtLeast(0).let {
            if (duration > 0) it.coerceAtMost(duration) else it
        }
        val speed = playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
        return PlaybackSnapshot(packageName, label, title?.trim().orEmpty().ifEmpty { label },
            artist?.trim().orEmpty(), duration, position, playing,
            PlaybackControls(
                playPauseCommand(playing, actions) != null,
                actions and PlaybackActions.PREVIOUS != 0L,
                actions and PlaybackActions.NEXT != 0L,
            ),
            positionUpdatedAtMs = positionUpdatedAtMs.coerceAtLeast(0),
            playbackSpeed = speed,
        )
    }
}
