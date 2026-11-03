package io.github.xhzair.refresholdphone.ui

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.xhzair.refresholdphone.MediaShellApplication
import io.github.xhzair.refresholdphone.R
import io.github.xhzair.refresholdphone.databinding.ActivityListeningStatsBinding
import io.github.xhzair.refresholdphone.prefs.UserPreferences
import io.github.xhzair.refresholdphone.stats.ListeningHistoryRepository
import io.github.xhzair.refresholdphone.stats.ListeningSourceKind
import io.github.xhzair.refresholdphone.stats.ListeningSourceShare
import io.github.xhzair.refresholdphone.stats.MonthlyListeningSummary
import io.github.xhzair.refresholdphone.stats.RankedListeningItem
import io.github.xhzair.refresholdphone.stats.formatListeningDuration
import io.github.xhzair.refresholdphone.stats.formatListeningRank
import java.util.Calendar

/** A month-at-a-time view over the persisted listening history. */
class ListeningStatsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityListeningStatsBinding
    private lateinit var repository: ListeningHistoryRepository

    /** Selected month; only the year and month fields are meaningful. */
    private val selectedMonth = Calendar.getInstance()
    private var loadGeneration = 0

    /** Scroll offset captured before recreation; applied once the first result renders. */
    private var pendingScrollY = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListeningStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBars()

        repository = (application as MediaShellApplication).listeningHistory
        selectedMonth.set(Calendar.DAY_OF_MONTH, 1)
        restoreSelectedMonth(savedInstanceState)

        binding.back.setOnClickListener { finish() }
        binding.previousMonth.setOnClickListener { changeMonth(-1) }
        binding.nextMonth.setOnClickListener { changeMonth(1) }
        binding.clearHistory.setOnClickListener { confirmClear() }
        binding.errorText.setOnClickListener { load() }
        renderMonthLabel()
        showLoading()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_YEAR, selectedMonth.get(Calendar.YEAR))
        outState.putInt(STATE_MONTH_ZERO_BASED, selectedMonth.get(Calendar.MONTH))
        outState.putInt(STATE_SCROLL_Y, binding.scroll.scrollY)
    }

    /**
     * Restores the month before the first render and load, so a past month never flashes the
     * current month after the Activity is recreated.
     */
    private fun restoreSelectedMonth(state: Bundle?) {
        if (state == null) return
        val year = state.getInt(STATE_YEAR, 0)
        val monthZeroBased = state.getInt(STATE_MONTH_ZERO_BASED, -1)
        if (year <= 0 || monthZeroBased !in 0..11) return
        selectedMonth.set(year, monthZeroBased, 1)
        pendingScrollY = state.getInt(STATE_SCROLL_Y, 0).coerceAtLeast(0)
    }

    override fun onResume() {
        super.onResume()
        applySystemBars()
        // Playback may have been observed while this screen was in the background.
        load()
    }

    private fun changeMonth(delta: Int) {
        selectedMonth.add(Calendar.MONTH, delta)
        selectedMonth.set(Calendar.DAY_OF_MONTH, 1)
        renderMonthLabel()
        load()
    }

    private fun renderMonthLabel() {
        binding.monthLabel.text = getString(
            R.string.listening_stats_month_format,
            selectedMonth.get(Calendar.YEAR),
            selectedMonth.get(Calendar.MONTH) + 1,
        )
    }

    private fun load() {
        val generation = ++loadGeneration
        val year = selectedMonth.get(Calendar.YEAR)
        val monthZeroBased = selectedMonth.get(Calendar.MONTH)
        showLoading()
        repository.loadMonth(year, monthZeroBased) { result ->
            // A late callback must not overwrite a newer month or a finished screen.
            if (isFinishing || isDestroyed || generation != loadGeneration) return@loadMonth
            result.onSuccess(::render).onFailure { showError() }
        }
    }

    /**
     * Enters an explicit loading state at the start of every load, so the previous month's
     * numbers can never sit under the newly selected month's heading.
     */
    private fun showLoading() {
        binding.errorText.visibility = View.GONE
        val placeholder = getString(R.string.listening_stats_placeholder)
        binding.totalDuration.text = placeholder
        binding.activeDays.text = placeholder
        binding.sessionCount.text = placeholder
        binding.longestSession.text = placeholder
        val loading = getString(R.string.listening_stats_loading)
        binding.topTracks.text = loading
        binding.topArtists.text = loading
        binding.topAlbums.text = loading
        binding.sources.text = loading
        binding.hoursChart.removeAllViews()
        binding.hoursChart.contentDescription = loading
    }

    /**
     * Keeps the failed month's label but clears every detail value before offering a retry, so a
     * failure cannot leave the previous month's figures on screen.
     */
    private fun showError() {
        val placeholder = getString(R.string.listening_stats_placeholder)
        binding.totalDuration.text = placeholder
        binding.activeDays.text = placeholder
        binding.sessionCount.text = placeholder
        binding.longestSession.text = placeholder
        val empty = getString(R.string.listening_stats_empty)
        binding.topTracks.text = empty
        binding.topArtists.text = empty
        binding.topAlbums.text = empty
        binding.sources.text = placeholder
        renderHours(LongArray(HOURS_PER_DAY))
        binding.errorText.text = getString(R.string.listening_stats_error_retry)
        binding.errorText.visibility = View.VISIBLE
        applyPendingScroll()
    }

    /** Restores the pre-recreation scroll offset once, after the first page of content exists. */
    private fun applyPendingScroll() {
        val scrollY = pendingScrollY
        pendingScrollY = 0
        if (scrollY <= 0) return
        binding.scroll.post { binding.scroll.scrollTo(0, scrollY) }
    }

    private fun render(summary: MonthlyListeningSummary) {
        binding.errorText.visibility = View.GONE
        binding.totalDuration.text = getString(
            R.string.listening_stats_summary_total,
            formatListeningDuration(summary.totalListenedMs),
        )
        binding.activeDays.text = getString(
            R.string.listening_stats_summary_active_days,
            summary.activeDays,
        )
        binding.sessionCount.text = getString(
            R.string.listening_stats_summary_sessions,
            summary.sessionCount,
        )
        binding.longestSession.text = getString(
            R.string.listening_stats_summary_longest,
            formatListeningDuration(summary.longestSessionMs),
        )
        binding.topTracks.text = formatRanked(summary.topTracks)
        binding.topArtists.text = formatRanked(summary.topArtists)
        binding.topAlbums.text = formatRanked(summary.topAlbums)
        binding.sources.text = formatSources(summary.sources)
        renderHours(summary.hours)
        applyPendingScroll()
    }

    private fun formatRanked(items: List<RankedListeningItem>): String {
        if (items.isEmpty()) return getString(R.string.listening_stats_empty)
        return items.take(TOP_LIMIT)
            .mapIndexed { index, item -> formatListeningRank(index + 1, item) }
            .joinToString("\n")
    }

    private fun formatSources(sources: List<ListeningSourceShare>): String {
        if (sources.isEmpty()) return getString(R.string.listening_stats_empty)
        return sources.joinToString("\n") { source ->
            val label = when (source.sourceKind) {
                ListeningSourceKind.LOCAL -> getString(R.string.listening_stats_source_local)
                ListeningSourceKind.EXTERNAL -> externalSourceLabel(source)
            }
            getString(
                R.string.listening_stats_source_line,
                label,
                formatListeningDuration(source.listenedMs),
            )
        }
    }

    /**
     * Resolves an external source to its current app label. The stored package name is the
     * fallback, so uninstalled or hidden packages still identify where the time came from.
     */
    private fun externalSourceLabel(source: ListeningSourceShare): String {
        val packageName = source.packageName?.trim().orEmpty()
        if (packageName.isNotEmpty()) {
            return applicationLabel(packageName) ?: packageName
        }
        return source.label.trim().takeIf { it.isNotEmpty() }
            ?: getString(R.string.listening_stats_source_external)
    }

    private fun applicationLabel(packageName: String): String? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun renderHours(hours: LongArray) {
        binding.hoursChart.removeAllViews()
        val peak = hours.maxOrNull() ?: 0L
        if (peak <= 0L) {
            // Nothing to plot; an empty chart avoids a misleading flat line.
            binding.hoursChart.contentDescription = describeHours(hours)
            return
        }
        val density = resources.displayMetrics.density
        val minHeight = (MIN_BAR_DP * density).toInt()
        val maxHeight = (MAX_BAR_DP * density).toInt()

        for (hour in 0 until HOURS_PER_DAY) {
            val listenedMs = hours.getOrElse(hour) { 0L }.coerceAtLeast(0L)
            val height = minHeight +
                ((maxHeight - minHeight) * (listenedMs.toDouble() / peak)).toInt()
            val bar = View(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@ListeningStatsActivity, R.color.accent))
            }
            val params = LinearLayout.LayoutParams(0, height, 1f).apply {
                val margin = (BAR_MARGIN_DP * density).toInt()
                marginStart = margin
                marginEnd = margin
                gravity = Gravity.BOTTOM
            }
            binding.hoursChart.addView(bar, params)
        }
        binding.hoursChart.contentDescription = describeHours(hours)
    }

    /** Summarizes the chart for screen readers instead of exposing 24 purely decorative bars. */
    private fun describeHours(hours: LongArray): String {
        var peakHour = -1
        var peakMs = 0L
        var totalMs = 0L
        for (hour in 0 until HOURS_PER_DAY) {
            val listenedMs = hours.getOrElse(hour) { 0L }.coerceAtLeast(0L)
            if (listenedMs > peakMs) {
                peakMs = listenedMs
                peakHour = hour
            }
            totalMs += listenedMs
        }
        if (peakHour < 0) return getString(R.string.listening_stats_hours_empty)
        return getString(
            R.string.listening_stats_hours_peak,
            peakHour,
            formatListeningDuration(totalMs),
        )
    }

    private fun confirmClear() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.listening_stats_clear_title)
            .setMessage(R.string.listening_stats_clear_message)
            .setPositiveButton(R.string.listening_stats_clear_confirm) { _, _ -> clearHistory() }
            .setNegativeButton(R.string.settings_cancel, null)
            .create()
        dialog.show()
        dialog.applyImmersiveStatusBar()
    }

    private fun clearHistory() {
        repository.clear { success ->
            if (isFinishing || isDestroyed) return@clear
            if (success) {
                Toast.makeText(this, R.string.listening_stats_cleared, Toast.LENGTH_SHORT).show()
                load()
            } else {
                Toast.makeText(this, R.string.listening_stats_clear_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (UserPreferences(applicationContext).hideStatusBar) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private companion object {
        const val TOP_LIMIT = 5
        const val HOURS_PER_DAY = 24
        const val MIN_BAR_DP = 4
        const val MAX_BAR_DP = 80
        const val BAR_MARGIN_DP = 1

        /** Saved-state keys are stable so recreation restores the same month across versions. */
        const val STATE_YEAR = "listening_stats_year"
        const val STATE_MONTH_ZERO_BASED = "listening_stats_month"
        const val STATE_SCROLL_Y = "listening_stats_scroll_y"
    }
}
