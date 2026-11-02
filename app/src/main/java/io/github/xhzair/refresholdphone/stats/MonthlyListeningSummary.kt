package io.github.xhzair.refresholdphone.stats

data class RankedListeningItem(val label: String, val listenedMs: Long)

data class ListeningSourceShare(
    val label: String,
    val packageName: String?,
    val sourceKind: ListeningSourceKind,
    val listenedMs: Long,
)

data class MonthlyListeningSummary(
    val monthLabel: String,
    val totalListenedMs: Long,
    val sessionCount: Int,
    val activeDays: Int,
    val topTracks: List<RankedListeningItem>,
    val topArtists: List<RankedListeningItem>,
    val topAlbums: List<RankedListeningItem>,
    val sources: List<ListeningSourceShare>,
    val hours: LongArray,
    val longestSessionMs: Long,
)
