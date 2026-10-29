package io.github.xhzair.refresholdphone.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionCandidatesTest {
    private class Session(val token: String, var playing: Boolean = false)

    private class Harness {
        val attached = mutableListOf<String>()
        val detached = mutableListOf<String>()
        val callbacks = mutableMapOf<String, (CandidateSessionEvent) -> Unit>()
        var changes = 0
        var selected: String? = null
        val candidates = PlaybackSessionCandidates<String, Session>(
            tokenOf = { it.token },
            describe = { PlaybackSessionCandidate("com.tencent.qqmusic", it.playing, null) },
            observe = { session, callback ->
                attached += session.token
                callbacks[session.token] = callback
                val detach: () -> Unit = { detached += session.token }
                detach
            },
            onChanged = { _, _ -> changed() },
        )
        private fun changed() {
            changes++
            selected = candidates.selected()?.token
        }
        fun sync(sessions: List<Session>) {
            candidates.sync(sessions)
            selected = candidates.selected()?.token
        }
    }

    @Test fun `B becoming playing reselects B without a membership update`() {
        val harness = Harness()
        val a = Session("A")
        val b = Session("B")
        harness.sync(listOf(a, b))
        assertEquals("A", harness.selected)
        assertEquals(listOf("A", "B"), harness.attached)
        b.playing = true
        harness.callbacks.getValue("B")(CandidateSessionEvent.PLAYBACK)
        assertEquals("B", harness.selected)
        assertEquals(1, harness.changes)
    }

    @Test fun `synchronization detaches removed tokens and never double registers retained tokens`() {
        val harness = Harness()
        val a = Session("A")
        val b = Session("B")
        harness.sync(listOf(a, b))
        harness.sync(listOf(b, Session("A"), b))
        assertEquals(listOf("A", "B"), harness.attached)
        assertEquals("B", harness.selected)
        harness.sync(listOf(b))
        assertEquals(listOf("A"), harness.detached)
        assertEquals(listOf(b), harness.candidates.values())
    }

    @Test fun `stop or disconnect clearing detaches all and rejects queued callbacks`() {
        val harness = Harness()
        harness.sync(listOf(Session("A"), Session("B")))
        val queued = harness.callbacks.getValue("B")
        harness.candidates.clear()
        harness.candidates.clear()
        assertEquals(listOf("A", "B"), harness.detached)
        assertTrue(harness.candidates.values().isEmpty())
        assertNull(harness.candidates.selected())
        queued(CandidateSessionEvent.PLAYBACK)
        assertEquals(0, harness.changes)
    }

    @Test fun `destroyed sessions detach before reselection and old registration cannot affect replacement`() {
        val harness = Harness()
        val a = Session("A")
        harness.sync(listOf(a, Session("B")))
        val old = harness.callbacks.getValue("A")
        old(CandidateSessionEvent.DESTROYED)
        assertEquals(listOf("A"), harness.detached)
        assertEquals("B", harness.selected)
        harness.sync(listOf(a))
        val count = harness.changes
        old(CandidateSessionEvent.DESTROYED)
        assertEquals(count, harness.changes)
        assertEquals("A", harness.candidates.selected()?.token)
    }
}
