package io.github.xhzair.refresholdphone.ui

import io.github.xhzair.refresholdphone.media.PlaybackSnapshot

data class HomeWords(val emptyTitle: String, val emptyArtist: String, val recent: String,
    val status: String, val charging: String)

data class HomeUiModel(val title: String, val artist: String, val status: String,
    val showArtwork: Boolean, val showProgress: Boolean, val progress: Int,
    val showControls: Boolean, val canPlayPause: Boolean, val canPrevious: Boolean,
    val canNext: Boolean, val playing: Boolean, val showPermission: Boolean,
    val progressMax: Int = 1000)

object HomeUiModelMapper {
    fun map(state: HomeState, words: HomeWords, lastPackage: String?, landscape: Boolean,
            nowElapsedMs: Long = 0L): HomeUiModel {
        val playback = state.playback
        val last = state.apps.firstOrNull { it.packageName == lastPackage }
        val artist = if (playback != null) listOf(playback.artist, playback.providerLabel)
            .filter(String::isNotBlank).distinct().joinToString(" · ")
        else if (landscape && last != null) words.recent.format(last.label) else words.emptyArtist
        val duration = playback?.durationMs ?: 0
        val position = playback?.let { interpolatedPosition(it, nowElapsedMs) } ?: 0L
        // Divide before scaling: even Long.MAX_VALUE durations cannot overflow.
        val progress = if (duration > 0) ((position.coerceIn(0, duration).toDouble() /
            duration.toDouble()) * 1000).toInt().coerceIn(0, 1000) else 0
        return HomeUiModel(playback?.title?.ifBlank { playback.providerLabel } ?: words.emptyTitle,
            artist, words.status.format(state.systemStatus.networkLabel,
                state.systemStatus.batteryPercent.coerceIn(0, 100), if (state.systemStatus.charging) words.charging else ""),
            playback?.artwork != null, duration > 0, progress, playback != null,
            playback?.controls?.canPlayPause == true, playback?.controls?.canSkipPrevious == true,
            playback?.controls?.canSkipNext == true, playback?.playing == true, !state.notificationAccessGranted)
    }

    /**
     * External players publish `position` only when something changes, so a playing snapshot is
     * advanced locally by the elapsed wall time. No MediaSession polling is involved; once a new
     * snapshot arrives its reported position re-anchors the bar.
     */
    private fun interpolatedPosition(playback: PlaybackSnapshot, nowElapsedMs: Long): Long {
        if (!playback.playing || playback.positionUpdatedAtMs <= 0L) return playback.positionMs
        if (nowElapsedMs <= playback.positionUpdatedAtMs) return playback.positionMs
        val elapsed = nowElapsedMs - playback.positionUpdatedAtMs
        val advanced = (elapsed.toDouble() * playback.playbackSpeed.toDouble()).toLong()
        val duration = playback.durationMs
        val interpolated = playback.positionMs + advanced
        return if (duration > 0) interpolated.coerceAtMost(duration) else interpolated
    }
}
