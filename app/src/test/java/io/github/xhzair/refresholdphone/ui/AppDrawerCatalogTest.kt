package io.github.xhzair.refresholdphone.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDrawerCatalogTest {
    private val apps = listOf(
        LauncherApp("app.podcast.cosmos", "小宇宙"),
        LauncherApp("com.tencent.qqmusic", "QQ音乐"),
        LauncherApp("com.netease.cloudmusic", "网易云音乐"),
        LauncherApp("io.github.xhzair.refresholdphone", "媒体桌面"),
        LauncherApp("com.tencent.qqmusic", "QQ音乐"),
    )

    @Test fun `self package is excluded and duplicates are removed`() {
        val sorted = sortedLauncherApps(apps, "io.github.xhzair.refresholdphone")
        assertEquals(
            listOf("com.tencent.qqmusic", "app.podcast.cosmos", "com.netease.cloudmusic"),
            sorted.map { it.packageName },
        )
    }

    @Test fun `entries are sorted by label case-insensitively`() {
        val sorted = sortedLauncherApps(
            listOf(
                LauncherApp("b", "beta"),
                LauncherApp("a", "Alpha"),
                LauncherApp("c", "charlie"),
            ),
            "self",
        )
        assertEquals(listOf("Alpha", "beta", "charlie"), sorted.map { it.label })
    }

    @Test fun `empty drawer stays empty`() {
        assertEquals(emptyList<LauncherApp>(), sortedLauncherApps(emptyList(), "self"))
    }
}
