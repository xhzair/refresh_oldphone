package io.github.xhzair.refresholdphone

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.xhzair.refresholdphone.databinding.ActivityMainBinding
import io.github.xhzair.refresholdphone.prefs.OrientationMode
import io.github.xhzair.refresholdphone.prefs.UserPreferences
import io.github.xhzair.refresholdphone.system.SystemActions
import io.github.xhzair.refresholdphone.ui.HomeState
import io.github.xhzair.refresholdphone.ui.HomeUiModelMapper
import io.github.xhzair.refresholdphone.ui.HomeViewModel
import io.github.xhzair.refresholdphone.ui.HomeWords
import io.github.xhzair.refresholdphone.ui.MediaAppAdapter
import io.github.xhzair.refresholdphone.ui.SettingsActivity

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: HomeViewModel
    private lateinit var preferences: UserPreferences
    private lateinit var actions: SystemActions
    private lateinit var adapter: MediaAppAdapter
    private lateinit var words: HomeWords

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = UserPreferences(applicationContext)
        actions = SystemActions(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this, (application as MediaShellApplication).homeViewModelFactory())[HomeViewModel::class.java]
        words = HomeWords(getString(R.string.empty_title), getString(R.string.empty_artist),
            getString(R.string.recent_app), getString(R.string.system_status), getString(R.string.charging),
            getString(R.string.installed), getString(R.string.missing))
        adapter = MediaAppAdapter(words) { app ->
            if (!app.installed) {
                Toast.makeText(this, getString(R.string.app_missing, app.label), Toast.LENGTH_SHORT).show()
            } else if (actions.launchPackage(app.packageName)) {
                preferences.lastLaunchedMediaPackage = app.packageName
            } else {
                Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
                actions.openAppDetails(app.packageName)
            }
        }
        binding.appList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.appList.adapter = adapter
        binding.appList.itemAnimator = null
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
        adapter.submitList(state.apps)
    }
}
