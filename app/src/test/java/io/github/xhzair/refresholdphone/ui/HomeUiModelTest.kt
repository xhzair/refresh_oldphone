package io.github.xhzair.refresholdphone.ui

import io.github.xhzair.refresholdphone.apps.MediaApp
import io.github.xhzair.refresholdphone.media.PlaybackSnapshot
import io.github.xhzair.refresholdphone.media.PlaybackControls
import io.github.xhzair.refresholdphone.system.SystemStatus
import org.junit.Assert.*
import org.junit.Test

class HomeUiModelTest {
    private val words = HomeWords("安静待播", "选择下方应用开始聆听", "最近使用：%s", "%s · 电量 %d%%%s", " · 充电中", "已安装", "未安装")
    @Test fun emptyStateAndLocalLastApp() {
        val state = HomeState(apps = listOf(MediaApp("p", "音乐", true, true)))
        val ui = HomeUiModelMapper.map(state, words, "p", true)
        assertEquals("安静待播", ui.title)
        assertEquals("最近使用：音乐", ui.artist)
        assertFalse(ui.showArtwork)
        assertFalse(ui.showProgress)
        assertEquals(0, ui.progress)
        assertFalse(ui.showControls)
        assertFalse(ui.canPlayPause)
        assertTrue(ui.showPermission)
        assertEquals("选择下方应用开始聆听", HomeUiModelMapper.map(state, words, "gone", true).artist)
        assertFalse(HomeUiModelMapper.map(state.copy(notificationAccessGranted = true), words, null, false).showPermission)
    }
    @Test fun activeCapabilitiesAndHugeProgressAreSafe() {
        val playback = PlaybackSnapshot("p", "来源", "歌曲", "作者", Long.MAX_VALUE, Long.MAX_VALUE / 2, true, PlaybackControls(true, false, true))
        val ui = HomeUiModelMapper.map(HomeState(playback = playback, notificationAccessGranted = true), words, null, false)
        assertEquals("歌曲", ui.title)
        assertEquals("作者 · 来源", ui.artist)
        assertEquals(500, ui.progress)
        assertEquals(1000, ui.progressMax)
        assertTrue(ui.showProgress)
        assertTrue(ui.showControls)
        assertTrue(ui.playing)
        assertTrue(ui.canPlayPause)
        assertFalse(ui.canPrevious)
        assertTrue(ui.canNext)
        assertFalse(ui.showArtwork)
        val over = playback.copy(durationMs = 5, positionMs = Long.MAX_VALUE, playing = false)
        assertEquals(1000, HomeUiModelMapper.map(HomeState(playback = over), words, null, false).progress)
        assertFalse(HomeUiModelMapper.map(HomeState(playback = over), words, null, false).playing)
    }
    @Test fun statusAndMissingAppAreExplicit() {
        val ui = HomeUiModelMapper.map(HomeState(systemStatus = SystemStatus(42, true, "Wi-Fi")), words, null, false)
        assertEquals("Wi-Fi · 电量 42% · 充电中", ui.status)
        assertEquals("未安装", HomeUiModelMapper.appStatus(MediaApp("p", "音乐", false, true), words))
    }
}
