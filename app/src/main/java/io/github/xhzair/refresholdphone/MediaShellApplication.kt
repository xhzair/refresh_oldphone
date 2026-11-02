package io.github.xhzair.refresholdphone

import android.app.Application
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.xhzair.refresholdphone.apps.AndroidInstalledAppSource
import io.github.xhzair.refresholdphone.apps.AppCatalog
import io.github.xhzair.refresholdphone.media.AndroidExternalPlaybackBridge
import io.github.xhzair.refresholdphone.media.SingleSourcePlaybackGuard
import io.github.xhzair.refresholdphone.media.supportedPlaybackPackages
import io.github.xhzair.refresholdphone.prefs.UserPreferences
import io.github.xhzair.refresholdphone.stats.ListeningHistoryRepository
import io.github.xhzair.refresholdphone.stats.PlaybackHistoryObserver
import io.github.xhzair.refresholdphone.system.AndroidSystemStatusSource
import io.github.xhzair.refresholdphone.ui.HomeViewModel

class MediaShellApplication : Application() {
    private lateinit var singleSourceGuard: SingleSourcePlaybackGuard
    private lateinit var playbackHistoryObserver: PlaybackHistoryObserver

    lateinit var listeningHistory: ListeningHistoryRepository

    override fun onCreate() {
        super.onCreate()
        val preferences = UserPreferences(this)
        singleSourceGuard = SingleSourcePlaybackGuard(this, preferences::selectedPackages)
        singleSourceGuard.start()
        listeningHistory = ListeningHistoryRepository(this)
        listeningHistory.closeStaleOnStart()
        playbackHistoryObserver = PlaybackHistoryObserver(this, listeningHistory) {
            supportedPlaybackPackages + UserPreferences(this).selectedPackages()
        }
        playbackHistoryObserver.start()
    }

    /** Keeps the process-level exclusivity guard alive after permission grants or restarts. */
    fun ensureSingleSourceGuardStarted() = singleSourceGuard.start()

    fun stopSingleSourceGuard() = singleSourceGuard.stop()

    /** Starts external playback recording after notification access is granted. */
    fun ensurePlaybackHistoryObserverStarted() = playbackHistoryObserver.start()

    /** Detaches the observer when notification access is revoked or the listener disconnects. */
    fun stopPlaybackHistoryObserver() = playbackHistoryObserver.stop()

    /**
     * Instrumentation-only substitution point. Tests set this before launching an
     * activity so the real MediaSession/system adapters stay out of the test process.
     */
    @VisibleForTesting
    var homeViewModelOverride: (() -> HomeViewModel)? = null

    fun homeViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == HomeViewModel::class.java) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            homeViewModelOverride?.let { return modelClass.cast(it())!! }
            val context = this@MediaShellApplication
            val preferences = UserPreferences(context)
            // Each ViewModel owns adapters with independent start/stop lifetimes.
            return modelClass.cast(HomeViewModel(
                catalog = AppCatalog(AndroidInstalledAppSource(context.packageManager)),
                selectedPackages = preferences::selectedPackages,
                playbackBridge = AndroidExternalPlaybackBridge(context, preferences::selectedPackages),
                statusSource = AndroidSystemStatusSource(context),
            ))!!
        }
    }
}
