package io.github.xhzair.refresholdphone.prefs

import android.content.Context
import androidx.core.content.edit

enum class OrientationMode { AUTO, PORTRAIT, LANDSCAPE }

class UserPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    var orientationMode: OrientationMode
        get() {
            val stored = preferences.getString(KEY_ORIENTATION, null)
            return OrientationMode.entries.firstOrNull { it.name == stored } ?: OrientationMode.AUTO
        }
        set(value) {
            preferences.edit { putString(KEY_ORIENTATION, value.name) }
        }

    var keepScreenOn: Boolean
        get() = preferences.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) {
            preferences.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }
        }

    var hideStatusBar: Boolean
        get() = preferences.getBoolean(KEY_HIDE_STATUS_BAR, true)
        set(value) {
            preferences.edit { putBoolean(KEY_HIDE_STATUS_BAR, value) }
        }

    var lastLaunchedMediaPackage: String?
        get() = preferences.getString(KEY_LAST_LAUNCHED_MEDIA_PACKAGE, null)
            ?.takeUnless(String::isBlank)
        set(value) {
            preferences.edit { putString(KEY_LAST_LAUNCHED_MEDIA_PACKAGE, value) }
        }

    var lastMediaSourceId: String?
        get() = preferences.getString(KEY_LAST_MEDIA_SOURCE, null)?.takeUnless(String::isBlank)
        set(value) {
            preferences.edit { putString(KEY_LAST_MEDIA_SOURCE, value) }
        }

    fun selectedPackages(): Set<String> =
        preferences.getStringSet(KEY_SELECTED_PACKAGES, emptySet()).orEmpty().toSet()

    fun setSelectedPackages(value: Set<String>) {
        preferences.edit { putStringSet(KEY_SELECTED_PACKAGES, value.toSet()) }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private companion object {
        const val PREFERENCES_FILE = "media_shell"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_HIDE_STATUS_BAR = "hide_status_bar"
        const val KEY_LAST_LAUNCHED_MEDIA_PACKAGE = "last_launched_media_package"
        const val KEY_LAST_MEDIA_SOURCE = "last_media_source"
        const val KEY_SELECTED_PACKAGES = "selected_apps"
    }
}
