package io.github.xhzair.refresholdphone.ui

import io.github.xhzair.refresholdphone.apps.MediaApp
import io.github.xhzair.refresholdphone.local.BluetoothOutput

enum class MediaSourceKind { APP, LOCAL_MUSIC, BLUETOOTH_MUSIC }

data class MediaSourceEntry(
    val id: String,
    val kind: MediaSourceKind,
    val label: String,
    val sublabel: String?,
    val available: Boolean,
    val official: Boolean,
    val packageName: String?,
)

object MediaSourceSelection {
    const val ID_LOCAL = "local"
    const val ID_BLUETOOTH = "bluetooth"

    fun appId(packageName: String): String = "app:$packageName"

    fun initialSourceId(lastSourceId: String?, legacyPackage: String?): String? =
        lastSourceId ?: legacyPackage?.let(::appId)

    fun entries(
        apps: List<MediaApp>,
        bluetooth: BluetoothOutput,
        localLabel: String,
        bluetoothLabel: String,
        disconnectedLabel: String,
    ): List<MediaSourceEntry> {
        val installed = apps.filter { it.installed }
        val appEntries = installed.map { app ->
            MediaSourceEntry(
                id = appId(app.packageName),
                kind = MediaSourceKind.APP,
                label = app.label,
                sublabel = null,
                available = true,
                official = app.official,
                packageName = app.packageName,
            )
        }
        val local = MediaSourceEntry(
            id = ID_LOCAL,
            kind = MediaSourceKind.LOCAL_MUSIC,
            label = localLabel,
            sublabel = null,
            available = true,
            official = false,
            packageName = null,
        )
        val bluetoothName = bluetooth.deviceName?.takeIf(String::isNotBlank)
        val bluetoothEntry = MediaSourceEntry(
            id = ID_BLUETOOTH,
            kind = MediaSourceKind.BLUETOOTH_MUSIC,
            label = if (bluetooth.connected && bluetoothName != null) {
                "$bluetoothLabel · $bluetoothName"
            } else {
                bluetoothLabel
            },
            sublabel = if (bluetooth.connected) null else disconnectedLabel,
            available = bluetooth.connected,
            official = false,
            packageName = null,
        )
        return appEntries.filter { it.official } + local + bluetoothEntry + appEntries.filterNot { it.official }
    }

    fun currentEntry(entries: List<MediaSourceEntry>, lastSourceId: String?): MediaSourceEntry? =
        entries.firstOrNull { it.id == lastSourceId }
            ?: entries.firstOrNull { it.available }
            ?: entries.firstOrNull()

    // 兼容 Task 6 之前的 MainActivity；Task 6 完成后可删除。
    fun availableSources(apps: List<MediaApp>): List<MediaApp> = apps.filter { it.installed }

    fun currentSource(apps: List<MediaApp>, lastLaunchedPackage: String?): MediaApp? {
        val installed = availableSources(apps)
        return installed.firstOrNull { it.packageName == lastLaunchedPackage } ?: installed.firstOrNull()
    }
}
