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
import io.github.xhzair.refresholdphone.local.LocalNowPlayingState
import io.github.xhzair.refresholdphone.local.LocalPlaybackContract
import io.github.xhzair.refresholdphone.local.LocalTrack
import io.github.xhzair.refresholdphone.prefs.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The local music library: permission gate, Bluetooth hint and a playable track list. */
class LocalMusicActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLocalMusicBinding
    private lateinit var adapter: LocalTrackAdapter

    private var tracks: List<LocalTrack> = emptyList()
    private var loading = false

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }

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

        adapter = LocalTrackAdapter(::playTrack)
        binding.trackList.layoutManager = LinearLayoutManager(this)
        binding.trackList.itemAnimator = null
        binding.trackList.adapter = adapter

        LocalNowPlayingState.nowPlaying.observe(this) { state ->
            adapter.setNowPlaying(state)
            binding.errorText.visibility = if (state.error) View.VISIBLE else View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        renderBluetooth()
        load()
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
        if (!hasAudioPermission()) {
            showPermission()
            return
        }

        loading = true
        binding.permissionPanel.visibility = View.GONE
        binding.empty.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.trackList.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { LocalLibrary.query(this@LocalMusicActivity) }
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
            loading = false
            if (isFinishing || isDestroyed) return@launch
            if (result == null) {
                showPermission()
                return@launch
            }

            tracks = result
            binding.count.text = getString(R.string.local_music_count, result.size)
            binding.empty.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
            adapter.submit(result)
            binding.progress.visibility = View.GONE
        }
    }

    private fun showPermission() {
        loading = false
        binding.permissionPanel.visibility = View.VISIBLE
        binding.empty.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.errorText.visibility = View.GONE
        binding.trackList.visibility = View.INVISIBLE
        binding.count.text = ""
    }

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

    private fun playTrack(track: LocalTrack, index: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val ids = tracks.map { it.id }.toLongArray()
        if (index !in ids.indices) return
        LocalPlaybackContract.playQueue(this, ids, index)
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
