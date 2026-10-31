package io.github.xhzair.refresholdphone.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.xhzair.refresholdphone.R
import io.github.xhzair.refresholdphone.databinding.ActivityLocalMusicBinding
import io.github.xhzair.refresholdphone.local.BluetoothOutputSource
import io.github.xhzair.refresholdphone.local.LocalLibrary
import io.github.xhzair.refresholdphone.local.LocalNowPlaying
import io.github.xhzair.refresholdphone.local.LocalNowPlayingState
import io.github.xhzair.refresholdphone.local.LocalPlaybackContract
import io.github.xhzair.refresholdphone.local.LocalTrack
import io.github.xhzair.refresholdphone.prefs.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The local music library: permission gate, Bluetooth hint and a playable track list. */
class LocalMusicActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLocalMusicBinding
    private lateinit var adapter: LocalTrackAdapter

    private var tracks: List<LocalTrack> = emptyList()
    private var loading = false
    private var permissionRequested = false

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionRequested = true
            if (!granted && !shouldShowRequestPermissionRationale(audioPermission())) {
                // Permanently denied: the grant button would be a no-op, so point at Settings.
                binding.grantPermission.visibility = View.GONE
            }
            load()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalMusicBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBars()

        binding.back.setOnClickListener { finish() }
        binding.grantPermission.setOnClickListener {
            audioPermissionLauncher.launch(audioPermission())
        }
        binding.openSettings.setOnClickListener { openAppSettings() }

        adapter = LocalTrackAdapter { track, _ -> playTrack(track) }
        binding.trackList.layoutManager = LinearLayoutManager(this)
        binding.trackList.itemAnimator = null
        binding.trackList.setHasFixedSize(true)
        binding.trackList.adapter = adapter

        LocalNowPlayingState.nowPlaying.observe(this) { state -> renderNowPlaying(state) }
    }

    override fun onResume() {
        super.onResume()
        renderBluetooth()
        load()
    }

    override fun onDestroy() {
        if (this::adapter.isInitialized) adapter.shutdown()
        super.onDestroy()
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) ==
            PackageManager.PERMISSION_GRANTED

    private fun load() {
        if (loading) return
        if (isFinishing || isDestroyed) return
        if (!hasAudioPermission()) {
            showPermission()
            return
        }
        binding.grantPermission.visibility = View.VISIBLE

        loading = true
        binding.permissionPanel.visibility = View.GONE
        binding.empty.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.trackList.visibility = View.VISIBLE

        lifecycleScope.launch {
            var permissionDenied = false
            var failed = false
            val result = try {
                // Query through the application context so a late read cannot retain this Activity.
                withContext(Dispatchers.IO) { LocalLibrary.query(applicationContext) }
            } catch (_: SecurityException) {
                permissionDenied = true
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
                null
            } finally {
                loading = false
            }

            if (isFinishing || isDestroyed) return@launch
            if (permissionDenied) {
                showPermission()
                return@launch
            }
            if (failed || result == null) {
                showLoadFailure()
                return@launch
            }
            renderTracks(result)
        }
    }

    private fun renderTracks(loaded: List<LocalTrack>) {
        tracks = loaded
        binding.count.text = getString(R.string.local_music_count, loaded.size)
        binding.empty.text = getString(R.string.local_music_empty)
        binding.empty.visibility = if (loaded.isEmpty()) View.VISIBLE else View.GONE
        binding.progress.visibility = View.GONE
        binding.trackList.visibility = View.VISIBLE
        adapter.submit(loaded)
        renderNowPlaying(currentNowPlaying())
    }

    /**
     * A read error that is not a permission problem: keep any list already shown and never claim
     * the permission is missing.
     */
    private fun showLoadFailure() {
        binding.progress.visibility = View.GONE
        if (tracks.isEmpty()) {
            binding.empty.text = getString(R.string.local_music_load_failed)
            binding.empty.visibility = View.VISIBLE
            binding.trackList.visibility = View.GONE
        } else {
            binding.trackList.visibility = View.VISIBLE
        }
    }

    private fun showPermission() {
        loading = false
        binding.permissionPanel.visibility = View.VISIBLE
        binding.empty.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.trackList.visibility = View.INVISIBLE
        binding.count.text = ""
        binding.grantPermission.visibility =
            if (permissionRequested && !shouldShowRequestPermissionRationale(audioPermission())) {
                View.GONE
            } else {
                View.VISIBLE
            }
        renderNowPlaying(currentNowPlaying())
    }

    private fun renderNowPlaying(state: LocalNowPlaying) {
        adapter.setNowPlaying(state)
        binding.errorText.visibility = if (state.error) View.VISIBLE else View.GONE
    }

    private fun currentNowPlaying(): LocalNowPlaying =
        LocalNowPlayingState.nowPlaying.value ?: LocalNowPlaying()

    private fun renderBluetooth() {
        val output = BluetoothOutputSource.current(this)
        if (!output.connected) {
            binding.bluetoothBanner.visibility = View.GONE
            return
        }
        val name = output.deviceName?.takeIf(String::isNotBlank)
            ?: getString(R.string.bluetooth_music)
        binding.bluetoothBanner.text = getString(R.string.bluetooth_output_named, name)
        binding.bluetoothBanner.visibility = View.VISIBLE
    }

    private fun playTrack(track: LocalTrack) {
        // The adapter position can be stale or unrelated; identify the row by track id instead.
        val index = tracks.indexOfFirst { it.id == track.id }
        if (index < 0) return
        requestNotificationPermissionIfNeeded()
        LocalPlaybackContract.playQueue(this, tracks.map { it.id }.toLongArray(), index)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (UserPreferences(this).hideStatusBar) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}
