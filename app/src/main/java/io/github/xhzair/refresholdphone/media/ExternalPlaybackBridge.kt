package io.github.xhzair.refresholdphone.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.scale

interface ExternalPlaybackBridge {
    fun start(onPlaybackChanged: (PlaybackSnapshot?) -> Unit, onAccessChanged: (Boolean) -> Unit)
    fun stop()
    fun playPause()
    fun skipPrevious()
    fun skipNext()
}

internal val supportedPlaybackPackages = setOf(
    "com.netease.cloudmusic", "com.tencent.qqmusic", "app.podcast.cosmos",
)

internal data class PlaybackSessionCandidate(
    val packageName: String,
    val playing: Boolean,
    val lastActive: Long?,
)

internal fun selectPlaybackSession(sessions: List<PlaybackSessionCandidate>): Int? {
    sessions.indexOfFirst { it.playing }.takeIf { it >= 0 }?.let { return it }
    val supported = sessions.indices.filter { sessions[it].packageName in supportedPlaybackPackages }
    return supported.filter { sessions[it].lastActive != null }
        .maxByOrNull { sessions[it].lastActive!! }
        ?: supported.firstOrNull() ?: sessions.indices.firstOrNull()
}

internal enum class PlaybackCommand { PLAY, PAUSE }

internal fun playPauseCommand(playing: Boolean, actions: Long): PlaybackCommand? {
    val required = if (playing) PlaybackActions.PAUSE else PlaybackActions.PLAY
    if (actions and (required or PlaybackActions.PLAY_PAUSE) == 0L) return null
    return if (playing) PlaybackCommand.PAUSE else PlaybackCommand.PLAY
}

/** All bridge state is confined to main. Off-main calls enqueue work, in call order.
 * Client callbacks run without locks and may reenter start/stop; revision checks fence emissions.
 */
class AndroidExternalPlaybackBridge(context: Context) : ExternalPlaybackBridge {
    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(applicationContext, MediaNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var revision = 0L
    private var lifecycle = 0L
    private var playbackCallback: ((PlaybackSnapshot?) -> Unit)? = null
    private var accessCallback: ((Boolean) -> Unit)? = null
    private var connectionListener: ((Boolean) -> Unit)? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var controller: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null
    private var snapshot: PlaybackSnapshot? = null
    private val recentActivity = mutableMapOf<String, Long>()
    private var activitySequence = 0L
    private var previousPlaying = emptySet<MediaSession.Token>()

    override fun start(onPlaybackChanged: (PlaybackSnapshot?) -> Unit, onAccessChanged: (Boolean) -> Unit) = onMain {
        revision++
        playbackCallback = onPlaybackChanged
        accessCallback = onAccessChanged
        if (!started) {
            started = true
            val epoch = ++lifecycle
            val listener: (Boolean) -> Unit = { connected -> onMain {
                if (started && lifecycle == epoch) {
                    if (connected) refreshSessions() else disconnected()
                }
            } }
            connectionListener = listener
            mediaListenerConnections.add(listener)
        }
        // Report settings access before any binder calls, and honor a reentrant stop/start.
        val expected = revision
        accessCallback?.invoke(accessEnabled())
        if (started && revision == expected) refreshSessions()
    }

    override fun stop() = onMain {
        started = false
        revision++
        lifecycle++
        connectionListener?.let(mediaListenerConnections::remove)
        connectionListener = null
        removeSessionsListener()
        selectController(null)
        snapshot = null
        playbackCallback = null
        accessCallback = null
        recentActivity.clear()
        previousPlaying = emptySet()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }

    private fun accessEnabled(): Boolean = try {
        applicationContext.packageName in NotificationManagerCompat.getEnabledListenerPackages(applicationContext)
    } catch (_: SecurityException) { false }

    private fun refreshSessions(excluded: MediaSession.Token? = null) {
        if (!started) return
        if (!accessEnabled()) { unavailable(false); return }
        val sessions = try {
            if (sessionsListener == null) {
                val epoch = lifecycle
                val listener = MediaSessionManager.OnActiveSessionsChangedListener {
                    onMain { if (started && lifecycle == epoch) refreshSessions() }
                }
                manager.addOnActiveSessionsChangedListener(listener, component, handler)
                sessionsListener = listener
            }
            manager.getActiveSessions(component).filter { it.sessionToken != excluded }
        } catch (_: SecurityException) { unavailable(false); return }
        catch (_: IllegalArgumentException) { unavailable(accessEnabled()); return }

        val nextSnapshot = try {
            val playing = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            playing.filter { it.sessionToken !in previousPlaying }.forEach { markActive(it) }
            previousPlaying = playing.mapTo(mutableSetOf()) { it.sessionToken }
            val index = selectPlaybackSession(sessions.map {
                PlaybackSessionCandidate(it.packageName, it.playbackState?.state == PlaybackState.STATE_PLAYING,
                    recentActivity[it.packageName])
            })
            selectController(index?.let(sessions::get))
            controller?.let(::toSnapshot)
        } catch (_: SecurityException) { unavailable(false); return }
        catch (_: IllegalArgumentException) { selectController(null); null }
        catch (_: IllegalStateException) { selectController(null); null }
        snapshot = nextSnapshot
        emit(true, nextSnapshot)
    }

    private fun markActive(source: MediaController) {
        if (source.packageName in supportedPlaybackPackages) {
            recentActivity[source.packageName] = ++activitySequence
        }
    }

    private fun selectController(next: MediaController?) {
        if (controller?.sessionToken == next?.sessionToken) return
        val old = controller
        val oldCallback = controllerCallback
        controller = null
        controllerCallback = null
        if (old != null && oldCallback != null) {
            try { old.unregisterCallback(oldCallback) }
            catch (_: SecurityException) { /* Access was revoked during cleanup. */ }
            catch (_: IllegalArgumentException) { /* Already removed by framework. */ }
        }
        if (next == null) return
        val epoch = lifecycle
        val callback = object : MediaController.Callback() {
            private fun changed() = onMain {
                if (started && lifecycle == epoch && controller?.sessionToken == next.sessionToken) {
                    markActive(next)
                    refreshSessions()
                }
            }
            override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
            override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
            override fun onSessionDestroyed() = onMain {
                if (started && lifecycle == epoch && controller?.sessionToken == next.sessionToken) {
                    selectController(null)
                    refreshSessions(excluded = next.sessionToken)
                }
            }
        }
        // Set ownership only after framework registration succeeds.
        next.registerCallback(callback, handler)
        controller = next
        controllerCallback = callback
    }

    private fun toSnapshot(source: MediaController): PlaybackSnapshot {
        val metadata = source.metadata
        val state = source.playbackState
        val label = try {
            val info = applicationContext.packageManager.getApplicationInfo(source.packageName, 0)
            applicationContext.packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) { source.packageName }
        val result = PlaybackSnapshotMapper.map(source.packageName, label,
            metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString(),
            metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString(),
            metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            state?.position ?: 0L, state?.state == PlaybackState.STATE_PLAYING, state?.actions ?: 0L)
        return result.copy(artwork = copyArtwork(metadata))
    }

    private fun copyArtwork(metadata: MediaMetadata?): Bitmap? {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: return null
        if (bitmap.isRecycled) return null
        return try {
            val maximum = maxOf(bitmap.width, bitmap.height)
            if (maximum > 512) {
                val ratio = 512.0 / maximum
                bitmap.scale((bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
            } else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: IllegalArgumentException) { null }
        catch (_: IllegalStateException) { null }
    }

    private fun disconnected() {
        removeSessionsListener()
        unavailable(accessEnabled())
    }

    private fun unavailable(access: Boolean) {
        removeSessionsListener()
        selectController(null)
        snapshot = null
        previousPlaying = emptySet()
        emit(access, null)
    }

    private fun emit(access: Boolean, playback: PlaybackSnapshot?) {
        val expected = revision
        accessCallback?.invoke(access)
        if (started && revision == expected) playbackCallback?.invoke(playback)
    }

    private fun removeSessionsListener() {
        val listener = sessionsListener ?: return
        sessionsListener = null
        try { manager.removeOnActiveSessionsChangedListener(listener) }
        catch (_: SecurityException) { /* Access may have been revoked. */ }
        catch (_: IllegalArgumentException) { /* Listener already removed. */ }
    }

    override fun playPause() = transport { source ->
        val state = source.playbackState
        when (playPauseCommand(state?.state == PlaybackState.STATE_PLAYING, state?.actions ?: 0L)) {
            PlaybackCommand.PLAY -> source.transportControls.play()
            PlaybackCommand.PAUSE -> source.transportControls.pause()
            null -> Unit
        }
    }

    override fun skipPrevious() = transport { source ->
        if (snapshot?.controls?.canSkipPrevious == true &&
            (source.playbackState?.actions ?: 0L) and PlaybackActions.PREVIOUS != 0L) {
            source.transportControls.skipToPrevious()
        }
    }

    override fun skipNext() = transport { source ->
        if (snapshot?.controls?.canSkipNext == true &&
            (source.playbackState?.actions ?: 0L) and PlaybackActions.NEXT != 0L) {
            source.transportControls.skipToNext()
        }
    }

    private fun transport(action: (MediaController) -> Unit) = onMain {
        if (!started) return@onMain
        if (!accessEnabled()) { unavailable(false); return@onMain }
        val source = controller ?: return@onMain
        try { action(source) }
        catch (_: SecurityException) { unavailable(false) }
        catch (_: IllegalArgumentException) { refreshSessions() }
        catch (_: IllegalStateException) { refreshSessions() }
    }
}
