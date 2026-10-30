package io.github.xhzair.refresholdphone.media

class MediaNotificationListener : android.service.notification.NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaListenerConnections.update(true)
    }

    override fun onListenerDisconnected() {
        mediaListenerConnections.update(false)
        super.onListenerDisconnected()
    }
}
