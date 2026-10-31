package io.github.xhzair.refresholdphone.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaybackSkipPolicyTest {
    @Test
    fun `skip while playing keeps playing`() {
        assertTrue(shouldPlayAfterSkip(playing = true, autoPlayWhenPrepared = false))
    }

    @Test
    fun `skip while the next track is preparing keeps playing`() {
        assertTrue(shouldPlayAfterSkip(playing = false, autoPlayWhenPrepared = true))
    }

    @Test
    fun `skip after a user pause stays paused`() {
        assertFalse(shouldPlayAfterSkip(playing = false, autoPlayWhenPrepared = false))
    }

    @Test
    fun `skip with both intents set keeps playing`() {
        assertTrue(shouldPlayAfterSkip(playing = true, autoPlayWhenPrepared = true))
    }
}
