package io.github.xhzair.refresholdphone.ui

import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.github.xhzair.refresholdphone.apps.MediaAppCatalog
import io.github.xhzair.refresholdphone.media.ExternalPlaybackBridge
import io.github.xhzair.refresholdphone.system.SystemStatusSource

class HomeViewModel(
    private val catalog: MediaAppCatalog,
    private val selectedPackages: () -> Set<String>,
    private val playbackBridge: ExternalPlaybackBridge,
    private val statusSource: SystemStatusSource,
) : ViewModel() {
    private val lock = Any()
    private var currentState = HomeState()
    private var activeGeneration: Any? = null
    private val mutableState = MutableLiveData(currentState)
    val state: LiveData<HomeState> = mutableState

    /** Lifecycle calls belong to the UI thread; source callbacks may arrive on any thread. */
    @MainThread
    fun start() {
        val generation = synchronized(lock) {
            if (activeGeneration != null) return
            Any().also { activeGeneration = it }
        }
        // Never hold the reducer lock across dependencies: start can callback immediately.
        val apps = catalog.load(selectedPackages().toSet()).toList()
        reduce(generation) { it.copy(apps = apps) }
        if (!isActive(generation)) return
        playbackBridge.start(
            { playback -> reduce(generation) { it.copy(playback = playback) } },
            { access -> reduce(generation) { it.copy(notificationAccessGranted = access) } },
        )
        if (!isActive(generation)) return
        statusSource.start { status -> reduce(generation) { it.copy(systemStatus = status) } }
    }

    @MainThread
    fun stop() {
        synchronized(lock) {
            if (activeGeneration == null) return
            // Invalidate first, including callbacks emitted synchronously during teardown.
            activeGeneration = null
        }
        try { playbackBridge.stop() } finally { statusSource.stop() }
    }

    private fun isActive(generation: Any): Boolean = synchronized(lock) {
        activeGeneration === generation
    }

    private fun reduce(generation: Any, update: (HomeState) -> HomeState) {
        synchronized(lock) {
            if (activeGeneration !== generation) return
            currentState = update(currentState)
            // Queue in reducer order. Coalescing cannot lose a field because every queued
            // value includes all accepted updates, independent of LiveData's delivered value.
            mutableState.postValue(currentState)
        }
    }

    fun playPause() = playbackBridge.playPause()
    fun skipPrevious() = playbackBridge.skipPrevious()
    fun skipNext() = playbackBridge.skipNext()

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
