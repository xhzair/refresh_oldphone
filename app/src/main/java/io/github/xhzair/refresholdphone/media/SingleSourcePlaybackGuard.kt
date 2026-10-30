package io.github.xhzair.refresholdphone.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat

/**
 * Process-level single-active-source guard.
 *
 * The desktop bridge stops with the activity, so a user switching to another player would leave
 * two sources playing at once. This guard is owned by the Application and listens for active
 * MediaSession changes for as long as the app process lives (event-driven; no polling). When more
 * than one known media source reports PLAYING, the newest one is kept and the rest are paused
 * through their own transport controls; processes are never force-stopped.
 */
internal class SingleSourcePlaybackGuard(
    context: Context,
    private val mediaPackages: () -> Set<String>,
) {
    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(appContext, MediaNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var started = false

    fun start() = onMain {
        if (started || !accessEnabled()) return@onMain
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { onMain { enforce() } }
        try {
            manager.addOnActiveSessionsChangedListener(listener, component, handler)
        } catch (_: SecurityException) {
            return@onMain
        } catch (_: IllegalArgumentException) {
            return@onMain
        }
        sessionsListener = listener
        started = true
        enforce()
    }

    fun stop() = onMain {
        if (!started) return@onMain
        started = false
        sessionsListener?.let { listener ->
            try {
                manager.removeOnActiveSessionsChangedListener(listener)
            } catch (_: SecurityException) {
                // Access may have been revoked before cleanup.
            } catch (_: IllegalArgumentException) {
                // Already removed by the framework.
            }
        }
        sessionsListener = null
    }

    private fun enforce() {
        if (!started) return
        if (!accessEnabled()) {
            stop()
            return
        }
        val sessions = try {
            manager.getActiveSessions(component)
        } catch (_: SecurityException) {
            stop()
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        val playing = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (playing.size < 2) return
        val allowed = supportedPlaybackPackages + mediaPackages()
        val relevant = playing.filter { it.packageName in allowed }
        if (relevant.size < 2) return
        // The session whose playback state was updated most recently is the source that just
        // started; keep it and pause everything older.
        val keep = relevant.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L } ?: return
        relevant.filter { it.sessionToken != keep.sessionToken }.forEach(::pauseSafe)
    }

    private fun pauseSafe(source: MediaController) {
        val actions = source.playbackState?.actions ?: return
        if (actions and (PlaybackActions.PAUSE or PlaybackActions.PLAY_PAUSE) == 0L) return
        try {
            source.transportControls.pause()
        } catch (_: SecurityException) {
            // Access can be revoked between discovery and the control call.
        } catch (_: IllegalArgumentException) {
            // The session disappeared concurrently.
        } catch (_: IllegalStateException) {
            // The controller is no longer usable.
        }
    }

    private fun accessEnabled(): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(appContext).contains(appContext.packageName)
    } catch (_: SecurityException) {
        false
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }
}
