package io.github.xhzair.refresholdphone.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.xhzair.refresholdphone.MainActivity
import io.github.xhzair.refresholdphone.R
import java.util.concurrent.Executors

/**
 * Resolves the requested track id to its index in the loaded queue. A missing id must not fall
 * back to the raw requested index, or a removed track would silently play a different song.
 */
internal fun resolveStartIndex(requestedId: Long?, tracks: List<LocalTrack>): Int {
    if (requestedId == null) return -1
    return tracks.indexOfFirst { it.id == requestedId }
}

/**
 * Clamps a seek target to a valid MediaPlayer position. MediaPlayer.duration can be -1 while the
 * source is unknown, and coerceIn(0, -1) would throw IllegalArgumentException.
 */
internal fun clampSeekPosition(position: Long, duration: Int): Int {
    val target = if (duration > 0) {
        position.coerceIn(0L, duration.toLong())
    } else {
        position.coerceAtLeast(0L)
    }
    return target.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

class LocalPlaybackService : Service() {
    private companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "local_music_playback"
        const val MEDIA_SESSION_TAG = "LocalPlaybackService"

        const val REQUEST_CONTENT = 1
        const val REQUEST_STOP = 2
        const val REQUEST_PREVIOUS = 3
        const val REQUEST_TOGGLE = 4
        const val REQUEST_NEXT = 5
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val libraryExecutor = Executors.newSingleThreadExecutor()
    private val artworkExecutor = Executors.newSingleThreadExecutor()

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSession: MediaSession

    private var player: MediaPlayer? = null
    private var queue: LocalPlaybackQueue? = null
    private var playing = false
    private var prepared = false
    private var ended = false
    private var pausedForFocus = false
    private var hasAudioFocus = false
    private var focusRequested = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var noiseReceiverRegistered = false
    private var foregroundStarted = false
    private var destroyed = false
    private var loadGeneration = 0

    /**
     * Identifies the current prepare attempt. MediaPlayer callbacks can be delivered after the
     * player was reset/released, so every callback checks this token before touching playback.
     */
    private var playbackToken = 0L
    private var autoPlayWhenPrepared = false
    private var playbackError = false

    private val audioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                restorePlayerVolume()
                if (pausedForFocus) {
                    pausedForFocus = false
                    startOrResumePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (playing || pausedForFocus) {
                    pausePlaybackInternal(abandonFocus = false, resumeOnFocusGain = false)
                }
                // A permanent loss is not recoverable: release the request/listener bookkeeping.
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (playing) pauseForFocus(resumeOnFocusGain = true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckPlayerVolume()
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && playing) {
                pausePlayback()
            }
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback() {
        override fun onPlay() = startOrResumePlayback()

        override fun onPause() = pausePlayback()

        override fun onSkipToNext() = skipNext()

        override fun onSkipToPrevious() = skipPrevious()

        override fun onSeekTo(pos: Long) = seekTo(pos)

        override fun onStop() = stopPlayback()
    }

    // The legacy media-button/transport flags are deprecated on newer APIs but are still needed
    // for older and AVRCP media-button routing.
    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        mediaSession = MediaSession(this, MEDIA_SESSION_TAG).apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(mediaSessionCallback)
            setSessionActivity(contentPendingIntent())
            isActive = true
        }
        registerNoisyReceiver()
        publishState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            LocalPlaybackContract.ACTION_PLAY_QUEUE -> {
                val ids = intent.getLongArrayExtra(LocalPlaybackContract.EXTRA_TRACK_IDS)
                    ?: LongArray(0)
                val startIndex = intent.getIntExtra(LocalPlaybackContract.EXTRA_START_INDEX, 0)
                startQueue(ids, startIndex)
            }
            LocalPlaybackContract.ACTION_TOGGLE -> handleControl { togglePlayback() }
            LocalPlaybackContract.ACTION_NEXT -> handleControl { skipNext() }
            LocalPlaybackContract.ACTION_PREVIOUS -> handleControl { skipPrevious() }
            LocalPlaybackContract.ACTION_STOP -> {
                if (!foregroundStarted) startForegroundPlaceholder()
                stopPlayback()
            }
            else -> if (!foregroundStarted) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlayback()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        loadGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        abandonAudioFocus()
        if (noiseReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver)
            } catch (_: IllegalArgumentException) {
            }
            noiseReceiverRegistered = false
        }
        mediaSession.setMetadata(null)
        mediaSession.isActive = false
        mediaSession.release()
        libraryExecutor.shutdownNow()
        artworkExecutor.shutdownNow()
        LocalNowPlayingState.update(null, false)
        super.onDestroy()
    }

    private fun handleControl(control: () -> Unit) {
        if (queue?.current != null) {
            control()
        } else if (!foregroundStarted) {
            // A service started with startForegroundService must become foreground promptly.
            startForegroundPlaceholder()
            stopPlayback()
        }
    }

    private fun startQueue(ids: LongArray, startIndex: Int) {
        val generation = ++loadGeneration
        resetPlayback()
        // Show a placeholder before touching MediaStore so the 5 second startForeground deadline
        // is met even when loading the queue is slow or fails.
        startForegroundPlaceholder()

        val requestedId = ids.getOrNull(startIndex)
        if (requestedId == null) {
            stopPlayback()
            return
        }

        libraryExecutor.execute {
            val tracks = try {
                LocalLibrary.loadByIds(this, ids)
            } catch (_: Exception) {
                null
            }
            if (destroyed || generation != loadGeneration) return@execute
            if (tracks == null) {
                // Never leave the placeholder foreground running without a resolved queue.
                mainHandler.post {
                    if (destroyed || generation != loadGeneration) return@post
                    stopPlayback()
                }
                return@execute
            }

            val resolved = resolveStartIndex(requestedId, tracks)
            mainHandler.post {
                if (destroyed || generation != loadGeneration) return@post
                if (resolved < 0) {
                    stopPlayback()
                    return@post
                }
                queue = LocalPlaybackQueue(tracks, resolved)
                startCurrentTrack(autoPlay = true)
            }
        }
    }

    private fun resetPlayback() {
        releasePlayer()
        queue = null
        playing = false
        prepared = false
        ended = false
        pausedForFocus = false
        playbackError = false
        abandonAudioFocus()
        mediaSession.setMetadata(null)
        LocalNowPlayingState.update(null, false)
        publishState()
    }

    private fun startForegroundPlaceholder() {
        val notification = buildNotification(track = null, isPlaying = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun startCurrentTrack(autoPlay: Boolean = true) {
        val track = queue?.current ?: run {
            pausePlayback()
            return
        }

        releasePlayer()
        updateMetadata(track)

        playing = false
        playbackError = false
        ended = false
        autoPlayWhenPrepared = autoPlay
        // releasePlayer() bumped the token, so this prepare attempt has a unique identity.
        val token = playbackToken

        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setAudioAttributes(audioAttributes)
            mediaPlayer.setDataSource(this, LocalLibrary.contentUri(track.id))
            mediaPlayer.setVolume(1f, 1f)
            mediaPlayer.setOnPreparedListener { preparedPlayer ->
                onPlayerPrepared(token, track.id, preparedPlayer)
            }
            mediaPlayer.setOnCompletionListener { completedPlayer ->
                onPlayerCompleted(token, completedPlayer)
            }
            mediaPlayer.setOnErrorListener { failedPlayer, _, _ ->
                onPlayerError(token, failedPlayer)
                true
            }
            // Never block the main thread; onPlayerPrepared starts playback once ready.
            mediaPlayer.prepareAsync()
        } catch (_: Exception) {
            handlePlaybackError()
            return
        }

        publishPlayback()
    }

    private fun onPlayerPrepared(token: Long, trackId: Long, mediaPlayer: MediaPlayer) {
        if (!isCurrentPlayer(token, mediaPlayer)) return
        // A stale prepare must never start a different queue track.
        if (queue?.current?.id != trackId) return

        prepared = true
        playbackError = false
        ended = false
        restorePlayerVolume()

        if (!autoPlayWhenPrepared) {
            playing = false
            publishPlayback()
            return
        }
        autoPlayWhenPrepared = false

        if (!requestAudioFocus()) {
            playing = false
            publishPlayback()
            return
        }

        try {
            mediaPlayer.start()
        } catch (_: IllegalStateException) {
            handlePlaybackError()
            return
        }
        playing = true
        pausedForFocus = false
        publishPlayback()
    }

    private fun onPlayerCompleted(token: Long, mediaPlayer: MediaPlayer) {
        if (!isCurrentPlayer(token, mediaPlayer)) return
        onTrackCompleted()
    }

    private fun onPlayerError(token: Long, mediaPlayer: MediaPlayer) {
        if (!isCurrentPlayer(token, mediaPlayer)) return
        handlePlaybackError()
    }

    private fun isCurrentPlayer(token: Long, mediaPlayer: MediaPlayer): Boolean =
        !destroyed && token == playbackToken && player === mediaPlayer

    private fun startOrResumePlayback() {
        if (queue?.current == null) return
        val mediaPlayer = player
        if (mediaPlayer == null) {
            startCurrentTrack(autoPlay = true)
            return
        }
        if (!prepared) {
            // Still preparing: remember the intent and let onPlayerPrepared start it.
            autoPlayWhenPrepared = true
            return
        }

        if (!requestAudioFocus()) {
            playing = false
            publishPlayback()
            return
        }

        if (ended) {
            if (!seekPlayerTo(mediaPlayer, 0L)) {
                handlePlaybackError()
                return
            }
            ended = false
        }

        restorePlayerVolume()
        try {
            mediaPlayer.start()
        } catch (_: IllegalStateException) {
            handlePlaybackError()
            return
        }
        playing = true
        playbackError = false
        pausedForFocus = false
        publishPlayback()
    }

    private fun togglePlayback() {
        if (playing) pausePlayback() else startOrResumePlayback()
    }

    private fun pausePlayback() {
        pausePlaybackInternal(abandonFocus = true, resumeOnFocusGain = false)
    }

    private fun pauseForFocus(resumeOnFocusGain: Boolean) {
        pausePlaybackInternal(abandonFocus = false, resumeOnFocusGain = resumeOnFocusGain)
    }

    private fun pausePlaybackInternal(abandonFocus: Boolean, resumeOnFocusGain: Boolean) {
        val mediaPlayer = player
        if (mediaPlayer != null && prepared) {
            try {
                if (mediaPlayer.isPlaying) mediaPlayer.pause()
            } catch (_: IllegalStateException) {
            }
        }
        playing = false
        ended = false
        // A pause while a prepare is still in flight must not start playback when it completes.
        autoPlayWhenPrepared = false
        pausedForFocus = resumeOnFocusGain
        if (abandonFocus) {
            // Never clear the focus flags without actually abandoning: pauseForFocus keeps focus
            // so a transient loss can resume, and abandonAudioFocus performs the cleanup.
            abandonAudioFocus()
        }
        publishPlayback()
    }

    private fun skipNext() {
        if (queue?.moveNext() == null) return
        startCurrentTrack(autoPlay = playing)
    }

    private fun skipPrevious() {
        if (queue?.movePrevious() == null) return
        startCurrentTrack(autoPlay = playing)
    }

    private fun seekTo(position: Long) {
        val mediaPlayer = player ?: return
        if (!prepared) return
        if (!seekPlayerTo(mediaPlayer, position)) return
        ended = false
        publishState()
    }

    private fun seekPlayerTo(mediaPlayer: MediaPlayer, position: Long): Boolean =
        try {
            val duration = mediaPlayer.duration
            mediaPlayer.seekTo(clampSeekPosition(position, duration))
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun onTrackCompleted() {
        val next = queue?.moveNext()
        if (next == null) {
            ended = true
            playing = false
            pausedForFocus = false
            abandonAudioFocus()
            publishPlayback()
        } else {
            startCurrentTrack(autoPlay = true)
        }
    }

    private fun handlePlaybackError() {
        playbackError = true
        releasePlayer()
        playing = false
        ended = false
        pausedForFocus = false
        abandonAudioFocus()
        publishPlayback()
    }

    private fun releasePlayer() {
        // Invalidate pending prepare/completion callbacks before the old player goes away.
        playbackToken += 1
        prepared = false
        autoPlayWhenPrepared = false

        val mediaPlayer = player ?: return
        player = null
        restoreVolume(mediaPlayer)
        try {
            mediaPlayer.reset()
        } catch (_: IllegalStateException) {
        } catch (_: IllegalArgumentException) {
        }
        try {
            mediaPlayer.release()
        } catch (_: IllegalStateException) {
        }
    }

    private fun restoreVolume(mediaPlayer: MediaPlayer) {
        try {
            mediaPlayer.setVolume(1f, 1f)
        } catch (_: IllegalStateException) {
        }
    }

    private fun restorePlayerVolume() {
        player?.let(::restoreVolume)
    }

    private fun duckPlayerVolume() {
        val mediaPlayer = player ?: return
        try {
            mediaPlayer.setVolume(0.2f, 0.2f)
        } catch (_: IllegalStateException) {
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasAudioFocus) focusRequested = true
        return hasAudioFocus
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        // focusRequested stays true across a transient-loss pause, so cleanup cannot be skipped
        // just because playback (and hasAudioFocus) was flipped to false.
        if (!focusRequested) return
        restorePlayerVolume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            audioManager.abandonAudioFocus(focusListener)
        }
        // Only after the abandon request is issued: drop the listener and its bookkeeping.
        audioFocusRequest = null
        hasAudioFocus = false
        focusRequested = false
    }

    private fun registerNoisyReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noiseReceiverRegistered = true
    }

    private fun publishPlayback() {
        publishState()
        updateNotification()
        LocalNowPlayingState.update(queue?.current, playing, playbackError)
    }

    private fun publishState() {
        val track = queue?.current
        val state = when {
            track == null -> PlaybackState.STATE_NONE
            playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        var actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        if (queue?.hasNext == true) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (queue?.hasPrevious == true) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, currentPosition(), if (playing) 1f else 0f)
                .build(),
        )
    }

    private fun currentPosition(): Long =
        try {
            player?.currentPosition?.toLong() ?: 0L
        } catch (_: IllegalStateException) {
            0L
        }

    private fun updateMetadata(track: LocalTrack) {
        mediaSession.setMetadata(buildMetadata(track, artwork = null))
        artworkExecutor.execute {
            val bitmap = try {
                LocalArtwork.load(this, track)
            } catch (_: Exception) {
                null
            }
            if (bitmap == null) return@execute
            mainHandler.post {
                if (destroyed || queue?.current?.id != track.id) return@post
                mediaSession.setMetadata(buildMetadata(track, bitmap))
                updateNotification()
            }
        }
    }

    private fun buildMetadata(track: LocalTrack, artwork: Bitmap?): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
        if (artwork != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
        }
        return builder.build()
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(queue?.current, playing),
        )
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(track: LocalTrack?, isPlaying: Boolean): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(track?.title ?: getString(R.string.local_music))
            .setContentIntent(contentPendingIntent())
            .setDeleteIntent(servicePendingIntent(LocalPlaybackContract.ACTION_STOP, REQUEST_STOP))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .addAction(
                notificationAction(
                    R.drawable.ic_previous,
                    R.string.previous,
                    LocalPlaybackContract.ACTION_PREVIOUS,
                    REQUEST_PREVIOUS,
                ),
            )
            .addAction(
                notificationAction(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    if (isPlaying) R.string.pause else R.string.play,
                    LocalPlaybackContract.ACTION_TOGGLE,
                    REQUEST_TOGGLE,
                ),
            )
            .addAction(
                notificationAction(
                    R.drawable.ic_next,
                    R.string.next,
                    LocalPlaybackContract.ACTION_NEXT,
                    REQUEST_NEXT,
                ),
            )
        if (track != null) {
            builder.setContentText(track.artist)
            builder.setSubText(
                getString(
                    if (isPlaying) R.string.local_music_playing else R.string.local_music_paused,
                ),
            )
            LocalArtwork.cached(track.id)?.let(builder::setLargeIcon)
        }
        return builder.build()
    }

    private fun notificationAction(
        iconRes: Int,
        titleRes: Int,
        action: String,
        requestCode: Int,
    ): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, iconRes),
            getString(titleRes),
            servicePendingIntent(action, requestCode),
        ).build()

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, LocalPlaybackService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // On API 26+ the actions start a foreground service, and getService can race the
        // background-start restrictions when the app is not in the foreground.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.local_music_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun stopPlayback() {
        loadGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        queue = null
        playing = false
        prepared = false
        ended = false
        pausedForFocus = false
        playbackError = false
        abandonAudioFocus()
        mediaSession.setMetadata(null)
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_NONE, 0L, 0f)
                .build(),
        )
        LocalNowPlayingState.update(null, false)
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }
}
