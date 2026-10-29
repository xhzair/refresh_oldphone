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
    private class Generation {
        var playbackAttempted = false
        var statusAttempted = false
    }

    private var activeGeneration: Generation? = null
    // Retain ownership after invalidation so cleanup can detect a reentrant restart.
    private var latestGeneration: Generation? = null
    private val mutableState = MutableLiveData(currentState)
    val state: LiveData<HomeState> = mutableState

    /** Lifecycle calls belong to the UI thread; source callbacks may arrive on any thread. */
    @MainThread
    fun start() {
        val generation = synchronized(lock) {
            if (activeGeneration != null) return
            Generation().also { activeGeneration = it; latestGeneration = it }
        }
        // Never hold the reducer lock across dependencies: start can callback immediately.
        try {
            val apps = catalog.load(selectedPackages().toSet()).toList()
            reduce(generation) { it.copy(apps = apps) }
            if (!isActive(generation)) return
            generation.playbackAttempted = true
            playbackBridge.start(
                { playback -> reduce(generation) { it.copy(playback = playback) } },
                { access -> reduce(generation) { it.copy(notificationAccessGranted = access) } },
            )
            if (!isActive(generation)) return
            generation.statusAttempted = true
            statusSource.start { status -> reduce(generation) { it.copy(systemStatus = status) } }
        } catch (failure: Exception) {
            // A synchronous callback may already have stopped/replaced this lifecycle.
            if (invalidate(generation)) stopSources(generation, failure)
            throw failure
        }
    }

    @MainThread
    fun stop() {
        val generation = synchronized(lock) { activeGeneration } ?: return
        if (invalidate(generation)) stopSources(generation)?.let { throw it }
    }

    private fun invalidate(generation: Generation): Boolean = synchronized(lock) {
        if (activeGeneration !== generation) return false
        // Fence retained callbacks before any external teardown calls.
        activeGeneration = null
        true
    }

    private fun stopSources(generation: Generation, startupFailure: Exception? = null): Exception? {
        var failure = startupFailure
        val sources = listOf(
            generation.playbackAttempted to playbackBridge::stop,
            generation.statusAttempted to statusSource::stop,
        )
        for ((attempted, stopSource) in sources) {
            // Cleanup itself can synchronously start a new lifecycle using these adapters.
            if (!attempted || synchronized(lock) { latestGeneration !== generation }) continue
            try { stopSource() } catch (cleanupFailure: Exception) {
                if (failure == null) failure = cleanupFailure
                else if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
            }
        }
        return failure
    }

    private fun isActive(generation: Generation): Boolean = synchronized(lock) {
        activeGeneration === generation
    }

    private fun reduce(generation: Generation, update: (HomeState) -> HomeState) {
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
