package io.github.xhzair.refresholdphone.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaybackSkipPolicyTest {
    @Test
    fun `skip while playing keeps playing`() {
        assertTrue(
            shouldPlayAfterSkip(
                playing = true,
                autoPlayWhenPrepared = false,
                playIntent = false,
            ),
        )
    }

    @Test
    fun `skip while the next track is preparing keeps playing`() {
        assertTrue(
            shouldPlayAfterSkip(
                playing = false,
                autoPlayWhenPrepared = true,
                playIntent = false,
            ),
        )
    }

    @Test
    fun `skip after a user pause stays paused`() {
        assertFalse(
            shouldPlayAfterSkip(
                playing = false,
                autoPlayWhenPrepared = false,
                playIntent = false,
            ),
        )
    }

    @Test
    fun `skip with playing and preparing both set keeps playing`() {
        assertTrue(
            shouldPlayAfterSkip(
                playing = true,
                autoPlayWhenPrepared = true,
                playIntent = false,
            ),
        )
    }

    @Test
    fun `skip after a playback error resumes when the user intended to play`() {
        // handlePlaybackError() clears playing and autoPlayWhenPrepared but keeps playIntent.
        assertTrue(
            shouldPlayAfterSkip(
                playing = false,
                autoPlayWhenPrepared = false,
                playIntent = true,
            ),
        )
    }

    @Test
    fun `skip after a user pause followed by an error stays paused`() {
        // pausePlayback() clears playIntent, then handlePlaybackError() leaves it false.
        assertFalse(
            shouldPlayAfterSkip(
                playing = false,
                autoPlayWhenPrepared = false,
                playIntent = false,
            ),
        )
    }

    @Test
    fun `skip with every intent set keeps playing`() {
        assertTrue(
            shouldPlayAfterSkip(
                playing = true,
                autoPlayWhenPrepared = true,
                playIntent = true,
            ),
        )
    }
}
