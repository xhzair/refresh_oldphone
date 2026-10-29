package io.github.xhzair.refresholdphone.ui

import io.github.xhzair.refresholdphone.apps.MediaApp

data class HomeWords(val emptyTitle: String, val emptyArtist: String, val recent: String,
    val status: String, val charging: String, val installed: String, val missing: String)

data class HomeUiModel(val title: String, val artist: String, val status: String,
    val showArtwork: Boolean, val showProgress: Boolean, val progress: Int,
    val showControls: Boolean, val canPlayPause: Boolean, val canPrevious: Boolean,
    val canNext: Boolean, val playing: Boolean, val showPermission: Boolean,
    val progressMax: Int = 1000)

object HomeUiModelMapper {
    fun map(state: HomeState, words: HomeWords, lastPackage: String?, landscape: Boolean): HomeUiModel {
        val playback = state.playback
        val last = state.apps.firstOrNull { it.packageName == lastPackage }
        val artist = if (playback != null) listOf(playback.artist, playback.providerLabel)
            .filter(String::isNotBlank).distinct().joinToString(" · ")
        else if (landscape && last != null) words.recent.format(last.label) else words.emptyArtist
        val duration = playback?.durationMs ?: 0
        // Divide before scaling: even Long.MAX_VALUE durations cannot overflow.
        val progress = if (duration > 0) ((playback!!.positionMs.coerceIn(0, duration).toDouble() /
            duration.toDouble()) * 1000).toInt().coerceIn(0, 1000) else 0
        return HomeUiModel(playback?.title?.ifBlank { playback.providerLabel } ?: words.emptyTitle,
            artist, words.status.format(state.systemStatus.networkLabel,
                state.systemStatus.batteryPercent.coerceIn(0, 100), if (state.systemStatus.charging) words.charging else ""),
            playback?.artwork != null, duration > 0, progress, playback != null,
            playback?.controls?.canPlayPause == true, playback?.controls?.canSkipPrevious == true,
            playback?.controls?.canSkipNext == true, playback?.playing == true, !state.notificationAccessGranted)
    }

    fun appStatus(app: MediaApp, words: HomeWords): String = if (app.installed) words.installed else words.missing
}
