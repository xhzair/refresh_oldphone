package io.github.xhzair.refresholdphone.apps

data class MediaApp(
    val packageName: String,
    val label: String,
    val installed: Boolean,
    val official: Boolean,
)

interface MediaAppCatalog {
    fun load(selectedPackages: Set<String>): List<MediaApp>
}

class AppCatalog(
    private val source: InstalledAppSource,
) : MediaAppCatalog {
    override fun load(selectedPackages: Set<String>): List<MediaApp> {
        val officialApps = OFFICIAL_APPS.map { official ->
            val installed = source.isLaunchable(official.packageName)
            MediaApp(
                packageName = official.packageName,
                label = if (installed) source.label(official.packageName) else official.fallbackLabel,
                installed = installed,
                official = true,
            )
        }
        val officialPackages = OFFICIAL_APPS.mapTo(mutableSetOf()) { it.packageName }
        val extras = selectedPackages
            .asSequence()
            .filterNot(officialPackages::contains)
            .filter(source::isLaunchable)
            .sorted()
            .map { packageName ->
                MediaApp(
                    packageName = packageName,
                    label = source.label(packageName),
                    installed = true,
                    official = false,
                )
            }
            .toList()

        return officialApps + extras
    }

    private data class OfficialApp(
        val packageName: String,
        val fallbackLabel: String,
    )

    private companion object {
        val OFFICIAL_APPS = listOf(
            OfficialApp("com.netease.cloudmusic", "网易云音乐"),
            OfficialApp("com.tencent.qqmusic", "QQ音乐"),
            OfficialApp("app.podcast.cosmos", "小宇宙"),
        )
    }
}
