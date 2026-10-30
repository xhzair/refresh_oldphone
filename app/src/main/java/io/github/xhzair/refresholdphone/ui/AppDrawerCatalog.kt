package io.github.xhzair.refresholdphone.ui

data class LauncherApp(val packageName: String, val label: String)

/**
 * Pure ordering/filtering for the all-apps drawer. The current desktop is excluded because it is
 * already the host of the drawer; entries are deduplicated by package and sorted by label.
 */
internal fun sortedLauncherApps(apps: List<LauncherApp>, selfPackage: String): List<LauncherApp> =
    apps.asSequence()
        .filter { it.packageName != selfPackage }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
        .toList()
