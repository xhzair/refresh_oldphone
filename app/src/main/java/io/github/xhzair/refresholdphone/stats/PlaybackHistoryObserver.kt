package io.github.xhzair.refresholdphone.stats

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.core.app.NotificationManagerCompat
import io.github.xhzair.refresholdphone.BuildConfig
import io.github.xhzair.refresholdphone.media.MediaNotificationListener

/**
 * Process-level observer that reports external MediaSession playback to the listening history.
 *
 * The observer is owned by [io.github.xhzair.refresholdphone.MediaShellApplication] and is
 * event-driven: the active-session listener plus per-controller callbacks decide when to report,
 * so nothing polls. Only packages the desktop exposes are recorded, and this app's own sessions
 * are skipped because local playback is reported directly by the playback service.
 *
 * A playing session opens a history session through [ListeningHistoryRepository.onExternal].
 * When that session stops playing it is reported once as paused so the tracker keeps the session
 * open for a later resume; when the session disappears or its metadata changes, the session is
 * flushed. All state is main-thread confined, and [stop] removes every listener and callback.
 */
class PlaybackHistoryObserver(
    context: Context,
    private val repository: ListeningHistoryRepository,
    private val mediaPackages: () -> Set<String>,
) {
    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(appContext, MediaNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var started = false
    private var activeListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val controllers = mutableMapOf<MediaSession.Token, MediaController>()
    private val callbacks = mutableMapOf<MediaSession.Token, MediaController.Callback>()

    /** Session that currently owns the open listening session, if any. */
    private var trackedToken: MediaSession.Token? = null
    private var trackedMetaKey: String? = null
    private var pausedReported = false

    /** Last observation handed to the repository, used to skip identical consecutive reports. */
    private var lastEmitted: Emitted? = null

    private data class Metadata(
        val title: String,
        val artist: String,
        val album: String,
        val key: String,
    )

    private data class Emitted(
        val token: MediaSession.Token,
        val metaKey: String,
        val playing: Boolean,
        val positionMs: Long,
    )

    /** Registers for active-session changes; a no-op when access is missing or already started. */
    @MainThread
    fun start() {
        if (started) return
        if (!accessEnabled()) return
        val listener = MediaSessionManager.OnActiveSessionsChangedListener {
            // Removal on stop also invalidates already queued session events.
            if (started) syncSessions()
        }
        try {
            manager.addOnActiveSessionsChangedListener(listener, component, handler)
        } catch (_: SecurityException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        activeListener = listener
        started = true
        syncSessions()
    }

    /** Detaches from MediaSessionManager and closes the external session that was being tracked. */
    @MainThread
    fun stop() {
        started = false
        removeActiveListener()
        // Snapshot the tokens: unregistering must not observe a map that a reentrant callback edits.
        controllers.keys.toList().forEach(::removeController)
        controllers.clear()
        callbacks.clear()
        trackedToken = null
        trackedMetaKey = null
        pausedReported = false
        lastEmitted = null
        repository.flushExternal()
    }

    /** Re-checks notification access; stops when it was revoked and starts when it was granted. */
    @MainThread
    fun refresh() {
        if (started && !accessEnabled()) stop() else start()
    }

    private fun syncSessions() {
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
        val activeTokens = sessions.mapTo(mutableSetOf()) { it.sessionToken }
        controllers.keys.toList().filter { it !in activeTokens }.forEach(::removeController)
        sessions.forEach { controller ->
            if (controller.sessionToken !in controllers) register(controller)
        }
        evaluate()
    }

    private fun register(controller: MediaController) {
        val token = controller.sessionToken
        if (token in controllers) return
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                evaluate()
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                evaluate()
            }

            override fun onSessionDestroyed() {
                removeController(token)
                evaluate()
            }
        }
        try {
            controller.registerCallback(callback, handler)
        } catch (_: SecurityException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        controllers[token] = controller
        callbacks[token] = callback
    }

    private fun removeController(token: MediaSession.Token) {
        val callback = callbacks.remove(token)
        val controller = controllers.remove(token)
        if (callback == null || controller == null) return
        try {
            controller.unregisterCallback(callback)
        } catch (_: SecurityException) {
            // Access can be revoked between discovery and cleanup.
        } catch (_: IllegalArgumentException) {
            // The framework already removed the callback.
        } catch (_: IllegalStateException) {
            // The controller is no longer usable.
        }
    }

    private fun evaluate() {
        if (!started) return
        if (!accessEnabled()) {
            stop()
            return
        }
        val playing = controllers.values.firstOrNull { controller ->
            controller.isObserved() && controller.isPlaying()
        }
        if (playing != null) {
            reportPlaying(playing)
        } else {
            reportPausedIfNeeded()
        }
    }

    private fun reportPlaying(controller: MediaController) {
        val metadata = readMetadata(controller)
        if (metadata == null) {
            // A blank title cannot open a session; close the previous one instead of guessing.
            closeTracked()
            return
        }
        val token = controller.sessionToken
        trackedToken = token
        trackedMetaKey = metadata.key
        pausedReported = false
        val state = playbackStateOf(controller)
        emit(
            token,
            metadata.key,
            PlaybackObservation(
                sourceKind = ListeningSourceKind.EXTERNAL,
                packageName = controller.packageName,
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                // Duration metadata can change transiently while a track loads, and duration is
                // part of the tracker's session identity. It is deliberately left at zero so a
                // transient duration change cannot split one external session into two.
                durationMs = 0L,
                positionMs = (state?.position ?: 0L).coerceAtLeast(0L),
                playing = true,
            ),
        )
    }

    /**
     * Reports the tracked session as paused once, so a resume continues the same session instead
     * of opening a new one. A session that disappeared or changed identity is flushed instead.
     */
    private fun reportPausedIfNeeded() {
        val token = trackedToken ?: return
        val metaKey = trackedMetaKey ?: return
        val controller = controllers[token]
        if (controller == null) {
            closeTracked()
            return
        }
        val metadata = readMetadata(controller)
        if (metadata == null || metadata.key != metaKey) {
            closeTracked()
            return
        }
        if (pausedReported) return
        pausedReported = true
        val state = playbackStateOf(controller)
        emit(
            token,
            metaKey,
            PlaybackObservation(
                sourceKind = ListeningSourceKind.EXTERNAL,
                packageName = controller.packageName,
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                durationMs = 0L,
                positionMs = (state?.position ?: 0L).coerceAtLeast(0L),
                playing = false,
            ),
        )
    }

    private fun closeTracked() {
        repository.flushExternal()
        trackedToken = null
        trackedMetaKey = null
        pausedReported = false
        lastEmitted = null
    }

    private fun emit(
        token: MediaSession.Token,
        metaKey: String,
        observation: PlaybackObservation,
    ) {
        val signature = Emitted(token, metaKey, observation.playing, observation.positionMs)
        if (signature == lastEmitted) return
        lastEmitted = signature
        repository.onExternal(observation)
    }

    private fun readMetadata(controller: MediaController): Metadata? {
        val metadata = try {
            controller.metadata
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        } ?: return null
        val title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString().orEmpty().trim()
        if (title.isBlank()) return null
        val artist = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString().orEmpty().trim()
        val album = metadata.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString().orEmpty().trim()
        return Metadata(title, artist, album, "${controller.packageName}|$title|$artist|$album")
    }

    private fun playbackStateOf(controller: MediaController): PlaybackState? = try {
        controller.playbackState
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun MediaController.isObserved(): Boolean {
        val pkg = packageName ?: return false
        if (pkg == BuildConfig.APPLICATION_ID) return false
        return pkg in mediaPackages()
    }

    private fun MediaController.isPlaying(): Boolean =
        playbackStateOf(this)?.state == PlaybackState.STATE_PLAYING

    private fun accessEnabled(): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)
    } catch (_: SecurityException) {
        false
    }

    private fun removeActiveListener() {
        val listener = activeListener ?: return
        activeListener = null
        try {
            manager.removeOnActiveSessionsChangedListener(listener)
        } catch (_: SecurityException) {
            // Access may have been revoked before cleanup.
        } catch (_: IllegalArgumentException) {
            // The framework already removed the listener.
        }
    }
}
