package io.github.xhzair.refresholdphone.system

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

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
        openSettings(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    fun openMobileNetworkSettings() {
        openSettings(
            Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
    }

    fun openBluetoothSettings() {
        openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    fun openVolumeSettings() {
        openSettings(Intent(Settings.ACTION_SOUND_SETTINGS))
    }

    fun openNotificationAccessSettings() {
        openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openHomeChooser() {
        openSettings(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    fun openSystemSettings() {
        openSettings(Intent(Settings.ACTION_SETTINGS))
    }

    fun openSelfAppDetails() {
        openAppDetails(context.packageName)
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
