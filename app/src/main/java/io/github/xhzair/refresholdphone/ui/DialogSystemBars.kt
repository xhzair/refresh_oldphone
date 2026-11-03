package io.github.xhzair.refresholdphone.ui

import android.app.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Keeps AlertDialogs immersive like the desktop: the system status bar must not flash back while
 * a dialog window is shown. The navigation bar is left visible so the user can always leave.
 */
fun Dialog.applyImmersiveStatusBar() {
    window?.let { window ->
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }
}
