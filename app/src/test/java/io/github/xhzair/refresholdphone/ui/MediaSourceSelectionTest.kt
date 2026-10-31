package io.github.xhzair.refresholdphone.ui

import io.github.xhzair.refresholdphone.apps.MediaApp
import io.github.xhzair.refresholdphone.local.BluetoothOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSourceSelectionTest {
    private val netease = MediaApp("com.netease.cloudmusic", "网易云音乐", installed = true, official = true)
    private val qqMusic = MediaApp("com.tencent.qqmusic", "QQ音乐", installed = false, official = true)
    private val vlc = MediaApp("org.videolan.vlc", "VLC", installed = true, official = false)

    @Test
    fun `builtins appear after official apps and before extras`() {
        val entries = MediaSourceSelection.entries(
            apps = listOf(netease, qqMusic, vlc),
            bluetooth = BluetoothOutput(false, null),
            localLabel = "本地音乐",
            bluetoothLabel = "蓝牙音乐",
            disconnectedLabel = "未连接",
        )
        assertEquals(
            listOf(
                "app:com.netease.cloudmusic",
                MediaSourceSelection.ID_LOCAL,
                MediaSourceSelection.ID_BLUETOOTH,
                "app:org.videolan.vlc",
            ),
            entries.map { it.id },
        )
        assertEquals(MediaSourceKind.LOCAL_MUSIC, entries[1].kind)
        assertEquals(MediaSourceKind.BLUETOOTH_MUSIC, entries[2].kind)
    }

    @Test
    fun `bluetooth connected entry shows device name and is available`() {
        val entry = MediaSourceSelection.entries(
            apps = emptyList(),
            bluetooth = BluetoothOutput(true, "WH-1000XM3"),
            localLabel = "本地音乐",
            bluetoothLabel = "蓝牙音乐",
            disconnectedLabel = "未连接",
        ).first { it.id == MediaSourceSelection.ID_BLUETOOTH }
        assertEquals("蓝牙音乐 · WH-1000XM3", entry.label)
        assertEquals(true, entry.available)
    }

    @Test
    fun `bluetooth disconnected entry stays visible but unavailable`() {
        val entry = MediaSourceSelection.entries(
            apps = emptyList(),
            bluetooth = BluetoothOutput(false, null),
            localLabel = "本地音乐",
            bluetoothLabel = "蓝牙音乐",
            disconnectedLabel = "未连接",
        ).first { it.id == MediaSourceSelection.ID_BLUETOOTH }
        assertEquals("蓝牙音乐", entry.label)
        assertEquals("未连接", entry.sublabel)
        assertEquals(false, entry.available)
    }

    @Test
    fun `current entry matches last source id and falls back to first available`() {
        val entries = MediaSourceSelection.entries(
            apps = listOf(netease, vlc),
            bluetooth = BluetoothOutput(false, null),
            localLabel = "本地音乐",
            bluetoothLabel = "蓝牙音乐",
            disconnectedLabel = "未连接",
        )
        assertEquals(MediaSourceSelection.ID_LOCAL, MediaSourceSelection.currentEntry(entries, "local")?.id)
        assertEquals("app:com.netease.cloudmusic", MediaSourceSelection.currentEntry(entries, null)?.id)
        assertEquals("app:org.videolan.vlc", MediaSourceSelection.currentEntry(entries, "app:org.videolan.vlc")?.id)
    }

    @Test
    fun `legacy package maps to app source id`() {
        assertEquals("app:com.tencent.qqmusic", MediaSourceSelection.initialSourceId(null, "com.tencent.qqmusic"))
        assertEquals("local", MediaSourceSelection.initialSourceId("local", "com.tencent.qqmusic"))
        assertNull(MediaSourceSelection.initialSourceId(null, null))
    }
}
