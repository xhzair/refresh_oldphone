package io.github.xhzair.refresholdphone

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import io.github.xhzair.refresholdphone.apps.MediaApp
import io.github.xhzair.refresholdphone.databinding.ActivityMainBinding
import io.github.xhzair.refresholdphone.prefs.OrientationMode
import io.github.xhzair.refresholdphone.prefs.UserPreferences
import io.github.xhzair.refresholdphone.system.SystemActions
import io.github.xhzair.refresholdphone.ui.HomeState
import io.github.xhzair.refresholdphone.ui.HomeUiModelMapper
import io.github.xhzair.refresholdphone.ui.HomeViewModel
import io.github.xhzair.refresholdphone.ui.HomeWords
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
    private var renderedMediaSourcePackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = UserPreferences(applicationContext)
        actions = SystemActions(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this, (application as MediaShellApplication).homeViewModelFactory())[HomeViewModel::class.java]
        words = HomeWords(getString(R.string.empty_title), getString(R.string.empty_artist),
            getString(R.string.recent_app), getString(R.string.system_status), getString(R.string.charging))
        binding.mediaSource.setOnClickListener { showMediaSourceDialog() }
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
        binding.ambientPhrase.visibility = if (preferences.ambientPhraseVisible &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) View.VISIBLE else View.GONE
        viewModel.start()
    }

    override fun onStop() {
        viewModel.stop()
        super.onStop()
    }

    private fun render(state: HomeState) {
        apps = state.apps
        val ui = HomeUiModelMapper.map(state, words, preferences.lastLaunchedMediaPackage,
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        binding.trackTitle.text = ui.title
        binding.trackArtist.text = ui.artist
        binding.systemStatus.text = ui.status
        binding.artwork.setImageBitmap(state.playback?.artwork)
        binding.artwork.visibility = if (ui.showArtwork) View.VISIBLE else View.INVISIBLE
        binding.artworkFallback.visibility = if (ui.showArtwork) View.INVISIBLE else View.VISIBLE
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
        renderMediaSource(MediaSourceSelection.currentSource(apps, preferences.lastLaunchedMediaPackage))
    }

    /**
     * The compact entry shows one icon only: the currently selected media source (or a generic
     * music glyph when nothing is installed). It never occupies the old horizontal card row.
     */
    private fun renderMediaSource(source: MediaApp?) {
        val packageName = source?.packageName
        if (mediaSourceRendered && packageName == renderedMediaSourcePackage) return
        mediaSourceRendered = true
        renderedMediaSourcePackage = packageName
        val icon = source?.let { appIcon(it.packageName) }
            ?: ContextCompat.getDrawable(this, R.drawable.ic_artwork)
        binding.mediaSource.setImageDrawable(icon)
        binding.mediaSource.contentDescription = if (source != null) {
            getString(R.string.media_source_entry_named, source.label)
        } else {
            getString(R.string.media_source_entry)
        }
    }

    private fun showMediaSourceDialog() {
        val sources = MediaSourceSelection.availableSources(apps)
        if (sources.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.media_source_empty_title)
                .setMessage(R.string.media_source_empty_message)
                .setPositiveButton(R.string.settings_cancel, null)
                .show()
            return
        }
        val currentPackage = MediaSourceSelection.currentSource(apps, preferences.lastLaunchedMediaPackage)
            ?.packageName
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialog: AlertDialog? = null
        sources.forEach { app ->
            val row = layoutInflater.inflate(R.layout.item_media_source, rows, false)
            row.findViewById<ImageView>(R.id.sourceIcon).setImageDrawable(
                appIcon(app.packageName) ?: ContextCompat.getDrawable(this, R.drawable.ic_artwork),
            )
            row.findViewById<TextView>(R.id.sourceLabel).text = app.label
            row.findViewById<TextView>(R.id.sourceBadge).visibility =
                if (app.packageName == currentPackage) View.VISIBLE else View.GONE
            row.setOnClickListener {
                dialog?.dismiss()
                launchMediaApp(app)
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

    private fun launchMediaApp(app: MediaApp) {
        if (!app.installed) {
            Toast.makeText(this, getString(R.string.app_missing, app.label), Toast.LENGTH_SHORT).show()
            return
        }
        if (actions.launchPackage(app.packageName)) {
            preferences.lastLaunchedMediaPackage = app.packageName
            renderMediaSource(app)
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
}
