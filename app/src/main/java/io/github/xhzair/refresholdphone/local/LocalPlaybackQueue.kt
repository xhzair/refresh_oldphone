package io.github.xhzair.refresholdphone.local

/**
 * Sequential navigation over a local playback queue. Boundaries do not wrap: next on the last
 * track and previous on the first track return null and keep the current index.
 */
class LocalPlaybackQueue(
    private val tracks: List<LocalTrack>,
    startIndex: Int = 0,
) {
    var index: Int = if (tracks.isEmpty()) -1 else startIndex.coerceIn(0, tracks.lastIndex)
        private set

    val current: LocalTrack? get() = tracks.getOrNull(index)
    val hasNext: Boolean get() = index in 0 until tracks.lastIndex
    val hasPrevious: Boolean get() = index > 0
    val all: List<LocalTrack> get() = tracks

    fun moveNext(): LocalTrack? {
        if (!hasNext) return null
        index += 1
        return current
    }

    fun movePrevious(): LocalTrack? {
        if (!hasPrevious) return null
        index -= 1
        return current
    }
}
