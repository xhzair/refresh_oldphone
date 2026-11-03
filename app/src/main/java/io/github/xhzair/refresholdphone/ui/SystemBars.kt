package io.github.xhzair.refresholdphone.ui

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.xhzair.refresholdphone.prefs.UserPreferences

/**
 * Applies the desktop's immersive status-bar preference to an Activity.
 *
 * Called on every resume/focus so a status bar revealed by a system event (boot, USB
 * notification, transient swipe) is hidden again without requiring a trip through Settings.
 */
fun Activity.applyDesktopSystemBars() {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (UserPreferences(applicationContext).hideStatusBar) {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    } else {
        controller.show(WindowInsetsCompat.Type.statusBars())
    }
}
