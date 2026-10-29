package io.github.xhzair.refresholdphone

import io.github.xhzair.refresholdphone.apps.MediaApp
import io.github.xhzair.refresholdphone.apps.MediaAppCatalog
import io.github.xhzair.refresholdphone.media.ExternalPlaybackBridge
import io.github.xhzair.refresholdphone.media.PlaybackSnapshot
import io.github.xhzair.refresholdphone.system.SystemStatus
import io.github.xhzair.refresholdphone.system.SystemStatusSource

/** Deterministic catalog used by instrumentation tests; no PackageManager involved. */
class FakeCatalog(private val apps: List<MediaApp>) : MediaAppCatalog {
    override fun load(selectedPackages: Set<String>): List<MediaApp> = apps
}

/**
 * Records lifecycle calls and lets tests push playback/access state synchronously.
 * The bridge contract allows callbacks on any thread; tests always invoke from main.
 */
class FakePlaybackBridge : ExternalPlaybackBridge {
    private var playbackCallback: ((PlaybackSnapshot?) -> Unit)? = null
    private var accessCallback: ((Boolean) -> Unit)? = null

    var started = 0
    var stopped = 0
    var playPauseCalls = 0
    var skipPreviousCalls = 0
    var skipNextCalls = 0
    var accessGranted = false

    override fun start(
        onPlaybackChanged: (PlaybackSnapshot?) -> Unit,
        onAccessChanged: (Boolean) -> Unit,
    ) {
        started++
        playbackCallback = onPlaybackChanged
        accessCallback = onAccessChanged
        onAccessChanged(accessGranted)
    }

    override fun stop() {
        stopped++
        playbackCallback = null
        accessCallback = null
    }

    override fun playPause() {
        playPauseCalls++
    }

    override fun skipPrevious() {
        skipPreviousCalls++
    }

    override fun skipNext() {
        skipNextCalls++
    }

    fun emitPlayback(value: PlaybackSnapshot?) {
        playbackCallback?.invoke(value)
    }

    fun emitAccess(granted: Boolean) {
        accessCallback?.invoke(granted)
    }
}

class FakeStatusSource : SystemStatusSource {
    var started = 0
    var stopped = 0

    override fun start(onStatus: (SystemStatus) -> Unit) {
        started++
        onStatus(SystemStatus(batteryPercent = 80, charging = true, networkLabel = "Wi‑Fi"))
    }

    override fun stop() {
        stopped++
    }
}
