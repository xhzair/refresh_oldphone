package io.github.xhzair.refresholdphone.ui

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelStore
import io.github.xhzair.refresholdphone.apps.MediaApp
import io.github.xhzair.refresholdphone.apps.MediaAppCatalog
import io.github.xhzair.refresholdphone.media.ExternalPlaybackBridge
import io.github.xhzair.refresholdphone.media.PlaybackControls
import io.github.xhzair.refresholdphone.media.PlaybackSnapshot
import io.github.xhzair.refresholdphone.system.SystemStatus
import io.github.xhzair.refresholdphone.system.SystemStatusSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule val instantExecutor = InstantTaskExecutorRule()

    private val apps = listOf(MediaApp("music", "Music", true, false))
    private val playback = PlaybackSnapshot("music", "Music", "Track", "Artist", 100, 20,
        true, PlaybackControls(true, true, true))
    private val status = SystemStatus(72, true, "Wi-Fi")
    private val catalog = FakeCatalog(apps)
    private val bridge = FakeBridge()
    private val source = FakeStatusSource()
    private var selected = setOf("music")
    private val model = HomeViewModel(catalog, { selected }, bridge, source)

    @Test fun startLoadsCurrentSelectionAndStartsEachSourceOnce() = observing {
        model.start()
        model.start()
        assertEquals(listOf(setOf("music")), catalog.selections)
        assertEquals(1, bridge.starts)
        assertEquals(1, source.starts)
        assertEquals(apps, model.state.value!!.apps)
        model.stop()
        selected = setOf("podcast")
        model.start()
        assertEquals(listOf(setOf("music"), setOf("podcast")), catalog.selections)
    }

    @Test fun immediateStartCallbacksPreserveLoadedApps() = observing {
        bridge.onStart = { playbackCallback, accessCallback ->
            playbackCallback(playback)
            accessCallback(true)
        }
        source.onStart = { it(status) }
        model.start()
        assertEquals(HomeState(apps, playback, status, true), model.state.value)
    }

    @Test fun statusChangesOnlyStatus() = observing {
        model.start()
        bridge.playbackCallbacks.last()(playback)
        bridge.accessCallbacks.last()(true)
        source.callbacks.last()(status)
        assertEquals(HomeState(apps, playback, status, true), model.state.value)
    }

    @Test fun concurrentCallbacksPreserveEveryField() = observing {
        model.start()
        val ready = CountDownLatch(3)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val callbacks = listOf<() -> Unit>(
                { source.callbacks.last()(status) },
                { bridge.playbackCallbacks.last()(playback) },
                { bridge.accessCallbacks.last()(true) },
            )
            val futures = callbacks.map { callback -> pool.submit {
                ready.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                repeat(100) { callback() }
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            release.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(HomeState(apps, playback, status, true), model.state.value)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun queuedLiveDataDeliveryCoalescesCompleteState() {
        val queued = ArrayDeque<Runnable>()
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) { queued.addLast(runnable) }
            override fun isMainThread() = true
        })
        try {
            observing {
                model.start()
                source.callbacks.last()(status)
                bridge.playbackCallbacks.last()(playback)
                bridge.accessCallbacks.last()(true)
                assertEquals(HomeState(), model.state.value)
                assertEquals(1, queued.size)
                while (queued.isNotEmpty()) queued.removeFirst().run()
                assertEquals(HomeState(apps, playback, status, true), model.state.value)
            }
        } finally {
            ArchTaskExecutor.getInstance().setDelegate(null)
        }
    }

    @Test fun repeatedStopCleansEachSourceOnceAndIgnoresTeardownCallbacks() = observing {
        model.stop()
        assertEquals(0, bridge.stops)
        model.start()
        val before = model.state.value
        bridge.onStop = {
            bridge.playbackCallbacks.last()(playback)
            bridge.accessCallbacks.last()(true)
        }
        source.onStop = { source.callbacks.last()(status) }
        model.stop()
        model.stop()
        assertEquals(1, bridge.stops)
        assertEquals(1, source.stops)
        assertEquals(before, model.state.value)
    }

    @Test fun retainedCallbacksCannotMutateStoppedOrRestartedState() = observing {
        model.start()
        val oldPlayback = bridge.playbackCallbacks.last()
        val oldAccess = bridge.accessCallbacks.last()
        val oldStatus = source.callbacks.last()
        model.stop()
        val stopped = model.state.value
        oldPlayback(playback)
        oldAccess(true)
        oldStatus(status)
        assertEquals(stopped, model.state.value)
        model.start()
        oldPlayback(playback)
        oldAccess(true)
        oldStatus(status)
        assertEquals(stopped, model.state.value)
        source.callbacks.last()(status)
        bridge.playbackCallbacks.last()(playback)
        bridge.accessCallbacks.last()(true)
        assertEquals(HomeState(apps, playback, status, true), model.state.value)
        assertEquals(2, bridge.starts)
        assertEquals(2, source.starts)
    }

    @Test fun clearingViewModelStoreStopsActiveSources() = observing {
        val store = ViewModelStore()
        store.put("home", model)
        model.start()
        store.clear()
        model.stop()
        assertEquals(1, bridge.stops)
        assertEquals(1, source.stops)
    }

    @Test fun transportActionsDelegateOnce() {
        model.playPause()
        model.skipPrevious()
        model.skipNext()
        assertEquals(listOf("playPause", "previous", "next"), bridge.commands)
    }

    private fun observing(block: () -> Unit) {
        val observer = Observer<HomeState> { }
        model.state.observeForever(observer)
        try { block() } finally {
            model.state.removeObserver(observer)
            model.stop()
        }
    }

    private class FakeCatalog(val apps: List<MediaApp>) : MediaAppCatalog {
        val selections = mutableListOf<Set<String>>()
        override fun load(selectedPackages: Set<String>): List<MediaApp> {
            selections += selectedPackages.toSet()
            return apps
        }
    }

    private class FakeBridge : ExternalPlaybackBridge {
        var starts = 0
        var stops = 0
        val playbackCallbacks = mutableListOf<(PlaybackSnapshot?) -> Unit>()
        val accessCallbacks = mutableListOf<(Boolean) -> Unit>()
        val commands = mutableListOf<String>()
        var onStart: (((PlaybackSnapshot?) -> Unit, (Boolean) -> Unit) -> Unit)? = null
        var onStop: (() -> Unit)? = null
        override fun start(onPlaybackChanged: (PlaybackSnapshot?) -> Unit, onAccessChanged: (Boolean) -> Unit) {
            starts++
            playbackCallbacks += onPlaybackChanged
            accessCallbacks += onAccessChanged
            onStart?.invoke(onPlaybackChanged, onAccessChanged)
        }
        override fun stop() { stops++; onStop?.invoke() }
        override fun playPause() { commands += "playPause" }
        override fun skipPrevious() { commands += "previous" }
        override fun skipNext() { commands += "next" }
    }

    private class FakeStatusSource : SystemStatusSource {
        var starts = 0
        var stops = 0
        val callbacks = mutableListOf<(SystemStatus) -> Unit>()
        var onStart: (((SystemStatus) -> Unit) -> Unit)? = null
        var onStop: (() -> Unit)? = null
        override fun start(onStatus: (SystemStatus) -> Unit) {
            starts++
            callbacks += onStatus
            onStart?.invoke(onStatus)
        }
        override fun stop() { stops++; onStop?.invoke() }
    }
}
