package io.github.xhzair.refresholdphone.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlaybackSeekTest {
    @Test
    fun `known duration clamps the target into range`() {
        assertEquals(10_000, clampSeekPosition(99_999L, 10_000))
        assertEquals(0, clampSeekPosition(-5L, 10_000))
        assertEquals(4_000, clampSeekPosition(4_000L, 10_000))
    }

    @Test
    fun `unknown duration accepts any non-negative position`() {
        assertEquals(7_500, clampSeekPosition(7_500L, -1))
        assertEquals(0, clampSeekPosition(-1L, -1))
    }

    @Test
    fun `position above int range does not overflow`() {
        assertEquals(Int.MAX_VALUE, clampSeekPosition(Long.MAX_VALUE, -1))
    }
}
