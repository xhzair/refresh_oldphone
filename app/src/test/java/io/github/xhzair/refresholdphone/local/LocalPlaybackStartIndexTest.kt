package io.github.xhzair.refresholdphone.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlaybackStartIndexTest {
    private val tracks = (1L..3L).map { LocalTrack(it, "T$it", "A", "B", 1000) }

    @Test
    fun `resolves requested id to its actual index`() {
        assertEquals(1, resolveStartIndex(2L, tracks))
        assertEquals(2, resolveStartIndex(3L, tracks))
    }

    @Test
    fun `missing id returns minus one instead of playing a shifted track`() {
        assertEquals(-1, resolveStartIndex(99L, tracks))
        assertEquals(-1, resolveStartIndex(null, tracks))
    }
}
