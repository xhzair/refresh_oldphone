package io.github.xhzair.refresholdphone.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.xhzair.refresholdphone.BuildConfig
import io.github.xhzair.refresholdphone.R
import io.github.xhzair.refresholdphone.apps.AndroidInstalledAppSource
import io.github.xhzair.refresholdphone.apps.AppCatalog
import io.github.xhzair.refresholdphone.databinding.ActivitySettingsBinding
import io.github.xhzair.refresholdphone.prefs.OrientationMode
import io.github.xhzair.refresholdphone.prefs.UserPreferences
import io.github.xhzair.refresholdphone.system.SystemActions

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferences: UserPreferences
    private lateinit var actions: SystemActions
    private lateinit var installedApps: AndroidInstalledAppSource

    /** Guards preference listeners while controls are populated from stored values. */
    private var applyingPreferences = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = UserPreferences(applicationContext)
        actions = SystemActions(this)
        installedApps = AndroidInstalledAppSource(packageManager)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyDesktopSystemBars()

        binding.back.setOnClickListener { finish() }
        binding.orientationGroup.setOnCheckedChangeListener { _, checkedId ->
            if (applyingPreferences) return@setOnCheckedChangeListener
            preferences.orientationMode = when (checkedId) {
                R.id.orientationPortrait -> OrientationMode.PORTRAIT
                R.id.orientationLandscape -> OrientationMode.LANDSCAPE
                else -> OrientationMode.AUTO
            }
        }
        binding.keepScreenOnSwitch.setOnCheckedChangeListener { _, checked ->
            if (applyingPreferences) return@setOnCheckedChangeListener
            preferences.keepScreenOn = checked
        }
        binding.hideStatusBarSwitch.setOnCheckedChangeListener { _, checked ->
            if (applyingPreferences) return@setOnCheckedChangeListener
            preferences.hideStatusBar = checked
        }
        binding.selectApps.setOnClickListener { showAppSelection() }
        binding.openNotificationAccess.setOnClickListener {
            actions.openNotificationAccessSettings()
        }
        binding.listeningStats.setOnClickListener {
            startActivity(Intent(this, ListeningStatsActivity::class.java))
        }
        binding.openWifi.setOnClickListener { actions.openWifiSettings() }
        binding.openMobileNetwork.setOnClickListener { actions.openMobileNetworkSettings() }
        binding.openBluetooth.setOnClickListener { actions.openBluetoothSettings() }
        binding.openVolume.setOnClickListener { actions.openVolumeSettings() }
        binding.openAppDetails.setOnClickListener { actions.openSelfAppDetails() }
        binding.openSystemSettings.setOnClickListener { actions.openSystemSettings() }

        // Both recovery entries land on the system HOME picker; the prominent one is the
        // product's explicit escape hatch, and explains the manual fallback below it.
        binding.chooseHome.setOnClickListener { actions.openHomeChooser() }
        binding.restoreHome.setOnClickListener { actions.openHomeChooser() }

        binding.clearLocalConfig.setOnClickListener { confirmClearLocalConfig() }
        binding.openGithub.setOnClickListener { actions.openUrl(REPOSITORY_URL) }
        binding.versionText.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        applyDesktopSystemBars()
        refreshFromPreferences()
    }

    private fun refreshFromPreferences() {
        applyingPreferences = true
        binding.orientationGroup.check(
            when (preferences.orientationMode) {
                OrientationMode.AUTO -> R.id.orientationAuto
                OrientationMode.PORTRAIT -> R.id.orientationPortrait
                OrientationMode.LANDSCAPE -> R.id.orientationLandscape
            },
        )
        binding.keepScreenOnSwitch.isChecked = preferences.keepScreenOn
        binding.hideStatusBarSwitch.isChecked = preferences.hideStatusBar
        applyingPreferences = false
        refreshNotificationAccess()
        refreshAppSummary()
    }

    private fun refreshNotificationAccess() {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        binding.notificationState.setText(
            if (enabled) R.string.settings_notification_on else R.string.settings_notification_off,
        )
        binding.notificationState.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.accent else R.color.muted),
        )
    }

    private fun refreshAppSummary() {
        val candidates = candidatePackages()
        val count = preferences.selectedPackages().count { it in candidates }
        binding.appsSummary.text = if (count == 0) {
            getString(R.string.settings_apps_none)
        } else {
            getString(R.string.settings_apps_summary, count)
        }
    }

    /**
     * Launchable apps the user may add as extra media entries. The three official providers are
     * always on the home screen already, and this app itself is not a media app.
     */
    private fun candidatePackages(): Set<String> {
        val officialPackages = AppCatalog(installedApps)
            .load(emptySet())
            .asSequence()
            .filter { it.official }
            .map { it.packageName }
            .toSet()
        return installedApps.launchablePackages()
            .asSequence()
            .filterNot { it == packageName || it in officialPackages }
            .toCollection(linkedSetOf())
    }

    private fun showAppSelection() {
        val candidates = candidatePackages().map { it to installedApps.label(it) }
            .sortedBy { (_, label) -> label }
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.settings_apps_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val selectedPackages = preferences.selectedPackages()
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.screen_padding)
        val accentTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
        val checkBoxes = candidates.map { (packageName, label) ->
            // Explicit checkboxes instead of the platform multi-choice list so every row keeps
            // the 18sp body size and 56dp touch target required by the product guidelines.
            CheckBox(this).apply {
                text = label
                textSize = 18f
                minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
                isChecked = packageName in selectedPackages
                buttonTintList = accentTint
                setPadding(horizontalPadding, 0, horizontalPadding, 0)
            }
        }
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            checkBoxes.forEach(::addView)
        }
        // Device fleets can have dozens of launchable apps; keep the dialog scrollable.
        val scrollableOptions = ScrollView(this).apply { addView(options) }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_apps_title)
            .setView(scrollableOptions)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                preferences.setSelectedPackages(
                    candidates.filterIndexed { index, _ -> checkBoxes[index].isChecked }
                        .mapTo(linkedSetOf()) { it.first },
                )
                refreshAppSummary()
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun confirmClearLocalConfig() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear_title)
            .setMessage(R.string.settings_clear_message)
            .setPositiveButton(R.string.settings_clear_confirm) { _, _ ->
                preferences.clear()
                refreshFromPreferences()
                Toast.makeText(this, R.string.settings_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private companion object {
        const val REPOSITORY_URL = "https://github.com/xhzair/refresh_oldphone"
    }
}
