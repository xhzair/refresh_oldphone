package io.github.xhzair.refresholdphone.ui

import io.github.xhzair.refresholdphone.apps.MediaApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSourceSelectionTest {
    private val netease = MediaApp("com.netease.cloudmusic", "网易云音乐", installed = true, official = true)
    private val qqMusic = MediaApp("com.tencent.qqmusic", "QQ音乐", installed = false, official = true)
    private val vlx = MediaApp("org.videolan.vlc", "VLC", installed = true, official = false)

    @Test
    fun `available sources keep catalog order and drop missing apps`() {
        assertEquals(
            listOf(netease, vlx),
            MediaSourceSelection.availableSources(listOf(netease, qqMusic, vlx)),
        )
    }

    @Test
    fun `current source prefers the last launched installed app`() {
        assertEquals(
            vlx,
            MediaSourceSelection.currentSource(listOf(netease, qqMusic, vlx), vlx.packageName),
        )
    }

    @Test
    fun `current source falls back to the first installed official app`() {
        assertEquals(
            netease,
            MediaSourceSelection.currentSource(listOf(netease, qqMusic, vlx), qqMusic.packageName),
        )
    }

    @Test
    fun `current source is null when no media app is installed`() {
        assertNull(MediaSourceSelection.currentSource(listOf(qqMusic), null))
    }
}
