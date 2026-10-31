package io.github.xhzair.refresholdphone.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLibraryTest {
    private fun track(id: Long, title: String, artist: String = "A", duration: Long = 1000) =
        LocalTrack(id, title, artist, "Album", duration)

    @Test
    fun `drops zero duration and blank titles`() {
        val cleaned = LocalLibrary.sanitize(
            listOf(
                track(1, "Keep"),
                track(2, ""),
                track(3, "Zero", duration = 0),
                track(4, "  "),
            ),
        )
        assertEquals(listOf(1L), cleaned.map { it.id })
    }

    @Test
    fun `deduplicates by id and sorts title then artist`() {
        val cleaned = LocalLibrary.sanitize(
            listOf(
                track(1, "beta", "Z"),
                track(2, "Alpha", "B"),
                track(2, "Alpha duplicate", "A"),
                track(3, "beta", "A"),
            ),
        )
        assertEquals(listOf(2L, 3L, 1L), cleaned.map { it.id })
    }

    @Test
    fun `empty library stays empty`() {
        assertEquals(emptyList<LocalTrack>(), LocalLibrary.sanitize(emptyList()))
    }

    @Test
    fun `drops negative durations`() {
        val cleaned = LocalLibrary.sanitize(
            listOf(
                track(1, "Keep"),
                track(2, "Negative", duration = -1),
            ),
        )
        assertEquals(listOf(1L), cleaned.map { it.id })
    }

    @Test
    fun `breaks equal title and artist ties by id ascending`() {
        val cleaned = LocalLibrary.sanitize(
            listOf(
                track(9, "Same", "Same"),
                track(3, "Same", "Same"),
            ),
        )
        assertEquals(listOf(3L, 9L), cleaned.map { it.id })
    }
}
