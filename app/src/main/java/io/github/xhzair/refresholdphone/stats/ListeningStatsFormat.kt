package io.github.xhzair.refresholdphone.stats

import java.util.Locale

/**
 * Formats a listening duration as m:ss, or h:mm:ss once it reaches an hour.
 *
 * Negative values clamp to zero so a corrupt snapshot can never render as a negative time.
 */
fun formatListeningDuration(totalMs: Long): String {
    val totalSeconds = totalMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * Formats one ranked row as `1. label · detail  duration`.
 *
 * [index] is the already one-based rank shown to the user. A null or blank detail is omitted so
 * artist and album rows stay compact.
 */
fun formatListeningRank(index: Int, item: RankedListeningItem): String {
    val detail = item.detail?.trim().orEmpty()
    val title = if (detail.isEmpty()) {
        "$index. ${item.label}"
    } else {
        "$index. ${item.label} · $detail"
    }
    return "$title  ${formatListeningDuration(item.listenedMs)}"
}
