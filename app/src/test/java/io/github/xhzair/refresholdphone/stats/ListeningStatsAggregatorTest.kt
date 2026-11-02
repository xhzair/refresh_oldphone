package io.github.xhzair.refresholdphone.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ListeningStatsAggregatorTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun time(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    private fun session(
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        startedAt: Long,
        listened: Long,
        source: ListeningSourceKind = ListeningSourceKind.LOCAL,
        packageName: String? = null,
    ) = ListeningSession(
        uid = title + startedAt + source.name + packageName,
        sourceKind = source,
        packageName = packageName,
        title = title,
        artist = artist,
        album = album,
        startedAtMs = startedAt,
        updatedAtMs = startedAt + listened,
        listenedMs = listened,
        durationMs = 60_000,
        lastPositionMs = 0,
        closed = true,
    )

    @Test
    fun `filters by month and computes totals and rankings`() {
        val sessions = listOf(
            session("A", artist = "X", startedAt = time(2025, 11, 3), listened = 60_000),
            session("A", artist = "X", startedAt = time(2025, 11, 3), listened = 30_000),
            session("B", artist = "Y", startedAt = time(2025, 11, 4), listened = 90_000),
            session("C", artist = "Z", startedAt = time(2025, 10, 31), listened = 10_000),
        )
        val summary = ListeningStatsAggregator.summarize(sessions, 2025, 10, zone)
        assertEquals("2025年11月", summary.monthLabel)
        assertEquals(180_000L, summary.totalListenedMs)
        assertEquals(3, summary.sessionCount)
        assertEquals("A", summary.topTracks.first().label)
        assertEquals(90_000L, summary.topTracks.first().listenedMs)
        assertEquals("B", summary.topTracks[1].label)
        assertEquals("X", summary.topArtists.first().label)
        assertEquals(1, summary.sources.size)
        assertEquals("本地音乐", summary.sources.first().label)
    }

    @Test
    fun `computes active days hours and longest session`() {
        val summary = ListeningStatsAggregator.summarize(
            listOf(
                session("A", startedAt = time(2025, 11, 3, hour = 9), listened = 30_000),
                session("B", startedAt = time(2025, 11, 4, hour = 15), listened = 50_000),
            ),
            2025, 10, zone,
        )
        assertEquals(2, summary.activeDays)
        assertEquals(30_000L, summary.hours[9])
        assertEquals(50_000L, summary.hours[15])
        assertEquals(50_000L, summary.longestSessionMs)
    }

    @Test
    fun `month filtering respects start boundary`() {
        val summary = ListeningStatsAggregator.summarize(
            listOf(
                session("Before", startedAt = time(2025, 10, 31, hour = 23), listened = 1_000),
                session("Start", startedAt = time(2025, 11, 1, hour = 0), listened = 2_000),
            ),
            2025, 10, zone,
        )
        assertEquals(2_000L, summary.totalListenedMs)
        assertEquals(1, summary.sessionCount)
    }

    @Test
    fun `empty data is a zero summary`() {
        val summary = ListeningStatsAggregator.summarize(emptyList(), 2025, 10, zone)
        assertEquals(0L, summary.totalListenedMs)
        assertEquals(0, summary.sessionCount)
        assertEquals(0, summary.activeDays)
        assertEquals(0, summary.sources.size)
    }
}
