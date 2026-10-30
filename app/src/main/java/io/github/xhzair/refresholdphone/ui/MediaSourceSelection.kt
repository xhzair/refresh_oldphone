package io.github.xhzair.refresholdphone.ui

import io.github.xhzair.refresholdphone.apps.MediaApp

/**
 * Pure selection policy for the compact media-source entry.
 * The home screen shows one small icon instead of a row of app cards; the dialog lists only
 * sources that are actually installed, in catalog order (official providers first).
 */
object MediaSourceSelection {
    fun availableSources(apps: List<MediaApp>): List<MediaApp> = apps.filter { it.installed }

    fun currentSource(apps: List<MediaApp>, lastLaunchedPackage: String?): MediaApp? {
        val installed = availableSources(apps)
        return installed.firstOrNull { it.packageName == lastLaunchedPackage } ?: installed.firstOrNull()
    }
}
