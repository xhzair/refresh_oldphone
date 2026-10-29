package io.github.xhzair.refresholdphone.apps

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

interface InstalledAppSource {
    fun isLaunchable(packageName: String): Boolean

    fun label(packageName: String): String

    fun launchablePackages(): List<String>
}

class AndroidInstalledAppSource(
    private val packageManager: PackageManager,
) : InstalledAppSource {
    override fun isLaunchable(packageName: String): Boolean = try {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        resolveActivity(launchIntent)
    } catch (_: SecurityException) {
        false
    }

    override fun label(packageName: String): String = try {
        val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        packageManager.getApplicationLabel(applicationInfo).toString().ifBlank { packageName }
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    override fun launchablePackages(): List<String> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }

        return matches
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .sorted()
    }

    private fun resolveActivity(intent: Intent): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(0),
            ) != null
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0) != null
        }
}
