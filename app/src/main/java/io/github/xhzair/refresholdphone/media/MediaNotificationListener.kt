package io.github.xhzair.refresholdphone.media

import io.github.xhzair.refresholdphone.MediaShellApplication

class MediaNotificationListener : android.service.notification.NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaListenerConnections.update(true)
        (application as? MediaShellApplication)?.ensureSingleSourceGuardStarted()
    }

    override fun onListenerDisconnected() {
        mediaListenerConnections.update(false)
        (application as? MediaShellApplication)?.stopSingleSourceGuard()
        super.onListenerDisconnected()
    }
}
