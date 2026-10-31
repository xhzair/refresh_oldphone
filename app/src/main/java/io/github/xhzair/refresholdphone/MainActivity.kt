package io.github.xhzair.refresholdphone

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TouchDelegate
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import io.github.xhzair.refresholdphone.apps.MediaApp
import io.github.xhzair.refresholdphone.databinding.ActivityMainBinding
import io.github.xhzair.refresholdphone.local.BluetoothOutputSource
import io.github.xhzair.refresholdphone.prefs.OrientationMode
import io.github.xhzair.refresholdphone.prefs.UserPreferences
import io.github.xhzair.refresholdphone.system.SystemActions
import io.github.xhzair.refresholdphone.ui.AppListActivity
import io.github.xhzair.refresholdphone.ui.HomeState
import io.github.xhzair.refresholdphone.ui.HomeUiModelMapper
import io.github.xhzair.refresholdphone.ui.HomeViewModel
import io.github.xhzair.refresholdphone.ui.HomeWords
import io.github.xhzair.refresholdphone.ui.LocalMusicActivity
import io.github.xhzair.refresholdphone.ui.MediaSourceEntry
import io.github.xhzair.refresholdphone.ui.MediaSourceKind
import io.github.xhzair.refresholdphone.ui.MediaSourceSelection
import io.github.xhzair.refresholdphone.ui.SettingsActivity

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: HomeViewModel
    private lateinit var preferences: UserPreferences
    private lateinit var actions: SystemActions
    private lateinit var words: HomeWords

    private var apps: List<MediaApp> = emptyList()
    private var mediaSourceRendered = false
    private var renderedMediaSourceId: String? = null
    private var renderedSourceRequestId: String? = null
    private var renderedSourceApps: List<MediaApp>? = null
    private var lastState: HomeState? = null
    private var progressTickerRunning = false
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            val state = lastState ?: return
            render(state)
            if (state.playback?.playing == true) {
                progressHandler.postDelayed(this, PROGRESS_TICK_MS)
            } else {
                progressTickerRunning = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = UserPreferences(applicationContext)
        actions = SystemActions(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The settings/entry buttons are visually compact; keep 56 dp touch targets.
        expandTouchTarget(binding.settings, R.dimen.touch_target)
        expandTouchTarget(binding.appDrawer, R.dimen.touch_target)
        viewModel = ViewModelProvider(this, (application as MediaShellApplication).homeViewModelFactory())[HomeViewModel::class.java]
        words = HomeWords(getString(R.string.empty_title), getString(R.string.empty_artist),
            getString(R.string.recent_app), getString(R.string.system_status), getString(R.string.charging))
        binding.mediaSource.setOnClickListener { showMediaSourceDialog() }
        binding.appDrawer.setOnClickListener { startActivity(Intent(this, AppListActivity::class.java)) }
        binding.previous.setOnClickListener { viewModel.skipPrevious() }
        binding.playPause.setOnClickListener { viewModel.playPause() }
        binding.next.setOnClickListener { viewModel.skipNext() }
        binding.settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.permissionPrompt.setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.permission_title)
                .setMessage(R.string.permission_explanation)
                .setPositiveButton(R.string.open_settings) { _, _ -> actions.openNotificationAccessSettings() }
                .setNegativeButton(R.string.cancel, null).show()
        }
        viewModel.state.observe(this, ::render)
    }

    override fun onStart() {
        super.onStart()
        requestedOrientation = when (preferences.orientationMode) {
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        binding.root.keepScreenOn = preferences.keepScreenOn
        applySystemBars()
        (application as MediaShellApplication).ensureSingleSourceGuardStarted()
        viewModel.start()
    }

    override fun onStop() {
        progressHandler.removeCallbacks(progressTick)
        progressTickerRunning = false
        viewModel.stop()
        super.onStop()
    }

    /**
     * The launcher has its own clock/battery row, so the system status bar can stay hidden for
     * an immersive media device. Swiping from the top still reveals it transiently, and the
     * navigation bar is never hidden so exiting to system UI stays possible.
     */
    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (preferences.hideStatusBar) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    /**
     * Keeps a small visual button honest about its touch area: the accessibility target grows
     * to [minSizeRes] while the painted circle stays compact.
     */
    private fun expandTouchTarget(view: View, minSizeRes: Int) {
        val minSize = resources.getDimensionPixelSize(minSizeRes)
        view.post {
            val parent = view.parent as? View ?: return@post
            val rect = Rect()
            view.getHitRect(rect)
            val dx = ((minSize - view.width) / 2).coerceAtLeast(0)
            val dy = ((minSize - view.height) / 2).coerceAtLeast(0)
            rect.inset(-dx, -dy)
            parent.touchDelegate = TouchDelegate(rect, view)
        }
    }

    private fun render(state: HomeState) {
        lastState = state
        apps = state.apps
        val ui = HomeUiModelMapper.map(state, words, preferences.lastLaunchedMediaPackage,
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            SystemClock.elapsedRealtime())
        binding.trackTitle.text = ui.title
        binding.trackArtist.text = ui.artist
        binding.systemStatus.text = ui.status
        binding.artwork.setImageBitmap(state.playback?.artwork)
        binding.artwork.visibility = if (ui.showArtwork) View.VISIBLE else View.INVISIBLE
        binding.artworkFallback.visibility = if (ui.showArtwork) View.INVISIBLE else View.VISIBLE
        // Reuse the already downscaled artwork bitmap (max 512 px) as a dimmed full-bleed
        // background; no blur, no extra decode, and the 30% alpha keeps text contrast.
        binding.artworkBackground.setImageBitmap(state.playback?.artwork)
        binding.artworkBackground.visibility = if (state.playback?.artwork != null) View.VISIBLE else View.INVISIBLE
        binding.position.max = ui.progressMax
        binding.position.progress = ui.progress
        binding.position.visibility = if (ui.showProgress) View.VISIBLE else View.INVISIBLE
        listOf(binding.previous to ui.canPrevious, binding.playPause to ui.canPlayPause,
            binding.next to ui.canNext).forEach { (button, enabled) ->
            button.visibility = if (ui.showControls) View.VISIBLE else View.INVISIBLE
            button.isEnabled = enabled
        }
        binding.playPause.setImageResource(if (ui.playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPause.contentDescription = getString(if (ui.playing) R.string.pause else R.string.play)
        binding.permissionPrompt.visibility = if (ui.showPermission) View.VISIBLE else View.INVISIBLE
        renderMediaSourceIfNeeded()
        updateProgressTicker(ui.playing)
    }

    /**
     * External players only publish a new position when their state changes. While the session is
     * playing and the desktop is visible, re-render once a second so the thin progress line moves
     * smoothly from the last reported position plus elapsed time. No MediaSession polling happens;
     * the ticker stops on pause, on stop and while the desktop is not visible.
     */
    private fun updateProgressTicker(playing: Boolean) {
        if (playing && !progressTickerRunning) {
            progressTickerRunning = true
            progressHandler.postDelayed(progressTick, PROGRESS_TICK_MS)
        } else if (!playing && progressTickerRunning) {
            progressTickerRunning = false
            progressHandler.removeCallbacks(progressTick)
        }
    }

    private fun sourceEntries(): List<MediaSourceEntry> = MediaSourceSelection.entries(
        apps = apps,
        bluetooth = BluetoothOutputSource.current(this),
        localLabel = getString(R.string.local_music),
        bluetoothLabel = getString(R.string.bluetooth_music),
        disconnectedLabel = getString(R.string.bluetooth_not_connected),
    )

    private fun currentSourceId(): String? = MediaSourceSelection.initialSourceId(
        preferences.lastMediaSourceId,
        preferences.lastLaunchedMediaPackage,
    )

    /**
     * Rebuilds the compact source entry only when the selection or the app list changed. This is
     * called from every state render, including the 1 Hz progress tick, so it must not touch
     * [BluetoothOutputSource] (an AudioManager IPC) unless something actually changed.
     */
    private fun renderMediaSourceIfNeeded() {
        val desiredId = currentSourceId()
        if (mediaSourceRendered && desiredId == renderedSourceRequestId && apps == renderedSourceApps) return
        val entry = MediaSourceSelection.currentEntry(sourceEntries(), desiredId)
        renderedSourceRequestId = desiredId
        renderedSourceApps = apps
        renderMediaSource(entry)
    }

    /**
     * The compact entry shows one icon only: the currently selected media source (or a generic
     * music glyph when nothing is available). It never occupies the old horizontal card row.
     */
    private fun renderMediaSource(entry: MediaSourceEntry?) {
        if (mediaSourceRendered && entry?.id == renderedMediaSourceId) return
        mediaSourceRendered = true
        renderedMediaSourceId = entry?.id
        binding.mediaSource.setImageDrawable(
            sourceIcon(entry) ?: ContextCompat.getDrawable(this, R.drawable.ic_artwork),
        )
        binding.mediaSource.contentDescription = if (entry != null) {
            getString(R.string.media_source_entry_named, entry.label)
        } else {
            getString(R.string.media_source_entry)
        }
    }

    private fun sourceIcon(entry: MediaSourceEntry?): Drawable? = when (entry?.kind) {
        MediaSourceKind.LOCAL_MUSIC -> ContextCompat.getDrawable(this, R.drawable.ic_music_note)
        MediaSourceKind.BLUETOOTH_MUSIC -> ContextCompat.getDrawable(this, R.drawable.ic_bluetooth)
        MediaSourceKind.APP -> entry?.packageName?.let(::appIcon)
        null -> null
    }

    private fun showMediaSourceDialog() {
        // Building entries here is allowed to touch the Bluetooth IPC: it happens only on tap.
        val entries = sourceEntries()
        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.media_source_empty_title)
                .setMessage(R.string.media_source_empty_message)
                .setPositiveButton(R.string.settings_cancel, null)
                .show()
            return
        }
        val current = MediaSourceSelection.currentEntry(entries, currentSourceId())
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialog: AlertDialog? = null
        entries.forEach { entry ->
            val row = layoutInflater.inflate(R.layout.item_media_source, rows, false)
            row.findViewById<ImageView>(R.id.sourceIcon).setImageDrawable(
                sourceIcon(entry) ?: ContextCompat.getDrawable(this, R.drawable.ic_artwork),
            )
            row.findViewById<TextView>(R.id.sourceLabel).text = entry.sublabel?.let { sublabel ->
                getString(R.string.media_source_label_with_sublabel, entry.label, sublabel)
            } ?: entry.label
            row.findViewById<TextView>(R.id.sourceBadge).visibility =
                if (entry.id == current?.id) View.VISIBLE else View.GONE
            row.setOnClickListener {
                dialog?.dismiss()
                selectSource(entry)
            }
            rows.addView(row)
        }
        val scrollable = ScrollView(this).apply { addView(rows) }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.media_source_dialog_title)
            .setView(scrollable)
            .setNegativeButton(R.string.settings_cancel, null)
            .create()
        dialog.show()
    }

    private fun selectSource(entry: MediaSourceEntry) {
        when (entry.kind) {
            MediaSourceKind.APP -> {
                val app = apps.firstOrNull { it.packageName == entry.packageName }
                if (app == null) {
                    Toast.makeText(this, getString(R.string.app_missing, entry.label), Toast.LENGTH_SHORT).show()
                } else {
                    launchMediaApp(app)
                }
            }

            MediaSourceKind.LOCAL_MUSIC -> openLocalMusic(entry.id)

            MediaSourceKind.BLUETOOTH_MUSIC -> {
                if (entry.available) {
                    openLocalMusic(entry.id)
                } else {
                    AlertDialog.Builder(this)
                        .setMessage(R.string.bluetooth_not_connected_prompt)
                        .setPositiveButton(R.string.open_bluetooth_settings) { _, _ ->
                            actions.openBluetoothSettings()
                        }
                        .setNegativeButton(R.string.settings_cancel, null)
                        .show()
                }
            }
        }
    }

    private fun openLocalMusic(sourceId: String) {
        rememberSource(sourceId)
        startActivity(Intent(this, LocalMusicActivity::class.java))
    }

    private fun rememberSource(sourceId: String) {
        preferences.lastMediaSourceId = sourceId
        if (sourceId.startsWith("app:")) {
            preferences.lastLaunchedMediaPackage = sourceId.removePrefix("app:")
        }
    }

    private fun launchMediaApp(app: MediaApp) {
        if (!app.installed) {
            Toast.makeText(this, getString(R.string.app_missing, app.label), Toast.LENGTH_SHORT).show()
            return
        }
        if (actions.launchPackage(app.packageName)) {
            preferences.lastLaunchedMediaPackage = app.packageName
            rememberSource(MediaSourceSelection.appId(app.packageName))
            val entry = MediaSourceSelection.currentEntry(sourceEntries(), currentSourceId())
            renderedSourceRequestId = currentSourceId()
            renderedSourceApps = apps
            renderMediaSource(entry)
        } else {
            Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
            actions.openAppDetails(app.packageName)
        }
    }

    private fun appIcon(packageName: String): Drawable? = try {
        packageManager.getApplicationIcon(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private companion object {
        const val PROGRESS_TICK_MS = 1_000L
    }
}
