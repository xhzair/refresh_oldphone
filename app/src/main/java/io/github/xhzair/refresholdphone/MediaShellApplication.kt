package io.github.xhzair.refresholdphone

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.xhzair.refresholdphone.apps.AndroidInstalledAppSource
import io.github.xhzair.refresholdphone.apps.AppCatalog
import io.github.xhzair.refresholdphone.media.AndroidExternalPlaybackBridge
import io.github.xhzair.refresholdphone.prefs.UserPreferences
import io.github.xhzair.refresholdphone.system.AndroidSystemStatusSource
import io.github.xhzair.refresholdphone.ui.HomeViewModel

class MediaShellApplication : Application() {
    fun homeViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == HomeViewModel::class.java) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            val context = this@MediaShellApplication
            val preferences = UserPreferences(context)
            // Each ViewModel owns adapters with independent start/stop lifetimes.
            return modelClass.cast(HomeViewModel(
                catalog = AppCatalog(AndroidInstalledAppSource(context.packageManager)),
                selectedPackages = preferences::selectedPackages,
                playbackBridge = AndroidExternalPlaybackBridge(context),
                statusSource = AndroidSystemStatusSource(context),
            ))!!
        }
    }
}
