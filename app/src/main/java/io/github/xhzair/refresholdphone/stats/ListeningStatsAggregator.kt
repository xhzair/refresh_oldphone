package io.github.xhzair.refresholdphone.stats

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object ListeningStatsAggregator {
    private const val TOP_LIMIT = 5
    private const val UNKNOWN_TITLE = "未知歌曲"
    private const val UNKNOWN_ARTIST = "未知歌手"
    private const val UNKNOWN_ALBUM = "未知专辑"
    private const val LOCAL_SOURCE_LABEL = "本地音乐"
    private const val EXTERNAL_SOURCE_LABEL = "外部应用"
    private const val TRACK_KEY_SEPARATOR = "\u0000"

    fun summarize(
        sessions: List<ListeningSession>,
        year: Int,
        monthZeroBased: Int,
        zone: TimeZone,
    ): MonthlyListeningSummary {
        require(monthZeroBased in 0..11) { "month must be 0..11" }

        val monthLabel = String.format(Locale.CHINA, "%d年%d月", year, monthZeroBased + 1)

        val startCalendar = Calendar.getInstance(zone).apply {
            clear()
            set(year, monthZeroBased, 1, 0, 0, 0)
        }
        val start = startCalendar.timeInMillis
        val end = (startCalendar.clone() as Calendar).apply {
            add(Calendar.MONTH, 1)
        }.timeInMillis

        val hours = LongArray(24)
        val activeDays = HashSet<Long>()
        val trackTotals = HashMap<String, TrackTotal>()
        val artistTotals = HashMap<String, Long>()
        val albumTotals = HashMap<String, Long>()
        val sourceTotals = HashMap<SourceKey, Long>()
        val calendar = Calendar.getInstance(zone)

        var totalListenedMs = 0L
        var sessionCount = 0
        var longestSessionMs = 0L

        for (session in sessions) {
            if (session.listenedMs <= 0L) continue
            if (session.startedAtMs < start || session.startedAtMs >= end) continue

            totalListenedMs += session.listenedMs
            sessionCount += 1
            if (session.listenedMs > longestSessionMs) {
                longestSessionMs = session.listenedMs
            }

            calendar.timeInMillis = session.startedAtMs
            activeDays.add(calendar.get(Calendar.YEAR) * 400L + calendar.get(Calendar.DAY_OF_YEAR))
            hours[calendar.get(Calendar.HOUR_OF_DAY)] += session.listenedMs

            val title = normalize(session.title, UNKNOWN_TITLE)
            val artist = normalize(session.artist, UNKNOWN_ARTIST)
            val album = normalize(session.album, UNKNOWN_ALBUM)
            val trackKey = title + TRACK_KEY_SEPARATOR + artist
            val previousTrack = trackTotals[trackKey]
            trackTotals[trackKey] = TrackTotal(
                title = title,
                artist = artist,
                listenedMs = (previousTrack?.listenedMs ?: 0L) + session.listenedMs,
            )
            artistTotals[artist] = (artistTotals[artist] ?: 0L) + session.listenedMs
            albumTotals[album] = (albumTotals[album] ?: 0L) + session.listenedMs

            val sourceKey = SourceKey(
                sourceKind = session.sourceKind,
                packageName = when (session.sourceKind) {
                    ListeningSourceKind.LOCAL -> null
                    ListeningSourceKind.EXTERNAL ->
                        session.packageName?.trim()?.takeIf { it.isNotBlank() }
                },
            )
            sourceTotals[sourceKey] = (sourceTotals[sourceKey] ?: 0L) + session.listenedMs
        }

        return MonthlyListeningSummary(
            monthLabel = monthLabel,
            totalListenedMs = totalListenedMs,
            sessionCount = sessionCount,
            activeDays = activeDays.size,
            topTracks = rankTracks(trackTotals.values),
            topArtists = rank(artistTotals),
            topAlbums = rank(albumTotals),
            sources = rankSources(sourceTotals),
            hours = hours,
            longestSessionMs = longestSessionMs,
        )
    }

    private fun normalize(value: String?, fallback: String): String =
        value?.trim()?.takeIf { it.isNotBlank() } ?: fallback

    private fun rankTracks(totals: Collection<TrackTotal>): List<RankedListeningItem> =
        totals.map { RankedListeningItem(label = it.title, listenedMs = it.listenedMs, detail = it.artist) }
            .sortedWith(
                compareByDescending<RankedListeningItem> { it.listenedMs }
                    .thenBy { it.label }
                    .thenBy { it.detail ?: "" },
            )
            .take(TOP_LIMIT)

    private fun rank(totals: Map<String, Long>): List<RankedListeningItem> =
        totals.map { (label, listenedMs) -> RankedListeningItem(label, listenedMs) }
            .sortedWith(
                compareByDescending<RankedListeningItem> { it.listenedMs }
                    .thenBy { it.label },
            )
            .take(TOP_LIMIT)

    private fun rankSources(totals: Map<SourceKey, Long>): List<ListeningSourceShare> =
        totals.map { (key, listenedMs) ->
            ListeningSourceShare(
                label = sourceLabelOf(key),
                packageName = key.packageName,
                sourceKind = key.sourceKind,
                listenedMs = listenedMs,
            )
        }.sortedWith(
            compareByDescending<ListeningSourceShare> { it.listenedMs }
                .thenBy { it.label },
        )

    private fun sourceLabelOf(key: SourceKey): String = when (key.sourceKind) {
        ListeningSourceKind.LOCAL -> LOCAL_SOURCE_LABEL
        ListeningSourceKind.EXTERNAL -> normalize(key.packageName, EXTERNAL_SOURCE_LABEL)
    }

    private data class TrackTotal(
        val title: String,
        val artist: String,
        val listenedMs: Long,
    )

    private data class SourceKey(
        val sourceKind: ListeningSourceKind,
        val packageName: String?,
    )
}
