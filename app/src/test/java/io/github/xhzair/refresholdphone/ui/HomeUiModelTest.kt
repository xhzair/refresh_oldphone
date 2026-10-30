package io.github.xhzair.refresholdphone.ui

import io.github.xhzair.refresholdphone.apps.MediaApp
import io.github.xhzair.refresholdphone.media.PlaybackSnapshot
import io.github.xhzair.refresholdphone.media.PlaybackControls
import io.github.xhzair.refresholdphone.system.SystemStatus
import org.junit.Assert.*
import org.junit.Test

class HomeUiModelTest {
    private val words = HomeWords("安静待播", "点击下方音乐图标开始聆听", "最近使用：%s", "%s · 电量 %d%%%s", " · 充电中")

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
        assertEquals("点击下方音乐图标开始聆听", HomeUiModelMapper.map(state, words, "gone", true).artist)
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

    @Test fun playingProgressAdvancesWithElapsedTime() {
        val playback = PlaybackSnapshot(
            "p", "来源", "歌曲", "作者",
            durationMs = 120_000, positionMs = 10_000, playing = true,
            controls = PlaybackControls(true, false, true),
            positionUpdatedAtMs = 5_000L, playbackSpeed = 1f,
        )
        val state = HomeState(playback = playback, notificationAccessGranted = true)
        // 10 s reported + 20 s elapsed = 30 s of 120 s.
        assertEquals(250, HomeUiModelMapper.map(state, words, null, false, nowElapsedMs = 25_000L).progress)
    }

    @Test fun pausedProgressDoesNotDriftAndUnknownSampleTimeFallsBack() {
        val paused = PlaybackSnapshot(
            "p", "来源", "歌曲", "作者", 120_000, 10_000, false, PlaybackControls(),
            positionUpdatedAtMs = 5_000L,
        )
        assertEquals(83, HomeUiModelMapper.map(HomeState(playback = paused), words, null, false, 25_000L).progress)
        val unknownSampleTime = paused.copy(playing = true, positionUpdatedAtMs = 0L)
        assertEquals(83, HomeUiModelMapper.map(HomeState(playback = unknownSampleTime), words, null, false, 25_000L).progress)
    }

    @Test fun interpolatedProgressClampsToDuration() {
        val playback = PlaybackSnapshot(
            "p", "来源", "歌曲", "作者", 60_000, 50_000, true, PlaybackControls(),
            positionUpdatedAtMs = 1_000L,
        )
        val ui = HomeUiModelMapper.map(HomeState(playback = playback), words, null, false, 999_999L)
        assertEquals(1000, ui.progress)
    }

    @Test fun statusIsExplicit() {
        val ui = HomeUiModelMapper.map(HomeState(systemStatus = SystemStatus(42, true, "Wi-Fi")), words, null, false)
        assertEquals("Wi-Fi · 电量 42% · 充电中", ui.status)
    }
}
