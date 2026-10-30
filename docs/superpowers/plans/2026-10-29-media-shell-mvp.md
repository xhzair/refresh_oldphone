# Old Android Media Shell MVP Implementation Plan

> **Status (2026-10-30):** Tasks 1–10 are implemented on `feature/media-shell-mvp`. Unit tests (8 classes / 51 cases) and instrumented tests (7 cases) pass; MIX 2S acceptance is recorded in `docs/device-testing-mix2s.md`. The checkboxes below are kept as the original planning text — see `git log` for step-by-step history. Not covered on device: audio-playing rotation, lock/unlock, and reboot persistence (reboot requires explicit user approval).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a no-root Android 6+ home-screen replacement that launches the user's existing NetEase Cloud Music, QQ Music, and Xiaoyuzhou apps, shows their current media session when Android exposes it, and switches between a portrait playback-first layout and a landscape ambient-clock layout.

**Architecture:** A single-activity native Android View/XML app owns only the launcher shell, system shortcuts, preferences, and a notification/media-session bridge. Streaming, accounts, downloads, DRM, and playback remain inside official provider apps. Small pure-Kotlin reducers and catalogs hold policy so they can be unit-tested without a device; Android adapters handle package discovery, settings intents, notifications, media controls, power, network, and battery state.

**Tech Stack:** Kotlin, Android Views/XML, AndroidX AppCompat/Core/ConstraintLayout/RecyclerView/Lifecycle, Gradle 9.6.0, Android Gradle Plugin 9.4.1, JDK 17, compile/target SDK 36, min SDK 23, JUnit 4, AndroidX Test/Espresso.

---

## Product boundaries

- Version 0.1 does not implement local music, RSS podcast parsing, network radio, provider login, provider APIs, or content aggregation.
- The app never requires root, device-owner privileges, Accessibility Service, VPN interception, or account credentials.
- Choosing this app as HOME is optional and reversible. Settings always exposes “恢复系统桌面” and Android's HOME-app chooser.
- A missing provider app is a normal state: its card becomes an install/help action rather than crashing.
- Notification access is optional. Without it, provider launching, clock, status, settings, and recovery still work.

## Repository map

```text
refresh_oldphone/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/io/github/xhzair/refresholdphone/
│       │   │   ├── MainActivity.kt
│       │   │   ├── MediaShellApplication.kt
│       │   │   ├── apps/AppCatalog.kt
│       │   │   ├── apps/InstalledAppSource.kt
│       │   │   ├── media/ExternalPlaybackBridge.kt
│       │   │   ├── media/MediaNotificationListener.kt
│       │   │   ├── media/PlaybackSnapshot.kt
│       │   │   ├── prefs/UserPreferences.kt
│       │   │   ├── system/AndroidSystemStatusSource.kt
│       │   │   ├── system/SystemActions.kt
│       │   │   ├── system/SystemStatus.kt
│       │   │   └── ui/
│       │   │       ├── HomeState.kt
│       │   │       ├── HomeViewModel.kt
│       │   │       ├── MediaAppAdapter.kt
│       │   │       └── SettingsActivity.kt
│       │   └── res/
│       │       ├── drawable/
│       │       ├── layout/activity_main.xml
│       │       ├── layout-land/activity_main.xml
│       │       ├── layout/item_media_app.xml
│       │       ├── layout/activity_settings.xml
│       │       ├── values/colors.xml
│       │       ├── values/dimens.xml
│       │       ├── values/strings.xml
│       │       └── values/themes.xml
│       ├── test/java/io/github/xhzair/refresholdphone/
│       │   ├── apps/AppCatalogTest.kt
│       │   ├── media/PlaybackSnapshotTest.kt
│       │   └── ui/HomeViewModelTest.kt
│       └── androidTest/java/io/github/xhzair/refresholdphone/MainActivityTest.kt
└── docs/device-testing-mix2s.md
```

## Task 1: Bootstrap a reproducible Android project

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/{strings,colors,dimens,themes}.xml`
- Create: `.gitignore`

- [ ] **Step 1: Install a repository-local Android SDK if the machine has none**

Use `..\.android-sdk` so no machine-wide Android Studio install is required. Download Google's Windows command-line tools archive `commandlinetools-win-15859902_latest.zip`, verify SHA-256 `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a`, place its contents under `..\.android-sdk\cmdline-tools\latest`, then run:

```powershell
$sdk = (Resolve-Path '..\.android-sdk').Path
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root=$sdk "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Accept only the Android SDK licenses printed by `sdkmanager`. Then create the untracked `local.properties` with `apply_patch`:

```properties
sdk.dir=C\:\\Users\\82562\\Downloads\\create_value\\.android-sdk
```

- [ ] **Step 2: Create Gradle settings and root plugin declaration**

```kotlin
// settings.gradle.kts
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "RefreshOldPhone"
include(":app")
```

```kotlin
// build.gradle.kts
plugins {
    id("com.android.application") version "9.4.1" apply false
}
```

- [ ] **Step 3: Create the app module**

```kotlin
// app/build.gradle.kts
plugins { id("com.android.application") }

android {
    namespace = "io.github.xhzair.refresholdphone"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.xhzair.refresholdphone"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { viewBinding = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
```

- [ ] **Step 4: Declare a reversible HOME activity and notification listener**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <queries>
        <package android:name="com.netease.cloudmusic" />
        <package android:name="com.tencent.qqmusic" />
        <package android:name="app.podcast.cosmos" />
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <application
        android:name=".MediaShellApplication"
        android:allowBackup="true"
        android:label="@string/app_name"
        android:theme="@style/Theme.RefreshOldPhone">
        <activity android:name=".ui.SettingsActivity" android:exported="false" />
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service
            android:name=".media.MediaNotificationListener"
            android:exported="true"
            android:label="@string/notification_access_label"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

- [ ] **Step 5: Generate and verify the wrapper**

Download/unpack Gradle 9.6.0 to a temporary directory and run `gradle.bat wrapper --gradle-version 9.6.0`; do not commit the downloaded distribution.

```powershell
.\gradlew.bat tasks
.\gradlew.bat testDebugUnitTest
```

Expected: Gradle configures successfully and the empty test task passes.

- [ ] **Step 6: Commit the project skeleton**

```powershell
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle app
git commit -m "build: bootstrap Android media shell"
```

## Task 2: Model and discover supported media apps

**Files:**
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/apps/InstalledAppSource.kt`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/apps/AppCatalog.kt`
- Test: `app/src/test/java/io/github/xhzair/refresholdphone/apps/AppCatalogTest.kt`

- [ ] **Step 1: Write the failing catalog tests**

```kotlin
class AppCatalogTest {
    private val source = FakeInstalledAppSource(setOf("com.netease.cloudmusic"))

    @Test fun `official apps have stable order and installed state`() {
        val apps = AppCatalog(source).load(emptySet())
        assertEquals(listOf("com.netease.cloudmusic", "com.tencent.qqmusic", "app.podcast.cosmos"), apps.map { it.packageName })
        assertTrue(apps.first().installed)
        assertFalse(apps[1].installed)
    }

    @Test fun `user-selected launchable apps follow official apps without duplicates`() {
        source.installed += "org.videolan.vlc"
        val apps = AppCatalog(source).load(setOf("org.videolan.vlc", "com.netease.cloudmusic"))
        assertEquals(4, apps.map { it.packageName }.distinct().size)
        assertEquals("org.videolan.vlc", apps.last().packageName)
    }
}

private class FakeInstalledAppSource(initial: Set<String>) : InstalledAppSource {
    val installed = initial.toMutableSet()
    override fun isLaunchable(packageName: String) = packageName in installed
    override fun label(packageName: String) = packageName.substringAfterLast('.')
    override fun launchablePackages() = installed.sorted()
}
```

- [ ] **Step 2: Run the test to prove it fails**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.AppCatalogTest"
```

Expected: compilation fails because the catalog types do not exist.

- [ ] **Step 3: Implement the catalog and Android package adapter**

```kotlin
data class MediaApp(
    val packageName: String,
    val label: String,
    val installed: Boolean,
    val official: Boolean,
)

interface MediaAppCatalog { fun load(selectedPackages: Set<String>): List<MediaApp> }

interface InstalledAppSource {
    fun isLaunchable(packageName: String): Boolean
    fun label(packageName: String): String
    fun launchablePackages(): List<String>
}

class AppCatalog(private val source: InstalledAppSource) : MediaAppCatalog {
    private val official = listOf(
        "com.netease.cloudmusic" to "网易云音乐",
        "com.tencent.qqmusic" to "QQ音乐",
        "app.podcast.cosmos" to "小宇宙",
    )

    override fun load(selectedPackages: Set<String>): List<MediaApp> {
        val officialNames = official.map { it.first }.toSet()
        val builtIns = official.map { (pkg, fallback) ->
            MediaApp(pkg, if (source.isLaunchable(pkg)) source.label(pkg) else fallback, source.isLaunchable(pkg), true)
        }
        val extras = selectedPackages.asSequence()
            .filterNot(officialNames::contains)
            .filter(source::isLaunchable)
            .sorted()
            .map { MediaApp(it, source.label(it), true, false) }
            .toList()
        return builtIns + extras
    }
}
```

Implement `AndroidInstalledAppSource` with `PackageManager.getLaunchIntentForPackage`, `resolveActivity`, and application labels. Do not enumerate packages except on the explicit app-selection screen.

- [ ] **Step 4: Run tests and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.AppCatalogTest"
git add app/src/main/java/io/github/xhzair/refresholdphone/apps app/src/test/java/io/github/xhzair/refresholdphone/apps
git commit -m "feat: add media app catalog"
```

Expected: both catalog tests pass.

## Task 3: Add safe system actions, preferences, and status

**Files:**
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/prefs/UserPreferences.kt`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/system/SystemActions.kt`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/system/SystemStatus.kt`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/system/AndroidSystemStatusSource.kt`

- [ ] **Step 1: Define typed preferences**

```kotlin
enum class OrientationMode { AUTO, PORTRAIT, LANDSCAPE }

class UserPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("media_shell", Context.MODE_PRIVATE)
    var orientationMode: OrientationMode
        get() = runCatching { OrientationMode.valueOf(prefs.getString("orientation", "AUTO")!!) }.getOrDefault(OrientationMode.AUTO)
        set(value) { prefs.edit().putString("orientation", value.name).apply() }
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(value) { prefs.edit().putBoolean("keep_screen_on", value).apply() }
    fun selectedPackages(): Set<String> = prefs.getStringSet("selected_apps", emptySet()).orEmpty().toSet()
    fun setSelectedPackages(value: Set<String>) { prefs.edit().putStringSet("selected_apps", value).apply() }
}
```

- [ ] **Step 2: Implement only public settings/navigation intents**

`SystemActions` must provide:

```kotlin
fun launchPackage(packageName: String): Boolean
fun openAppDetails(packageName: String)
fun openWifiSettings()
fun openMobileNetworkSettings()
fun openBluetoothSettings()
fun openVolumeSettings()
fun openNotificationAccessSettings()
fun openHomeChooser()
fun openSystemSettings()
fun openSelfAppDetails()
```

Every action uses an explicit Android Settings action or `PackageManager` launch intent, adds `FLAG_ACTIVITY_NEW_TASK` when necessary, catches `ActivityNotFoundException`, and falls back to `Settings.ACTION_SETTINGS`. `openHomeChooser()` first tries `Settings.ACTION_HOME_SETTINGS` and then the general settings fallback. Never toggle Wi-Fi, mobile data, or Bluetooth directly.

- [ ] **Step 3: Define observable system status**

```kotlin
data class SystemStatus(
    val batteryPercent: Int = 0,
    val charging: Boolean = false,
    val networkLabel: String = "离线",
)

interface SystemStatusSource {
    fun start(onStatus: (SystemStatus) -> Unit)
    fun stop()
}
```

`AndroidSystemStatusSource` registers a battery receiver and a network callback while active. For API 23 it uses `ConnectivityManager.activeNetworkInfo`; on newer APIs it uses `NetworkCapabilities`. It emits only `Wi‑Fi`, `移动网络`, `以太网`, or `离线` and never requests location permission or SSID access.

- [ ] **Step 4: Build and commit**

```powershell
.\gradlew.bat testDebugUnitTest
git add app/src/main/java/io/github/xhzair/refresholdphone/prefs app/src/main/java/io/github/xhzair/refresholdphone/system
git commit -m "feat: add preferences and safe system actions"
```

## Task 4: Build the optional external playback bridge

**Files:**
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/media/PlaybackSnapshot.kt`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/media/ExternalPlaybackBridge.kt`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/media/MediaNotificationListener.kt`
- Test: `app/src/test/java/io/github/xhzair/refresholdphone/media/PlaybackSnapshotTest.kt`

- [ ] **Step 1: Test metadata sanitization and capability mapping**

Write tests that assert blank titles fall back to the provider label, negative durations/positions become zero, position does not exceed a known duration, and transport controls reflect `PlaybackState.actions` instead of being assumed.

```kotlin
@Test fun `mapper clamps invalid timing and uses fallback title`() {
    val mapped = PlaybackSnapshotMapper.map(
        packageName = "com.netease.cloudmusic",
        providerLabel = "网易云音乐",
        title = " ", artist = null, durationMs = -1, positionMs = -4,
        playing = false, actions = 0,
    )
    assertEquals("网易云音乐", mapped.title)
    assertEquals(0, mapped.durationMs)
    assertEquals(0, mapped.positionMs)
    assertFalse(mapped.controls.canPlayPause)
}
```

- [ ] **Step 2: Run the test to prove it fails**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.PlaybackSnapshotTest"
```

- [ ] **Step 3: Implement the pure playback model**

```kotlin
data class PlaybackControls(
    val canPlayPause: Boolean = false,
    val canSkipPrevious: Boolean = false,
    val canSkipNext: Boolean = false,
)

data class PlaybackSnapshot(
    val packageName: String,
    val providerLabel: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val controls: PlaybackControls,
    val artwork: Bitmap? = null,
)

interface ExternalPlaybackBridge {
    fun start(onPlaybackChanged: (PlaybackSnapshot?) -> Unit, onAccessChanged: (Boolean) -> Unit)
    fun stop()
    fun playPause()
    fun skipPrevious()
    fun skipNext()
}
```

Keep `PlaybackSnapshotMapper` Android-free by accepting primitive values and action bits as arguments.

- [ ] **Step 4: Implement the Android bridge**

`MediaNotificationListener` publishes connected/disconnected state through a process-local singleton. `AndroidExternalPlaybackBridge` obtains `MediaSessionManager`, calls `getActiveSessions` only when notification-listener access is granted, listens for active-session changes, and chooses sessions in this order:

1. A session whose playback state is `STATE_PLAYING`.
2. The most recently active supported-provider session.
3. Any active media session.

Map `MediaMetadata` title, artist, album art, duration, and `PlaybackState` position/actions. Transport buttons call only the selected session's `MediaController.TransportControls`. Catch `SecurityException` and emit `onAccessChanged(false)` instead of crashing. Scale copied artwork to a maximum of 512 px before storing it in UI state.

- [ ] **Step 5: Run tests and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.PlaybackSnapshotTest"
git add app/src/main/java/io/github/xhzair/refresholdphone/media app/src/test/java/io/github/xhzair/refresholdphone/media
git commit -m "feat: bridge external media sessions"
```

## Task 5: Create lifecycle-safe home state

**Files:**
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/ui/HomeState.kt`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/ui/HomeViewModel.kt`
- Test: `app/src/test/java/io/github/xhzair/refresholdphone/ui/HomeViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Cover these transitions:

- `start()` loads provider apps and begins status/playback observation once.
- Playback callback updates only the playback portion of state.
- Notification-access callback controls the permission prompt.
- `stop()` unregisters both sources.

Use explicit fakes implementing `MediaAppCatalog`, `ExternalPlaybackBridge`, and `SystemStatusSource`; each fake records `start`/`stop` call counts and stores callbacks so the test can emit state synchronously.

- [ ] **Step 2: Prove the tests fail**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.HomeViewModelTest"
```

- [ ] **Step 3: Implement state and reducer-style updates**

```kotlin
data class HomeState(
    val apps: List<MediaApp> = emptyList(),
    val playback: PlaybackSnapshot? = null,
    val systemStatus: SystemStatus = SystemStatus(),
    val notificationAccessGranted: Boolean = false,
)

class HomeViewModel(
    private val catalog: MediaAppCatalog,
    private val selectedPackages: () -> Set<String>,
    private val playbackBridge: ExternalPlaybackBridge,
    private val statusSource: SystemStatusSource,
) : ViewModel() {
    private val _state = MutableLiveData(HomeState())
    val state: LiveData<HomeState> = _state
    private var started = false

    fun start() {
        if (started) return
        started = true
        _state.value = _state.value!!.copy(apps = catalog.load(selectedPackages()))
        playbackBridge.start(
            onPlaybackChanged = { value -> _state.postValue(_state.value!!.copy(playback = value)) },
            onAccessChanged = { granted -> _state.postValue(_state.value!!.copy(notificationAccessGranted = granted)) },
        )
        statusSource.start { value -> _state.postValue(_state.value!!.copy(systemStatus = value)) }
    }

    fun stop() {
        if (!started) return
        started = false
        playbackBridge.stop()
        statusSource.stop()
    }
}
```

In production, inject `UserPreferences::selectedPackages`. Add factory construction in `MediaShellApplication`; do not introduce a dependency-injection framework.

- [ ] **Step 4: Run tests and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests "*.HomeViewModelTest"
git add app/src/main/java/io/github/xhzair/refresholdphone/ui app/src/test/java/io/github/xhzair/refresholdphone/ui
git commit -m "feat: add lifecycle-safe home state"
```

## Task 6: Build portrait playback-first and landscape clock-first screens

**Files:**
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/layout-land/activity_main.xml`
- Create: `app/src/main/res/layout/item_media_app.xml`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/ui/MediaAppAdapter.kt`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/MainActivity.kt`
- Modify: `app/src/main/res/values/{strings,colors,dimens,themes}.xml`

- [ ] **Step 1: Implement shared visual tokens**

Use an opaque near-black background (`#0B0D0F`), warm white primary text (`#F4F1EA`), muted secondary text (`#A7ABB0`), and one restrained accent (`#D7A84B`). Minimum interactive target is 56 dp, body text is at least 18 sp, and the clock uses tabular numerals. No gradients, glass effects, auto-playing animation, ads, recommendations, or dense navigation chrome.

- [ ] **Step 2: Build portrait playback-first XML**

The root is a `ConstraintLayout` with these stable IDs: `clockSmall`, `dateText`, `systemStatus`, `artwork`, `trackTitle`, `trackArtist`, `position`, `previous`, `playPause`, `next`, `permissionPrompt`, `appList`, and `settings`. The now-playing block occupies the upper half; the horizontal provider list occupies the lower half. Empty playback shows “打开一个音乐或播客应用开始播放” and hides transport buttons that the active session does not support.

- [ ] **Step 3: Build landscape clock-first XML**

Use the same IDs so `MainActivity` has no orientation branch for binding. `clockSmall` becomes a large centered `TextClock` (`HH:mm`, 120–180 sp according to available height), with `dateText` and a locally bundled, user-hideable quiet phrase below it. Current title/artist and compact controls sit to the right; app cards form a narrow bottom row. With no playback, that area shows the last successfully launched media app from local preferences, never network recommendations. Prevent text overlap at the MIX 2S logical resolution by using constraints and `0dp` match-constraint dimensions, not hard-coded pixel widths.

- [ ] **Step 4: Bind state and actions**

`MainActivity`:

- observes `HomeViewModel.state`;
- submits app cards to `MediaAppAdapter`;
- launches installed apps, otherwise opens app details/help without linking to unofficial APKs;
- binds play/pause/previous/next to `ExternalPlaybackBridge`;
- opens notification access only after the user taps the visible prompt;
- applies `FLAG_KEEP_SCREEN_ON` only when the stored preference is enabled;
- applies `OrientationMode` through `requestedOrientation`, with `AUTO` using `SCREEN_ORIENTATION_UNSPECIFIED`;
- calls `viewModel.start()` in `onStart` and `viewModel.stop()` in `onStop`.

- [ ] **Step 5: Compile, install, and inspect both orientations**

```powershell
.\gradlew.bat assembleDebug
..\.tools\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
..\.tools\platform-tools\adb.exe shell am start -n io.github.xhzair.refresholdphone/.MainActivity
..\.tools\platform-tools\adb.exe shell settings put system accelerometer_rotation 0
..\.tools\platform-tools\adb.exe shell settings put system user_rotation 0
..\.tools\platform-tools\adb.exe shell settings put system user_rotation 1
..\.tools\platform-tools\adb.exe shell settings put system accelerometer_rotation 1
```

Expected: portrait emphasizes playback; landscape emphasizes the clock; restoring `accelerometer_rotation` leaves the user's device rotation enabled.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/io/github/xhzair/refresholdphone/MainActivity.kt app/src/main/java/io/github/xhzair/refresholdphone/ui/MediaAppAdapter.kt app/src/main/res
git commit -m "feat: add adaptive media home screen"
```

## Task 7: Add settings, app selection, and recovery

**Files:**
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/ui/SettingsActivity.kt`
- Create: `app/src/main/res/layout/activity_settings.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Build a single-page settings screen**

Include only:

- Orientation: automatic / force portrait / force landscape.
- Keep screen awake: off by default, with burn-in/power warning.
- Additional apps: explicit multi-select from launchable user apps.
- Notification access status and shortcut.
- Wi-Fi, mobile network, Bluetooth, volume, app details, and general system settings shortcuts.
- “选择默认桌面” and visually prominent “恢复系统桌面” actions.
- “清除本地配置” with a confirmation dialog; it clears only this app's SharedPreferences and does not touch provider apps.
- App version and GitHub repository link.

Persist only package names and UI preferences. Never store provider account data, notification contents, listening history, or artwork.

- [ ] **Step 2: Make recovery paths resilient**

The recovery section remains usable if notification access is denied and if all provider apps are absent. “恢复系统桌面” opens Android HOME settings and explains: select “系统桌面/MIUI 桌面”; if the chooser is unavailable, open general Apps settings and clear this app's defaults. Do not automate removal of defaults.

- [ ] **Step 3: Build and commit**

```powershell
.\gradlew.bat assembleDebug
git add app/src/main/java/io/github/xhzair/refresholdphone/ui/SettingsActivity.kt app/src/main/res/layout/activity_settings.xml app/src/main/res/values/strings.xml
git commit -m "feat: add settings and launcher recovery"
```

## Task 8: Add instrumentation coverage for critical user paths

**Files:**
- Create: `app/src/androidTest/java/io/github/xhzair/refresholdphone/MainActivityTest.kt`

- [ ] **Step 1: Add UI tests with injected fakes**

Expose a debug/test-only dependency override in `MediaShellApplication`. Tests must verify:

- Home renders official providers in stable order.
- Missing QQ Music is labelled unavailable rather than throwing.
- A playback snapshot renders title/artist and capability-dependent buttons.
- Notification prompt opens only on click.
- Settings contains the HOME recovery action.
- System setting intents use their documented action and fall back to general settings when unresolvable.

- [ ] **Step 2: Run instrumentation tests on the connected MIX 2S**

```powershell
..\.tools\platform-tools\adb.exe devices -l
.\gradlew.bat connectedDebugAndroidTest
```

Expected: exactly one authorized `polaris` device is listed and all tests pass.

- [ ] **Step 3: Commit**

```powershell
git add app/src/androidTest app/src/main/java/io/github/xhzair/refresholdphone/MediaShellApplication.kt
git commit -m "test: cover critical launcher flows"
```

## Task 9: Validate on Xiaomi MIX 2S without taking irreversible control

**Files:**
- Create: `docs/device-testing-mix2s.md`

- [ ] **Step 1: Capture a baseline**

```powershell
..\.tools\platform-tools\adb.exe shell getprop ro.product.model
..\.tools\platform-tools\adb.exe shell getprop ro.build.version.release
..\.tools\platform-tools\adb.exe shell getprop ro.build.version.sdk
..\.tools\platform-tools\adb.exe shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Record model, Android/API version, and current HOME package in the document. Never call `pm disable-user`, `pm uninstall`, device-owner commands, or privileged settings grants.

- [ ] **Step 2: Run the manual acceptance matrix**

Document pass/fail evidence for:

1. Launch as a normal app before selecting it as HOME.
2. NetEase card launches installed `com.netease.cloudmusic`.
3. QQ Music and Xiaoyuzhou missing states are clear and non-blocking.
4. Wi-Fi and mobile data continue working inside provider apps.
5. Grant notification access manually and verify metadata/control if NetEase exposes a session.
6. Revoke notification access and verify graceful degradation.
7. Verify Wi-Fi, mobile network, Bluetooth, volume, app details, and general settings shortcuts.
8. Lock/unlock, process recreation, portrait, landscape, and dark room readability.
9. Press HOME, temporarily select this app, then recover MIUI Launcher through settings.
10. Clear local configuration and verify provider app data remains untouched.
11. Reboot only with the user's explicit approval; otherwise record reboot persistence as untested.

- [ ] **Step 3: Check basic resource behavior**

```powershell
..\.tools\platform-tools\adb.exe shell dumpsys meminfo io.github.xhzair.refresholdphone
..\.tools\platform-tools\adb.exe shell dumpsys batterystats io.github.xhzair.refresholdphone
..\.tools\platform-tools\adb.exe logcat -d -t 500 AndroidRuntime:E RefreshOldPhone:V *:S
```

Record foreground PSS, whether any crash appears, and whether callbacks stop after leaving HOME. Treat performance numbers as device observations, not universal benchmarks.

- [ ] **Step 4: Commit device notes**

```powershell
git add docs/device-testing-mix2s.md
git commit -m "docs: record MIX 2S acceptance results"
```

## Task 10: Finish documentation and release verification

**Files:**
- Modify: `README.md`
- Create: `docs/privacy.md`
- Create: `docs/release-checklist.md`

- [ ] **Step 1: Document installation and limits**

README must explain Android 6+, no root, existing provider apps, optional notification access, optional HOME selection, portrait/landscape behavior, build commands, debug APK location, recovery procedure, and the deferred local music/RSS/radio features. Privacy documentation states that v0.1 has no analytics, ads, provider credentials, server, or notification-content persistence.

- [ ] **Step 2: Run the complete verification suite**

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest
..\.android-sdk\build-tools\36.0.0\aapt2.exe dump badging app\build\outputs\apk\debug\app-debug.apk
..\.tools\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
git status --short
```

Expected:

- Unit tests, lint, assembly, and device tests pass.
- APK reports `minSdkVersion:'23'` and the expected package.
- Installation succeeds on the MIX 2S.
- Working tree contains only intentional documentation/result changes.

- [ ] **Step 3: Perform a secrets and placeholder scan**

```powershell
rg -n "(api[_-]?key|client[_-]?secret|password|TODO|FIXME|TBD)" . -g "!*.lock" -g "!docs/superpowers/plans/*"
```

Expected: no credentials and no release-blocking placeholders.

- [ ] **Step 4: Commit the release-ready MVP**

```powershell
git add README.md docs/privacy.md docs/release-checklist.md
git commit -m "docs: add setup privacy and release guidance"
```

Do not push, publish an APK, change the phone's default HOME permanently, or create a GitHub release without a separate explicit user request.
