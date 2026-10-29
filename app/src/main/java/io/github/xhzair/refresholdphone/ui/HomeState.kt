package io.github.xhzair.refresholdphone.ui

import io.github.xhzair.refresholdphone.apps.MediaApp
import io.github.xhzair.refresholdphone.media.PlaybackSnapshot
import io.github.xhzair.refresholdphone.system.SystemStatus

data class HomeState(
    val apps: List<MediaApp> = emptyList(),
    val playback: PlaybackSnapshot? = null,
    val systemStatus: SystemStatus = SystemStatus(),
    val notificationAccessGranted: Boolean = false,
)
