package io.github.xhzair.refresholdphone.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCatalogTest {
    @Test
    fun `official apps keep stable order and report installed state`() {
        val source = FakeInstalledAppSource(
            launchable = setOf("com.tencent.qqmusic"),
        )

        val apps = AppCatalog(source).load(emptySet())

        assertEquals(
            listOf(
                "com.netease.cloudmusic",
                "com.tencent.qqmusic",
                "app.podcast.cosmos",
            ),
            apps.map(MediaApp::packageName),
        )
        assertEquals(listOf(false, true, false), apps.map(MediaApp::installed))
        assertEquals(listOf(true, true, true), apps.map(MediaApp::official))
    }

    @Test
    fun `installed official app uses actual label and missing app uses fallback`() {
        val source = FakeInstalledAppSource(
            launchable = setOf("com.netease.cloudmusic"),
            labels = mapOf("com.netease.cloudmusic" to "Cloud Music"),
        )

        val apps = AppCatalog(source).load(emptySet())

        assertEquals("Cloud Music", apps[0].label)
        assertEquals("QQ音乐", apps[1].label)
        assertEquals(0, source.labelRequests.count { it == "com.tencent.qqmusic" })
    }

    @Test
    fun `selected launchable extras follow officials without duplicating official selections`() {
        val source = FakeInstalledAppSource(
            launchable = setOf(
                "com.netease.cloudmusic",
                "com.example.radio",
            ),
            labels = mapOf("com.example.radio" to "Radio"),
        )

        val apps = AppCatalog(source).load(
            setOf(
                "com.netease.cloudmusic",
                "com.example.radio",
            ),
        )

        assertEquals(4, apps.size)
        assertEquals(1, apps.count { it.packageName == "com.netease.cloudmusic" })
        assertEquals("com.example.radio", apps.last().packageName)
        assertEquals("Radio", apps.last().label)
        assertEquals(false, apps.last().official)
        assertEquals(true, apps.last().installed)
    }

    @Test
    fun `missing selected extra is excluded`() {
        val source = FakeInstalledAppSource(
            launchable = setOf("com.example.available"),
        )

        val apps = AppCatalog(source).load(
            setOf("com.example.available", "com.example.missing"),
        )

        assertEquals(
            listOf(
                "com.netease.cloudmusic",
                "com.tencent.qqmusic",
                "app.podcast.cosmos",
                "com.example.available",
            ),
            apps.map(MediaApp::packageName),
        )
    }

    @Test
    fun `extras are ordered deterministically by package name`() {
        val source = FakeInstalledAppSource(
            launchable = setOf(
                "com.example.zebra",
                "com.example.alpha",
                "com.example.middle",
            ),
        )

        val apps = AppCatalog(source).load(
            linkedSetOf(
                "com.example.zebra",
                "com.example.middle",
                "com.example.alpha",
            ),
        )

        assertEquals(
            listOf(
                "com.example.alpha",
                "com.example.middle",
                "com.example.zebra",
            ),
            apps.drop(3).map(MediaApp::packageName),
        )
    }

    private class FakeInstalledAppSource(
        private val launchable: Set<String>,
        private val labels: Map<String, String> = emptyMap(),
    ) : InstalledAppSource {
        val labelRequests = mutableListOf<String>()

        override fun isLaunchable(packageName: String): Boolean = packageName in launchable

        override fun label(packageName: String): String {
            labelRequests += packageName
            return labels[packageName] ?: packageName
        }

        override fun launchablePackages(): List<String> = launchable.sorted()
    }
}
