package io.github.xhzair.refresholdphone.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningStatsFormatTest {
    @Test
    fun `zero duration formats as zero minutes`() {
        assertEquals("0:00", formatListeningDuration(0L))
    }

    @Test
    fun `sub-minute duration formats as zero minutes and seconds`() {
        assertEquals("0:12", formatListeningDuration(12_340L))
        assertEquals("0:59", formatListeningDuration(59_999L))
    }

    @Test
    fun `minute duration formats as minutes and two digit seconds`() {
        assertEquals("12:34", formatListeningDuration(754_000L))
        assertEquals("1:00", formatListeningDuration(60_000L))
    }

    @Test
    fun `hour duration formats as hours minutes seconds`() {
        assertEquals("1:02:03", formatListeningDuration(3_723_000L))
        assertEquals("10:00:00", formatListeningDuration(36_000_000L))
    }

    @Test
    fun `negative duration clamps to zero`() {
        assertEquals("0:00", formatListeningDuration(-1L))
        assertEquals("0:00", formatListeningDuration(-60_000L))
    }

    @Test
    fun `rank includes detail only when present`() {
        assertEquals(
            "1. 晴天 · 周杰伦  12:34",
            formatListeningRank(1, RankedListeningItem("晴天", 754_000L, "周杰伦")),
        )
        assertEquals(
            "2. Intro  0:05",
            formatListeningRank(2, RankedListeningItem("Intro", 5_000L)),
        )
        assertEquals(
            "3. Outro  0:01",
            formatListeningRank(3, RankedListeningItem("Outro", 1_000L, "   ")),
        )
    }
}
