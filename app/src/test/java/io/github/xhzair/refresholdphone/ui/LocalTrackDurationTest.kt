package io.github.xhzair.refresholdphone.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTrackDurationTest {
    @Test
    fun `zero duration formats as zero minutes`() {
        assertEquals("0:00", formatTrackDuration(0L))
    }

    @Test
    fun `partial minute floors to whole seconds`() {
        assertEquals("0:59", formatTrackDuration(59_999L))
    }

    @Test
    fun `whole minute formats with two digit seconds`() {
        assertEquals("1:00", formatTrackDuration(60_000L))
    }

    @Test
    fun `one hour formats as hours minutes seconds`() {
        assertEquals("1:00:00", formatTrackDuration(3_600_000L))
    }

    @Test
    fun `negative duration clamps to zero`() {
        assertEquals("0:00", formatTrackDuration(-1L))
    }
}
