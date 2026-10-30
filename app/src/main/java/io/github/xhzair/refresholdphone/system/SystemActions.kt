package io.github.xhzair.refresholdphone.system

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri

class SystemActions(
    private val context: Context,
) {
    fun launchPackage(packageName: String): Boolean {
        val launchIntent = try {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: SecurityException) {
            null
        } ?: return false

        return startIfResolvable(launchIntent)
    }

    fun openAppDetails(packageName: String) {
        openSettings(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    fun openWifiSettings() {
        openShortcut(SettingsShortcut.WIFI)
    }

    fun openMobileNetworkSettings() {
        openShortcut(SettingsShortcut.MOBILE_NETWORK)
    }

    fun openBluetoothSettings() {
        openShortcut(SettingsShortcut.BLUETOOTH)
    }

    fun openVolumeSettings() {
        openShortcut(SettingsShortcut.VOLUME)
    }

    fun openNotificationAccessSettings() {
        openShortcut(SettingsShortcut.NOTIFICATION_ACCESS)
    }

    fun openHomeChooser() {
        openShortcut(SettingsShortcut.HOME)
    }

    fun openSystemSettings() {
        openShortcut(SettingsShortcut.SYSTEM)
    }

    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            openSystemSettings()
        } catch (_: SecurityException) {
            openSystemSettings()
        }
    }

    fun openSelfAppDetails() {
        openAppDetails(context.packageName)
    }

    private fun openShortcut(shortcut: SettingsShortcut) {
        openSettings(*settingsShortcutActions(shortcut).map(::Intent).toTypedArray())
    }

    private fun openSettings(vararg candidates: Intent) {
        candidates.forEach { intent ->
            if (startIfResolvable(intent)) return
        }

        if (candidates.none { it.action == Settings.ACTION_SETTINGS }) {
            startIfResolvable(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun startIfResolvable(intent: Intent): Boolean {
        val preparedIntent = intent.apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolvable = try {
            preparedIntent.resolveActivity(context.packageManager) != null
        } catch (_: SecurityException) {
            false
        }
        if (!resolvable) return false

        return try {
            context.startActivity(preparedIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

internal enum class SettingsShortcut {
    WIFI,
    MOBILE_NETWORK,
    BLUETOOTH,
    VOLUME,
    NOTIFICATION_ACCESS,
    HOME,
    SYSTEM,
}

/**
 * Documented public Settings actions for each shortcut, tried in order. When none resolves,
 * [SystemActions] falls back to the general settings screen instead of guessing at vendor
 * components or privileged settings.
 */
internal fun settingsShortcutActions(shortcut: SettingsShortcut): List<String> = when (shortcut) {
    SettingsShortcut.WIFI -> listOf(Settings.ACTION_WIFI_SETTINGS)
    SettingsShortcut.MOBILE_NETWORK ->
        listOf(Settings.ACTION_DATA_ROAMING_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
    SettingsShortcut.BLUETOOTH -> listOf(Settings.ACTION_BLUETOOTH_SETTINGS)
    SettingsShortcut.VOLUME -> listOf(Settings.ACTION_SOUND_SETTINGS)
    SettingsShortcut.NOTIFICATION_ACCESS -> listOf(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    SettingsShortcut.HOME -> listOf(Settings.ACTION_HOME_SETTINGS)
    SettingsShortcut.SYSTEM -> listOf(Settings.ACTION_SETTINGS)
}
