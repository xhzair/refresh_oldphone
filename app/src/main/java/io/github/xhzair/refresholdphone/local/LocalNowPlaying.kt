package io.github.xhzair.refresholdphone.local

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class LocalNowPlaying(
    val track: LocalTrack? = null,
    val playing: Boolean = false,
)

object LocalNowPlayingState {
    private val current = MutableLiveData(LocalNowPlaying())

    val nowPlaying: LiveData<LocalNowPlaying> get() = current

    fun update(track: LocalTrack?, playing: Boolean) {
        current.postValue(LocalNowPlaying(track, playing))
    }
}
