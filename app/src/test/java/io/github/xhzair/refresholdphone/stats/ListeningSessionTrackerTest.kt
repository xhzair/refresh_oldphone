package io.github.xhzair.refresholdphone.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningSessionTrackerTest {
    private val written = mutableListOf<ListeningSession>()
    private val tracker = ListeningSessionTracker(
        store = { written += it },
        uidFactory = { "id-${written.size}" },
    )

    private fun observed(
        title: String = "Song",
        playing: Boolean = true,
        position: Long = 0,
        duration: Long = 60_000,
        packageName: String? = "com.tencent.qqmusic",
    ) = PlaybackObservation(
        sourceKind = if (packageName == null) ListeningSourceKind.LOCAL else ListeningSourceKind.EXTERNAL,
        packageName = packageName,
        title = title,
        artist = "Artist",
        album = "Album",
        durationMs = duration,
        positionMs = position,
        playing = playing,
    )

    @Test
    fun `accumulates played wall time and flushes on close`() {
        tracker.onObserved(observed(), 1_000)
        tracker.onObserved(observed(playing = true), 6_000)
        tracker.flush(11_000)
        assertEquals(10_000L, written.last().listenedMs)
        assertTrue(written.last().closed)
    }

    @Test
    fun `pause does not accumulate and resume continues same session`() {
        tracker.onObserved(observed(), 0)
        tracker.onObserved(observed(playing = false), 5_000)
        tracker.onObserved(observed(playing = false), 20_000)
        tracker.onObserved(observed(playing = true), 21_000)
        tracker.flush(26_000)
        assertEquals(10_000L, written.last().listenedMs)
        assertEquals(1, written.count { it.closed })
    }

    @Test
    fun `identity change closes old and opens new`() {
        tracker.onObserved(observed(title = "A"), 0)
        tracker.onObserved(observed(title = "B"), 4_000)
        tracker.flush(6_000)
        assertEquals(2, written.count { it.closed })
        assertEquals("A", written.first().title)
        assertEquals("B", written.last().title)
        assertEquals(4_000L, written.first().listenedMs)
        assertEquals(2_000L, written.last().listenedMs)
    }

    @Test
    fun `unknown metadata is ignored`() {
        tracker.onObserved(observed(title = ""), 0)
        tracker.flush(5_000)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `paused first observation does not open a session`() {
        tracker.onObserved(observed(playing = false), 0)
        tracker.flush(5_000)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `scoped external flush leaves an active local session open`() {
        tracker.onObserved(observed(packageName = null), 0)
        tracker.flush(1_000, ListeningSourceKind.EXTERNAL)
        assertTrue(written.isEmpty())
        tracker.flush(5_000)
        assertEquals(1, written.count { it.closed })
        assertEquals(ListeningSourceKind.LOCAL, written.single().sourceKind)
        assertEquals(5_000L, written.single().listenedMs)
    }

    @Test
    fun `scoped external flush closes an active external session`() {
        tracker.onObserved(observed(), 0)
        tracker.flush(1_000, ListeningSourceKind.EXTERNAL)
        assertEquals(1, written.count { it.closed })
        assertEquals(ListeningSourceKind.EXTERNAL, written.single().sourceKind)
        assertEquals(1_000L, written.single().listenedMs)
        tracker.flush(5_000)
        assertEquals(1, written.count { it.closed })
    }

    @Test
    fun `paused external snapshot does not close a newly active local session`() {
        tracker.onObserved(observed(), 0)
        tracker.onObserved(observed(packageName = null), 1_000)
        tracker.onObserved(observed(playing = false), 2_000)
        tracker.flush(5_000)
        assertEquals(2, written.count { it.closed })
        assertEquals(ListeningSourceKind.LOCAL, written.last().sourceKind)
        assertTrue(written.last().closed)
        assertEquals(4_000L, written.last().listenedMs)
    }

    @Test
    fun `paused external identity change is ignored while local session is active`() {
        tracker.onObserved(observed(packageName = null), 0)
        tracker.onObserved(observed(playing = false), 2_000)
        tracker.flush(5_000)
        assertEquals(1, written.count { it.closed })
        assertEquals(ListeningSourceKind.LOCAL, written.single().sourceKind)
        assertEquals(5_000L, written.single().listenedMs)
    }
}
