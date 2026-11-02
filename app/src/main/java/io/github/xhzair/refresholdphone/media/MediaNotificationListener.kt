package io.github.xhzair.refresholdphone.media

import io.github.xhzair.refresholdphone.MediaShellApplication

class MediaNotificationListener : android.service.notification.NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaListenerConnections.update(true)
        (application as? MediaShellApplication)?.ensureSingleSourceGuardStarted()
        (application as? MediaShellApplication)?.ensurePlaybackHistoryObserverStarted()
    }

    override fun onListenerDisconnected() {
        mediaListenerConnections.update(false)
        (application as? MediaShellApplication)?.stopSingleSourceGuard()
        (application as? MediaShellApplication)?.stopPlaybackHistoryObserver()
        super.onListenerDisconnected()
    }
}
