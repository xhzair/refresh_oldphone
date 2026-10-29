package io.github.xhzair.refresholdphone.system

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsShortcutActionsTest {
    @Test
    fun `every shortcut targets documented public settings actions`() {
        assertEquals(listOf("android.settings.WIFI_SETTINGS"), settingsShortcutActions(SettingsShortcut.WIFI))
        assertEquals(
            listOf("android.settings.DATA_ROAMING_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
            settingsShortcutActions(SettingsShortcut.MOBILE_NETWORK),
        )
        assertEquals(
            listOf("android.settings.BLUETOOTH_SETTINGS"),
            settingsShortcutActions(SettingsShortcut.BLUETOOTH),
        )
        assertEquals(
            listOf("android.settings.SOUND_SETTINGS"),
            settingsShortcutActions(SettingsShortcut.VOLUME),
        )
        assertEquals(
            listOf("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
            settingsShortcutActions(SettingsShortcut.NOTIFICATION_ACCESS),
        )
        assertEquals(
            listOf("android.settings.HOME_SETTINGS"),
            settingsShortcutActions(SettingsShortcut.HOME),
        )
        assertEquals(listOf("android.settings.SETTINGS"), settingsShortcutActions(SettingsShortcut.SYSTEM))
    }
}
