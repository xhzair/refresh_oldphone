package io.github.xhzair.refresholdphone.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPlaybackQueueTest {
    private val tracks = (1L..3L).map { LocalTrack(it, "Track $it", "A", "B", 1000) }

    @Test
    fun `starts at the requested index`() {
        assertEquals(2L, LocalPlaybackQueue(tracks, 1).current?.id)
    }

    @Test
    fun `next stops at the last track`() {
        val queue = LocalPlaybackQueue(tracks, 1)
        assertEquals(3L, queue.moveNext()?.id)
        assertNull(queue.moveNext())
        assertEquals(3L, queue.current?.id)
    }

    @Test
    fun `previous stops at the first track`() {
        val queue = LocalPlaybackQueue(tracks, 1)
        assertEquals(1L, queue.movePrevious()?.id)
        assertNull(queue.movePrevious())
        assertEquals(1L, queue.current?.id)
    }

    @Test
    fun `empty queue has no current track`() {
        val queue = LocalPlaybackQueue(emptyList())
        assertNull(queue.current)
        assertNull(queue.moveNext())
        assertNull(queue.movePrevious())
    }
}
