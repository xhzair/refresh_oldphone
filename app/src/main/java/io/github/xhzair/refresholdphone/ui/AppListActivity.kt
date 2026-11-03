package io.github.xhzair.refresholdphone.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.xhzair.refresholdphone.R
import io.github.xhzair.refresholdphone.apps.AndroidInstalledAppSource
import io.github.xhzair.refresholdphone.databinding.ActivityAppListBinding
import io.github.xhzair.refresholdphone.prefs.UserPreferences
import io.github.xhzair.refresholdphone.system.SystemActions

/** A quiet app drawer for users who keep a few non-media tools on the device. */
class AppListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppListBinding
    private lateinit var actions: SystemActions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actions = SystemActions(this)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBars()

        binding.back.setOnClickListener { finish() }
        val source = AndroidInstalledAppSource(packageManager)
        val apps = source.launchablePackages().map { packageName ->
            LauncherApp(packageName, source.label(packageName))
        }
        val visible = sortedLauncherApps(apps, packageName)
        binding.appCount.text = getString(R.string.apps_count, visible.size)

        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.itemAnimator = null
        binding.appList.adapter = LauncherAppAdapter(visible) { app ->
            if (!actions.launchPackage(app.packageName)) {
                Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applySystemBars()
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
}
