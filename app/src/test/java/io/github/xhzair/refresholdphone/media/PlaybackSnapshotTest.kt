package io.github.xhzair.refresholdphone.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackSnapshotTest {
    @Test fun `availability follows notification access regardless of service binding`() {
        var settingsEnabled = true
        val policy = PlaybackAvailabilityPolicy { settingsEnabled }
        assertTrue(policy.available())
        settingsEnabled = false
        assertFalse(policy.available())
    }

    @Test fun `enabled listener without a bound service still grants access`() {
        // MediaSessionManager checks the enabled listener component; a bound service is not required.
        val policy = PlaybackAvailabilityPolicy { true }
        assertTrue(policy.available())
    }

    @Test fun `exclusive policy pauses other playing media sources only`() {
        val media = setOf("com.netease.cloudmusic", "com.tencent.qqmusic", "app.podcast.cosmos")
        assertTrue(shouldPauseOtherSession("com.tencent.qqmusic", "com.netease.cloudmusic", true, media))
        assertFalse(shouldPauseOtherSession("com.tencent.qqmusic", "com.netease.cloudmusic", false, media))
        assertFalse(shouldPauseOtherSession("com.tencent.qqmusic", "com.netease.cloudmusic", true, emptySet()))
        assertFalse(shouldPauseOtherSession("com.tencent.qqmusic", "com.tencent.qqmusic", true, media))
        assertFalse(shouldPauseOtherSession(null, "com.netease.cloudmusic", true, media))
        assertFalse(shouldPauseOtherSession("com.tencent.qqmusic", "com.spotify.music", true, media))
    }

    private fun map(title: String? = null, artist: String? = null,
                    label: String = " Provider ", duration: Long = 0,
                    position: Long = 0, playing: Boolean = false, actions: Long = 0) =
        PlaybackSnapshotMapper.map("example.player", label, title, artist,
            duration, position, playing, actions)

    @Test fun `blank metadata uses trimmed provider and empty artist`() {
        val snapshot = map(title = "  ", artist = " \n ")
        assertEquals("Provider", snapshot.title)
        assertEquals("Provider", snapshot.providerLabel)
        assertEquals("", snapshot.artist)
        assertEquals("", map().artist)
        assertEquals("example.player", map(label = " ").title)
    }

    @Test fun `metadata whitespace is trimmed`() {
        val snapshot = map(title = " Song ", artist = " Artist ")
        assertEquals("Song", snapshot.title)
        assertEquals("Artist", snapshot.artist)
    }

    @Test fun `negative timing becomes zero`() {
        val snapshot = map(duration = -1, position = -100)
        assertEquals(0L, snapshot.durationMs)
        assertEquals(0L, snapshot.positionMs)
    }

    @Test fun `known duration clamps position but unknown duration retains it`() {
        assertEquals(100L, map(duration = 100, position = 150).positionMs)
        assertEquals(150L, map(duration = 0, position = 150).positionMs)
        assertEquals(150L, map(duration = -1, position = 150).positionMs)
    }

    @Test fun `playing is passed through`() {
        assertTrue(map(playing = true).playing)
        assertFalse(map(playing = false).playing)
    }

    @Test fun `controls derive from current state and platform action bits`() {
        // Stable PlaybackState action values; no Android framework objects needed.
        for (action in listOf(2L, 4L, 512L)) {
            assertEquals(PlaybackControls(true, false, false), map(playing = action == 2L, actions = action).controls)
        }
        assertEquals(PlaybackControls(false, true, false), map(actions = 16).controls)
        assertEquals(PlaybackControls(false, false, true), map(actions = 32).controls)
        assertEquals(PlaybackControls(true, true, true), map(playing = true, actions = 2 or 16 or 32).controls)
        assertEquals(PlaybackControls(), map(actions = 1 or 8 or 256).controls)
        assertEquals(PlaybackControls(), map(actions = 0).controls)
    }

    @Test fun `playing snapshot disables play-only capability and accepts pause or toggle`() {
        assertFalse(map(playing = true, actions = 4).controls.canPlayPause)
        assertTrue(map(playing = true, actions = 2).controls.canPlayPause)
        assertTrue(map(playing = true, actions = 512).controls.canPlayPause)
    }

    @Test fun `paused snapshot disables pause-only capability and accepts play or toggle`() {
        assertFalse(map(playing = false, actions = 2).controls.canPlayPause)
        assertTrue(map(playing = false, actions = 4).controls.canPlayPause)
        assertTrue(map(playing = false, actions = 512).controls.canPlayPause)
    }

    @Test fun `selection prefers playing then recent supported then manager order`() {
        val sessions = listOf(
            PlaybackSessionCandidate("other", false, null),
            PlaybackSessionCandidate("com.tencent.qqmusic", false, 2),
            PlaybackSessionCandidate("com.netease.cloudmusic", false, 5),
        )
        assertEquals(2, selectPlaybackSession(sessions))
        assertEquals(0, selectPlaybackSession(sessions.mapIndexed { index, item ->
            item.copy(playing = index == 0)
        }))
        assertEquals(1, selectPlaybackSession(sessions.map { it.copy(lastActive = null) }))
        assertEquals(0, selectPlaybackSession(listOf(sessions[0])))
        assertNull(selectPlaybackSession(emptyList()))
    }

    @Test fun `play pause never invokes the opposite unsupported action`() {
        assertEquals(PlaybackCommand.PAUSE, playPauseCommand(true, 2))
        assertEquals(PlaybackCommand.PLAY, playPauseCommand(false, 4))
        assertEquals(PlaybackCommand.PAUSE, playPauseCommand(true, 512))
        assertEquals(PlaybackCommand.PLAY, playPauseCommand(false, 512))
        assertNull(playPauseCommand(true, 4))
        assertNull(playPauseCommand(false, 2))
        assertNull(playPauseCommand(true, 0))
    }

    @Test fun `connection registry tracks state and supports removing callbacks during notification`() {
        val registry = ListenerConnectionRegistry()
        val observed = mutableListOf<Boolean>()
        lateinit var listener: (Boolean) -> Unit
        listener = { connected ->
            observed += connected
            assertEquals(connected, registry.connected)
            registry.remove(listener)
        }
        assertFalse(registry.connected)
        registry.add(listener)
        registry.update(true)
        registry.update(false)
        assertEquals(listOf(true), observed)
        assertFalse(registry.connected)
    }
}
