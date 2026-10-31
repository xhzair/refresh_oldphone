# 本地音乐与蓝牙音乐播放源 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在媒体桌面中新增内置本地音乐播放器与蓝牙音乐源：扫描 MediaStore、前台服务播放、MediaSession 接入现有卡片/单源互斥，蓝牙源显示 A2DP 设备名并复用系统输出与 AVRCP。

**Architecture:** 新增 `local/` 包承载 MediaStore 查询、队列、MediaPlayer/MediaSession 前台服务、蓝牙输出检测；新增 `LocalMusicActivity` 浏览并播放本地歌曲。桌面继续通过 `ExternalPlaybackBridge` 读取本应用 MediaSession，单源互斥把本应用包名纳入白名单；MediaSourceSelection 扩展出 `LOCAL_MUSIC` / `BLUETOOTH_MUSIC` 两个内置源。

**Tech Stack:** Kotlin、Android Views/XML、平台 `MediaPlayer`、平台 `MediaSession`/`MediaStyle` 通知、`MediaStore`、`AudioManager`（A2DP 输出检测）、AppCompat/RecyclerView、JUnit4/Espresso。无新增依赖、无网络。

---

## 前置约束与命令

- 工作目录：`C:\Users\82562\Downloads\create_value\refresh_oldphone`
- 离线构建（每个任务重复使用）：
  ```powershell
  $env:ANDROID_USER_HOME='C:\Users\82562\Downloads\create_value\.android-user-home'
  & "C:\Users\82562\Downloads\create_value\gradle-9.6.0\bin\gradle.bat" `
    -g "C:\Users\82562\Downloads\create_value\.gradle-wrapper-home-3" --offline `
    testDebugUnitTest assembleDebug lintDebug --console=plain
  ```
- 仪器化：`assembleDebugAndroidTest` 后 `adb install -r` 两个 APK；MIUI 需要预先
  `adb shell appops set io.github.xhzair.refresholdphone 10021 allow`，执行
  `adb shell am instrument -w -r io.github.xhzair.refresholdphone.test/androidx.test.runner.AndroidJUnitRunner`。
- 不要新增第三方依赖；不要使用 Media3（当前离线缓存没有）。
- 所有提交在 `main` 上进行，提交信息使用 `feat:` / `test:` / `docs:` 前缀。

---

## 文件结构

新增：

- `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalTrack.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalLibrary.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalPlaybackQueue.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/local/BluetoothOutput.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalArtwork.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalNowPlaying.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalPlaybackContract.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalPlaybackService.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/ui/LocalMusicActivity.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/ui/LocalTrackAdapter.kt`
- `app/src/main/res/layout/activity_local_music.xml`
- `app/src/main/res/layout/item_local_track.xml`
- `app/src/main/res/drawable/ic_music_note.xml`
- `app/src/main/res/drawable/ic_bluetooth.xml`
- `app/src/test/java/io/github/xhzair/refresholdphone/local/LocalLibraryTest.kt`
- `app/src/test/java/io/github/xhzair/refresholdphone/local/LocalPlaybackQueueTest.kt`
- `app/src/test/java/io/github/xhzair/refresholdphone/ui/MediaSourceSelectionTest.kt`（更新）

修改：

- `app/src/main/java/io/github/xhzair/refresholdphone/ui/MediaSourceSelection.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/MainActivity.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/prefs/UserPreferences.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/media/ExternalPlaybackBridge.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/androidTest/java/io/github/xhzair/refresholdphone/MainActivityTest.kt`
- `app/src/test/java/io/github/xhzair/refresholdphone/media/PlaybackSnapshotTest.kt`
- `README.md`
- `docs/device-testing-mix2s.md`
- `docs/release-checklist.md`

---

### Task 1: 内置源模型与选择策略（纯逻辑）

**Files:**
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/local/BluetoothOutput.kt`
- Modify: `app/src/main/java/io/github/xhzair/refresholdphone/ui/MediaSourceSelection.kt`（整体替换）
- Test: `app/src/test/java/io/github/xhzair/refresholdphone/ui/MediaSourceSelectionTest.kt`（整体替换）

- [ ] **Step 1: 写失败测试**

替换 `MediaSourceSelectionTest.kt`：

```kotlin
package io.github.xhzair.refresholdphone.ui

import io.github.xhzair.refresholdphone.apps.MediaApp
import io.github.xhzair.refresholdphone.local.BluetoothOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSourceSelectionTest {
    private val netease = MediaApp("com.netease.cloudmusic", "网易云音乐", installed = true, official = true)
    private val qqMusic = MediaApp("com.tencent.qqmusic", "QQ音乐", installed = false, official = true)
    private val vlc = MediaApp("org.videolan.vlc", "VLC", installed = true, official = false)

    @Test
    fun `builtins appear after official apps and before extras`() {
        val entries = MediaSourceSelection.entries(
            apps = listOf(netease, qqMusic, vlc),
            bluetooth = BluetoothOutput(false, null),
            localLabel = "本地音乐",
            bluetoothLabel = "蓝牙音乐",
            disconnectedLabel = "未连接",
        )
        assertEquals(
            listOf(
                "app:com.netease.cloudmusic",
                MediaSourceSelection.ID_LOCAL,
                MediaSourceSelection.ID_BLUETOOTH,
                "app:org.videolan.vlc",
            ),
            entries.map { it.id },
        )
        assertEquals(MediaSourceKind.LOCAL_MUSIC, entries[1].kind)
        assertEquals(MediaSourceKind.BLUETOOTH_MUSIC, entries[2].kind)
    }

    @Test
    fun `bluetooth connected entry shows device name and is available`() {
        val entry = MediaSourceSelection.entries(
            apps = emptyList(),
            bluetooth = BluetoothOutput(true, "WH-1000XM3"),
            localLabel = "本地音乐",
            bluetoothLabel = "蓝牙音乐",
            disconnectedLabel = "未连接",
        ).first { it.id == MediaSourceSelection.ID_BLUETOOTH }
        assertEquals("蓝牙音乐 · WH-1000XM3", entry.label)
        assertEquals(true, entry.available)
    }

    @Test
    fun `bluetooth disconnected entry stays visible but unavailable`() {
        val entry = MediaSourceSelection.entries(
            apps = emptyList(),
            bluetooth = BluetoothOutput(false, null),
            localLabel = "本地音乐",
            bluetoothLabel = "蓝牙音乐",
            disconnectedLabel = "未连接",
        ).first { it.id == MediaSourceSelection.ID_BLUETOOTH }
        assertEquals("蓝牙音乐", entry.label)
        assertEquals("未连接", entry.sublabel)
        assertEquals(false, entry.available)
    }

    @Test
    fun `current entry matches last source id and falls back to first available`() {
        val entries = MediaSourceSelection.entries(
            apps = listOf(netease, vlc),
            bluetooth = BluetoothOutput(false, null),
            localLabel = "本地音乐",
            bluetoothLabel = "蓝牙音乐",
            disconnectedLabel = "未连接",
        )
        assertEquals(MediaSourceSelection.ID_LOCAL, MediaSourceSelection.currentEntry(entries, "local")?.id)
        assertEquals("app:com.netease.cloudmusic", MediaSourceSelection.currentEntry(entries, null)?.id)
        assertEquals("app:org.videolan.vlc", MediaSourceSelection.currentEntry(entries, "app:org.videolan.vlc")?.id)
    }

    @Test
    fun `legacy package maps to app source id`() {
        assertEquals("app:com.tencent.qqmusic", MediaSourceSelection.initialSourceId(null, "com.tencent.qqmusic"))
        assertEquals("local", MediaSourceSelection.initialSourceId("local", "com.tencent.qqmusic"))
        assertNull(MediaSourceSelection.initialSourceId(null, null))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run：
```powershell
$env:ANDROID_USER_HOME='C:\Users\82562\Downloads\create_value\.android-user-home'
& "C:\Users\82562\Downloads\create_value\gradle-9.6.0\bin\gradle.bat" -g "C:\Users\82562\Downloads\create_value\.gradle-wrapper-home-3" --offline testDebugUnitTest --tests "io.github.xhzair.refresholdphone.ui.MediaSourceSelectionTest" --console=plain
```
Expected: 编译失败，提示找不到 `MediaSourceSelection.entries` / `BluetoothOutput`。

- [ ] **Step 3: 实现 `BluetoothOutput.kt`**

```kotlin
package io.github.xhzair.refresholdphone.local

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

data class BluetoothOutput(
    val connected: Boolean,
    val deviceName: String?,
)

object BluetoothOutputSource {
    fun current(context: Context): BluetoothOutput {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return BluetoothOutput(false, null)
        val device = try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        } catch (_: SecurityException) {
            null
        }
        return if (device == null) {
            BluetoothOutput(false, null)
        } else {
            BluetoothOutput(true, device.productName?.toString()?.takeIf(String::isNotBlank))
        }
    }
}
```

- [ ] **Step 4: 实现 `MediaSourceSelection.kt`**

```kotlin
package io.github.xhzair.refresholdphone.ui

import io.github.xhzair.refresholdphone.apps.MediaApp
import io.github.xhzair.refresholdphone.local.BluetoothOutput

enum class MediaSourceKind { APP, LOCAL_MUSIC, BLUETOOTH_MUSIC }

data class MediaSourceEntry(
    val id: String,
    val kind: MediaSourceKind,
    val label: String,
    val sublabel: String?,
    val available: Boolean,
    val official: Boolean,
    val packageName: String?,
)

object MediaSourceSelection {
    const val ID_LOCAL = "local"
    const val ID_BLUETOOTH = "bluetooth"

    fun appId(packageName: String): String = "app:$packageName"

    fun initialSourceId(lastSourceId: String?, legacyPackage: String?): String? =
        lastSourceId ?: legacyPackage?.let(::appId)

    fun entries(
        apps: List<MediaApp>,
        bluetooth: BluetoothOutput,
        localLabel: String,
        bluetoothLabel: String,
        disconnectedLabel: String,
    ): List<MediaSourceEntry> {
        val installed = apps.filter { it.installed }
        val appEntries = installed.map { app ->
            MediaSourceEntry(
                id = appId(app.packageName),
                kind = MediaSourceKind.APP,
                label = app.label,
                sublabel = null,
                available = true,
                official = app.official,
                packageName = app.packageName,
            )
        }
        val local = MediaSourceEntry(
            id = ID_LOCAL,
            kind = MediaSourceKind.LOCAL_MUSIC,
            label = localLabel,
            sublabel = null,
            available = true,
            official = false,
            packageName = null,
        )
        val bluetoothName = bluetooth.deviceName?.takeIf(String::isNotBlank)
        val bluetoothEntry = MediaSourceEntry(
            id = ID_BLUETOOTH,
            kind = MediaSourceKind.BLUETOOTH_MUSIC,
            label = if (bluetooth.connected && bluetoothName != null) {
                "$bluetoothLabel · $bluetoothName"
            } else {
                bluetoothLabel
            },
            sublabel = if (bluetooth.connected) null else disconnectedLabel,
            available = bluetooth.connected,
            official = false,
            packageName = null,
        )
        return appEntries.filter { it.official } + local + bluetoothEntry + appEntries.filterNot { it.official }
    }

    fun currentEntry(entries: List<MediaSourceEntry>, lastSourceId: String?): MediaSourceEntry? =
        entries.firstOrNull { it.id == lastSourceId }
            ?: entries.firstOrNull { it.available }
            ?: entries.firstOrNull()

    // 兼容 Task 6 之前的 MainActivity；Task 6 完成后可删除。
    fun availableSources(apps: List<MediaApp>): List<MediaApp> = apps.filter { it.installed }

    fun currentSource(apps: List<MediaApp>, lastLaunchedPackage: String?): MediaApp? {
        val installed = availableSources(apps)
        return installed.firstOrNull { it.packageName == lastLaunchedPackage } ?: installed.firstOrNull()
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run：同 Step 2。Expected: `MediaSourceSelectionTest` 全部通过。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/io/github/xhzair/refresholdphone/local/BluetoothOutput.kt app/src/main/java/io/github/xhzair/refresholdphone/ui/MediaSourceSelection.kt app/src/test/java/io/github/xhzair/refresholdphone/ui/MediaSourceSelectionTest.kt
git commit -m "feat: add local and Bluetooth media source model"
```

---

### Task 2: 本地音乐数据模型、扫描与排序

**Files:**
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalTrack.kt`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalLibrary.kt`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalArtwork.kt`
- Test: `app/src/test/java/io/github/xhzair/refresholdphone/local/LocalLibraryTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package io.github.xhzair.refresholdphone.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLibraryTest {
    private fun track(id: Long, title: String, artist: String = "A", duration: Long = 1000) =
        LocalTrack(id, title, artist, "Album", duration)

    @Test
    fun `drops zero duration and blank titles`() {
        val cleaned = LocalLibrary.sanitize(
            listOf(
                track(1, "Keep"),
                track(2, ""),
                track(3, "Zero", duration = 0),
                track(4, "  "),
            ),
        )
        assertEquals(listOf(1L), cleaned.map { it.id })
    }

    @Test
    fun `deduplicates by id and sorts title then artist`() {
        val cleaned = LocalLibrary.sanitize(
            listOf(
                track(1, "beta", "Z"),
                track(2, "Alpha", "B"),
                track(2, "Alpha duplicate", "A"),
                track(3, "beta", "A"),
            ),
        )
        assertEquals(listOf(2L, 3L, 1L), cleaned.map { it.id })
    }

    @Test
    fun `empty library stays empty`() {
        assertEquals(emptyList<LocalTrack>(), LocalLibrary.sanitize(emptyList()))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run：
```powershell
$env:ANDROID_USER_HOME='C:\Users\82562\Downloads\create_value\.android-user-home'
& "C:\Users\82562\Downloads\create_value\gradle-9.6.0\bin\gradle.bat" -g "C:\Users\82562\Downloads\create_value\.gradle-wrapper-home-3" --offline testDebugUnitTest --tests "io.github.xhzair.refresholdphone.local.LocalLibraryTest" --console=plain
```
Expected: 找不到 `LocalTrack` / `LocalLibrary.sanitize`。

- [ ] **Step 3: 实现数据模型与纯排序**

`LocalTrack.kt`：

```kotlin
package io.github.xhzair.refresholdphone.local

data class LocalTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)
```

`LocalLibrary.kt`：

```kotlin
package io.github.xhzair.refresholdphone.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

object LocalLibrary {
    private const val UNKNOWN_TITLE = "未知歌曲"
    private const val UNKNOWN_ARTIST = "未知歌手"
    private const val UNKNOWN_ALBUM = "未知专辑"

    internal fun sanitize(tracks: List<LocalTrack>): List<LocalTrack> =
        tracks.asSequence()
            .filter { it.durationMs > 0 && it.title.isNotBlank() }
            .distinctBy(LocalTrack::id)
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist },
            )
            .toList()

    fun query(context: Context): List<LocalTrack> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        val result = mutableListOf<LocalTrack>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                result += LocalTrack(
                    id = id,
                    title = cursor.getString(titleColumn)?.trim().orEmpty().ifBlank { UNKNOWN_TITLE },
                    artist = cursor.getString(artistColumn)?.trim().orEmpty().ifBlank { UNKNOWN_ARTIST },
                    album = cursor.getString(albumColumn)?.trim().orEmpty().ifBlank { UNKNOWN_ALBUM },
                    durationMs = cursor.getLong(durationColumn),
                )
            }
        }
        return sanitize(result)
    }

    fun loadByIds(context: Context, ids: LongArray): List<LocalTrack> {
        val byId = query(context).associateBy(LocalTrack::id)
        return ids.mapNotNull(byId::get)
    }

    fun contentUri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}
```

- [ ] **Step 4: 实现封面加载器**

`LocalArtwork.kt`：

```kotlin
package io.github.xhzair.refresholdphone.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size

object LocalArtwork {
    private val cache = object : LruCache<Long, Bitmap>(32) {}

    fun cached(id: Long): Bitmap? = cache.get(id)

    fun load(context: Context, track: LocalTrack): Bitmap? {
        cache.get(track.id)?.let { return it }
        val uri = LocalLibrary.contentUri(track.id)
        val bitmap = embeddedPicture(context, uri) ?: thumbnail(context, uri)
        if (bitmap != null) cache.put(track.id, bitmap)
        return bitmap
    }

    private fun embeddedPicture(context: Context, uri: android.net.Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun thumbnail(context: Context, uri: android.net.Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
        } catch (_: Exception) {
            null
        }
    }
}
```

- [ ] **Step 5: 运行单元测试**

Run：
```powershell
$env:ANDROID_USER_HOME='C:\Users\82562\Downloads\create_value\.android-user-home'
& "C:\Users\82562\Downloads\create_value\gradle-9.6.0\bin\gradle.bat" -g "C:\Users\82562\Downloads\create_value\.gradle-wrapper-home-3" --offline testDebugUnitTest --tests "io.github.xhzair.refresholdphone.local.LocalLibraryTest" --console=plain
```
Expected: PASS。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/io/github/xhzair/refresholdphone/local app/src/test/java/io/github/xhzair/refresholdphone/local
git commit -m "feat: scan and sort local audio library"
```

---

### Task 3: 播放队列纯逻辑

**Files:**
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalPlaybackQueue.kt`
- Test: `app/src/test/java/io/github/xhzair/refresholdphone/local/LocalPlaybackQueueTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package io.github.xhzair.refresholdphone.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPlaybackQueueTest {
    private val tracks = (1L..3L).map { LocalTrack(it, "Track $it", "A", "B", 1000) }

    @Test
    fun `starts at the requested index`() {
        assertEquals(2L, LocalPlaybackQueue(tracks, 1).current?.id)
    }

    @Test
    fun `next stops at the last track`() {
        val queue = LocalPlaybackQueue(tracks, 1)
        assertEquals(3L, queue.moveNext()?.id)
        assertNull(queue.moveNext())
        assertEquals(3L, queue.current?.id)
    }

    @Test
    fun `previous stops at the first track`() {
        val queue = LocalPlaybackQueue(tracks, 1)
        assertEquals(1L, queue.movePrevious()?.id)
        assertNull(queue.movePrevious())
        assertEquals(1L, queue.current?.id)
    }

    @Test
    fun `empty queue has no current track`() {
        val queue = LocalPlaybackQueue(emptyList())
        assertNull(queue.current)
        assertNull(queue.moveNext())
        assertNull(queue.movePrevious())
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run：
```powershell
$env:ANDROID_USER_HOME='C:\Users\82562\Downloads\create_value\.android-user-home'
& "C:\Users\82562\Downloads\create_value\gradle-9.6.0\bin\gradle.bat" -g "C:\Users\82562\Downloads\create_value\.gradle-wrapper-home-3" --offline testDebugUnitTest --tests "io.github.xhzair.refresholdphone.local.LocalPlaybackQueueTest" --console=plain
```
Expected: 找不到 `LocalPlaybackQueue`。

- [ ] **Step 3: 实现队列**

```kotlin
package io.github.xhzair.refresholdphone.local

class LocalPlaybackQueue(
    private val tracks: List<LocalTrack>,
    startIndex: Int = 0,
) {
    var index: Int = if (tracks.isEmpty()) -1 else startIndex.coerceIn(0, tracks.lastIndex)
        private set

    val current: LocalTrack? get() = tracks.getOrNull(index)
    val hasNext: Boolean get() = index in 0 until tracks.lastIndex
    val hasPrevious: Boolean get() = index > 0
    val all: List<LocalTrack> get() = tracks

    fun moveNext(): LocalTrack? {
        if (!hasNext) return null
        index += 1
        return current
    }

    fun movePrevious(): LocalTrack? {
        if (!hasPrevious) return null
        index -= 1
        return current
    }
}
```

- [ ] **Step 4: 运行测试**

Run：同上。Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/io/github/xhzair/refresholdphone/local/LocalPlaybackQueue.kt app/src/test/java/io/github/xhzair/refresholdphone/local/LocalPlaybackQueueTest.kt
git commit -m "feat: add local playback queue"
```

---

### Task 4: 播放服务（MediaPlayer + MediaSession + 前台通知）

**Files:**
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalPlaybackContract.kt`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalNowPlaying.kt`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/local/LocalPlaybackService.kt`
- Create: `app/src/main/res/drawable/ic_music_note.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 加图标与字符串**

`ic_music_note.xml`：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@color/white"
        android:pathData="M12,3 L19,1.5 V14.5 A3,3 0 1,1 17,11.7 V6 L14,6.7 V17 A3,3 0 1,1 12,14.2 Z" />
</vector>
```

`strings.xml` 追加：

```xml
<string name="local_music">本地音乐</string>
<string name="bluetooth_music">蓝牙音乐</string>
<string name="bluetooth_not_connected">未连接</string>
<string name="bluetooth_output_named">蓝牙输出：%1$s</string>
<string name="bluetooth_not_connected_prompt">未连接蓝牙音频设备，请先连接蓝牙耳机或音箱。</string>
<string name="open_bluetooth_settings">打开蓝牙设置</string>
<string name="local_music_title">本地音乐</string>
<string name="local_music_count">全部歌曲 · %1$d 首</string>
<string name="local_music_empty">手机里还没有音乐</string>
<string name="local_music_permission">需要读取音频权限才能显示本地音乐</string>
<string name="local_music_grant">授予权限</string>
<string name="local_music_open_settings">去设置</string>
<string name="local_music_playing">正在播放</string>
<string name="local_music_paused">已暂停</string>
<string name="local_music_play_failed">无法播放这首歌曲</string>
<string name="local_music_notification_channel">本地音乐播放</string>
```

Manifest 追加（放在 `<application>` 内）：

```xml
<service
    android:name=".local.LocalPlaybackService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback" />
```

Manifest 权限追加（`<manifest>` 下）：

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 2: 实现契约与状态容器**

`LocalPlaybackContract.kt`：

```kotlin
package io.github.xhzair.refresholdphone.local

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object LocalPlaybackContract {
    const val ACTION_PLAY_QUEUE = "io.github.xhzair.refresholdphone.local.PLAY_QUEUE"
    const val ACTION_TOGGLE = "io.github.xhzair.refresholdphone.local.TOGGLE"
    const val ACTION_NEXT = "io.github.xhzair.refresholdphone.local.NEXT"
    const val ACTION_PREVIOUS = "io.github.xhzair.refresholdphone.local.PREVIOUS"
    const val ACTION_STOP = "io.github.xhzair.refresholdphone.local.STOP"
    const val EXTRA_TRACK_IDS = "track_ids"
    const val EXTRA_START_INDEX = "start_index"

    fun playQueue(context: Context, trackIds: LongArray, startIndex: Int) {
        val intent = Intent(context, LocalPlaybackService::class.java).apply {
            action = ACTION_PLAY_QUEUE
            putExtra(EXTRA_TRACK_IDS, trackIds)
            putExtra(EXTRA_START_INDEX, startIndex)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
```

`LocalNowPlaying.kt`：

```kotlin
package io.github.xhzair.refresholdphone.local

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class LocalNowPlaying(
    val track: LocalTrack? = null,
    val playing: Boolean = false,
)

object LocalNowPlayingState {
    private val mutable = MutableLiveData(LocalNowPlaying())
    val liveData: LiveData<LocalNowPlaying> = mutable

    fun update(value: LocalNowPlaying) {
        mutable.postValue(value)
    }
}
```

- [ ] **Step 3: 实现服务**

`LocalPlaybackService.kt`（完整代码）：

```kotlin
package io.github.xhzair.refresholdphone.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.github.xhzair.refresholdphone.MainActivity
import io.github.xhzair.refresholdphone.R

class LocalPlaybackService : Service() {
    private lateinit var audioManager: AudioManager
    private lateinit var session: MediaSession
    private var player: MediaPlayer? = null
    private var queue: LocalPlaybackQueue? = null
    private var playing = false
    private var prepared = false
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_GAIN -> player?.setVolume(1f, 1f)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        session = MediaSession(this, "LocalPlayback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) {
                    player?.seekTo(pos.toInt())
                    publishState()
                }
                override fun onStop() = stopPlayback()
            })
            setSessionActivity(contentIntent())
            isActive = true
        }
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            LocalPlaybackContract.ACTION_PLAY_QUEUE -> {
                val ids = intent.getLongArrayExtra(LocalPlaybackContract.EXTRA_TRACK_IDS)
                    ?: LongArray(0)
                val index = intent.getIntExtra(LocalPlaybackContract.EXTRA_START_INDEX, 0)
                startQueue(ids, index)
            }
            LocalPlaybackContract.ACTION_TOGGLE -> if (playing) pause() else play()
            LocalPlaybackContract.ACTION_NEXT -> next()
            LocalPlaybackContract.ACTION_PREVIOUS -> previous()
            LocalPlaybackContract.ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    private fun startQueue(ids: LongArray, startIndex: Int) {
        val tracks = LocalLibrary.loadByIds(this, ids)
        if (tracks.isEmpty()) {
            stopSelf()
            return
        }
        queue = LocalPlaybackQueue(tracks, startIndex)
        playCurrent()
    }

    private fun playCurrent() {
        val track = queue?.current ?: return
        val mediaPlayer = player ?: MediaPlayer().also { created ->
            player = created
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            created.setOnCompletionListener {
                if (queue?.moveNext() != null) playCurrent() else endOfQueue()
            }
            created.setOnErrorListener { _, _, _ ->
                prepared = false
                playing = false
                publishState()
                updateNotification(playing = false)
                LocalNowPlayingState.update(LocalNowPlaying(queue?.current, false))
                true
            }
            created.setOnPreparedListener {
                prepared = true
                play()
            }
        }
        prepared = false
        mediaPlayer.reset()
        mediaPlayer.setDataSource(this, LocalLibrary.contentUri(track.id))
        mediaPlayer.prepareAsync()
        updateMetadata(track)
        updateNotification(playing = false)
        LocalNowPlayingState.update(LocalNowPlaying(track, false))
    }

    private fun play() {
        val mediaPlayer = player ?: return
        if (!prepared) return
        if (!requestFocus()) return
        mediaPlayer.start()
        playing = true
        publishState()
        updateNotification(playing = true)
        LocalNowPlayingState.update(LocalNowPlaying(queue?.current, true))
    }

    private fun pause() {
        player?.let { if (it.isPlaying) it.pause() }
        playing = false
        publishState()
        updateNotification(playing = false)
        LocalNowPlayingState.update(LocalNowPlaying(queue?.current, false))
    }

    private fun next() {
        if (queue?.moveNext() != null) playCurrent()
    }

    private fun previous() {
        if (queue?.movePrevious() != null) playCurrent()
    }

    private fun endOfQueue() {
        playing = false
        publishState()
        updateNotification(playing = false)
        LocalNowPlayingState.update(LocalNowPlaying(queue?.current, false))
    }

    private fun stopPlayback() {
        player?.run {
            reset()
            release()
        }
        player = null
        queue = null
        playing = false
        prepared = false
        abandonFocus()
        session.isActive = false
        LocalNowPlayingState.update(LocalNowPlaying())
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishState() {
        val track = queue?.current
        val state = when {
            playing -> PlaybackState.STATE_PLAYING
            prepared -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }
        val position = player?.currentPosition?.toLong() ?: 0L
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(state, position, if (playing) 1f else 0f)
                .build(),
        )
        if (track != null) updateMetadata(track)
    }

    private fun updateMetadata(track: LocalTrack) {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
        LocalArtwork.load(this, track)?.let { builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(builder.build())
    }

    private fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            audioManager.abandonAudioFocus(focusListener)
        }
        focusRequest = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.local_music_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun updateNotification(playing: Boolean) {
        val track = queue?.current
        val notification = buildNotification(track, playing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(track: LocalTrack?, playing: Boolean): Notification {
        val style = Notification.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(track?.title ?: getString(R.string.local_music))
            .setContentText(track?.artist.orEmpty())
            .setContentIntent(contentIntent())
            .setDeleteIntent(serviceIntent(LocalPlaybackContract.ACTION_STOP, STOP_REQUEST))
            .setStyle(style)
            .setOngoing(playing)
            .setShowWhen(false)
            .addAction(action(R.drawable.ic_previous, R.string.previous, LocalPlaybackContract.ACTION_PREVIOUS, PREV_REQUEST))
            .addAction(action(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) R.string.pause else R.string.play,
                LocalPlaybackContract.ACTION_TOGGLE,
                TOGGLE_REQUEST,
            ))
            .addAction(action(R.drawable.ic_next, R.string.next, LocalPlaybackContract.ACTION_NEXT, NEXT_REQUEST))
            .build()
    }

    private fun action(icon: Int, title: Int, action: String, requestCode: Int): Notification.Action {
        @Suppress("DEPRECATION")
        return Notification.Action.Builder(icon, getString(title), serviceIntent(action, requestCode)).build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, LocalPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlayback()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
        session.release()
        player?.release()
        player = null
        abandonFocus()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "local_music_playback"
        const val NOTIFICATION_ID = 1101
        const val TOGGLE_REQUEST = 1
        const val PREV_REQUEST = 2
        const val NEXT_REQUEST = 3
        const val STOP_REQUEST = 4
    }
}
```

- [ ] **Step 4: 编译**

Run：
```powershell
$env:ANDROID_USER_HOME='C:\Users\82562\Downloads\create_value\.android-user-home'
& "C:\Users\82562\Downloads\create_value\gradle-9.6.0\bin\gradle.bat" -g "C:\Users\82562\Downloads\create_value\.gradle-wrapper-home-3" --offline assembleDebug --console=plain
```
Expected: BUILD SUCCESSFUL（服务暂未被 UI 使用）。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/io/github/xhzair/refresholdphone/local app/src/main/res/drawable/ic_music_note.xml app/src/main/res/values/strings.xml app/src/main/AndroidManifest.xml
git commit -m "feat: add local playback foreground service"
```

---

### Task 5: 本地音乐界面与歌曲列表

**Files:**
- Create: `app/src/main/res/layout/activity_local_music.xml`
- Create: `app/src/main/res/layout/item_local_track.xml`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/ui/LocalMusicActivity.kt`
- Create: `app/src/main/java/io/github/xhzair/refresholdphone/ui/LocalTrackAdapter.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 布局**

`activity_local_music.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="true"
    android:orientation="vertical"
    android:padding="@dimen/screen_padding">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <Button
            android:id="@+id/back"
            style="@style/TextAction"
            android:layout_width="wrap_content"
            android:text="@string/settings_back" />

        <TextView
            android:id="@+id/title"
            style="@style/HomeText"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="16dp"
            android:layout_weight="1"
            android:text="@string/local_music_title"
            android:textSize="28sp" />
    </LinearLayout>

    <TextView
        android:id="@+id/count"
        style="@style/HomeText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:textColor="@color/muted" />

    <TextView
        android:id="@+id/bluetoothBanner"
        style="@style/HomeText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:textColor="@color/accent"
        android:visibility="gone" />

    <LinearLayout
        android:id="@+id/permissionPanel"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:orientation="vertical"
        android:visibility="gone">

        <TextView
            style="@style/HomeText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/local_music_permission"
            android:textSize="18sp" />

        <Button
            android:id="@+id/grantPermission"
            style="@style/TextAction"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/local_music_grant" />

        <Button
            android:id="@+id/openSettings"
            style="@style/TextAction"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="@string/local_music_open_settings" />
    </LinearLayout>

    <TextView
        android:id="@+id/empty"
        style="@style/HomeText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:text="@string/local_music_empty"
        android:textSize="18sp"
        android:visibility="gone" />

    <ProgressBar
        android:id="@+id/progress"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center_horizontal"
        android:layout_marginTop="24dp"
        android:visibility="gone" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/trackList"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_marginTop="12dp"
        android:layout_weight="1"
        android:clipToPadding="false" />
</LinearLayout>
```

`item_local_track.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:foreground="?android:attr/selectableItemBackground"
    android:clickable="true"
    android:focusable="true"
    android:gravity="center_vertical"
    android:minHeight="64dp"
    android:orientation="horizontal"
    android:paddingStart="8dp"
    android:paddingEnd="8dp">

    <ImageView
        android:id="@+id/artwork"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:importantForAccessibility="no"
        android:scaleType="centerCrop" />

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/trackTitle"
            style="@style/HomeText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:ellipsize="end"
            android:maxLines="1"
            android:textSize="18sp" />

        <TextView
            android:id="@+id/trackSubtitle"
            style="@style/HomeText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:ellipsize="end"
            android:maxLines="1"
            android:textColor="@color/muted"
            android:textSize="16sp" />
    </LinearLayout>

    <TextView
        android:id="@+id/trackStatus"
        style="@style/HomeText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textColor="@color/accent"
        android:textSize="16sp"
        android:visibility="gone" />
</LinearLayout>
```

Manifest 增加：

```xml
<activity
    android:name=".ui.LocalMusicActivity"
    android:exported="false" />
```

- [ ] **Step 2: 适配器**

`LocalTrackAdapter.kt`：

```kotlin
package io.github.xhzair.refresholdphone.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.xhzair.refresholdphone.R
import io.github.xhzair.refresholdphone.databinding.ItemLocalTrackBinding
import io.github.xhzair.refresholdphone.local.LocalArtwork
import io.github.xhzair.refresholdphone.local.LocalNowPlaying
import io.github.xhzair.refresholdphone.local.LocalTrack
import java.util.concurrent.Executors

class LocalTrackAdapter(
    private val onClick: (track: LocalTrack, index: Int) -> Unit,
) : RecyclerView.Adapter<LocalTrackAdapter.Holder>() {
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tracks: List<LocalTrack> = emptyList()
    private var nowPlaying: LocalNowPlaying = LocalNowPlaying()

    class Holder(val binding: ItemLocalTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemLocalTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = tracks.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = tracks[position]
        with(holder.binding) {
            trackTitle.text = track.title
            trackSubtitle.text = listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · ")
            trackStatus.visibility = when {
                nowPlaying.track?.id == track.id && nowPlaying.playing -> {
                    trackStatus.text = root.context.getString(R.string.local_music_playing)
                    android.view.View.VISIBLE
                }
                nowPlaying.track?.id == track.id -> {
                    trackStatus.text = root.context.getString(R.string.local_music_paused)
                    android.view.View.VISIBLE
                }
                else -> android.view.View.GONE
            }
            val cached = LocalArtwork.cached(track.id)
            if (cached != null) {
                artwork.setImageBitmap(cached)
            } else {
                artwork.setImageResource(R.drawable.ic_artwork)
            }
            root.setOnClickListener { onClick(track, holder.bindingAdapterPosition) }
            val token = track.id
            root.tag = token
            if (cached == null) {
                executor.execute {
                    val bitmap = LocalArtwork.load(root.context, track)
                    mainHandler.post {
                        if (root.tag == token && bitmap != null) artwork.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    fun submit(newTracks: List<LocalTrack>) {
        tracks = newTracks
        notifyDataSetChanged()
    }

    fun setNowPlaying(value: LocalNowPlaying) {
        val previousId = nowPlaying.track?.id
        nowPlaying = value
        listOfNotNull(previousId, value.track?.id).distinct().forEach { id ->
            val index = tracks.indexOfFirst { it.id == id }
            if (index >= 0) notifyItemChanged(index)
        }
    }
}
```

- [ ] **Step 3: 实现 Activity**

`LocalMusicActivity.kt`：

```kotlin
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

class LocalMusicActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLocalMusicBinding
    private lateinit var adapter: LocalTrackAdapter
    private var tracks: List<LocalTrack> = emptyList()
    private val requiredPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalMusicBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBars()
        adapter = LocalTrackAdapter { _, index ->
            requestNotificationPermissionIfNeeded()
            val ids = tracks.map { it.id }.toLongArray()
            LocalPlaybackContract.playQueue(this, ids, index)
        }
        binding.trackList.layoutManager = LinearLayoutManager(this)
        binding.trackList.adapter = adapter
        binding.back.setOnClickListener { finish() }
        binding.grantPermission.setOnClickListener { permissionLauncher.launch(requiredPermission) }
        binding.openSettings.setOnClickListener { openSettings() }
        LocalNowPlayingState.liveData.observe(this) { adapter.setNowPlaying(it) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        renderBluetooth()
        load()
    }

    private fun load() {
        if (ContextCompat.checkSelfPermission(this, requiredPermission) != PackageManager.PERMISSION_GRANTED) {
            showPermission()
            return
        }
        binding.permissionPanel.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.trackList.visibility = View.VISIBLE
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { LocalLibrary.query(this@LocalMusicActivity) }
            tracks = loaded
            binding.progress.visibility = View.GONE
            binding.count.text = getString(R.string.local_music_count, loaded.size)
            binding.empty.visibility = if (loaded.isEmpty()) View.VISIBLE else View.GONE
            adapter.submit(loaded)
        }
    }

    private fun showPermission() {
        binding.permissionPanel.visibility = View.VISIBLE
        binding.empty.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.trackList.visibility = View.INVISIBLE
    }

    private fun renderBluetooth() {
        val output = BluetoothOutputSource.current(this)
        binding.bluetoothBanner.visibility = if (output.connected) View.VISIBLE else View.GONE
        if (output.connected) {
            binding.bluetoothBanner.text = getString(
                R.string.bluetooth_output_named,
                output.deviceName ?: getString(R.string.bluetooth_music),
            )
        }
    }

    private fun openSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (UserPreferences(applicationContext).hideStatusBar) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}
```

- [ ] **Step 4: 编译并安装**

Run：`assembleDebug`，Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/io/github/xhzair/refresholdphone/ui/LocalMusicActivity.kt app/src/main/java/io/github/xhzair/refresholdphone/ui/LocalTrackAdapter.kt app/src/main/res/layout/activity_local_music.xml app/src/main/res/layout/item_local_track.xml app/src/main/AndroidManifest.xml
git commit -m "feat: add local music library screen"
```

---

### Task 6: 桌面源弹窗接入本地/蓝牙源

**Files:**
- Modify: `app/src/main/java/io/github/xhzair/refresholdphone/prefs/UserPreferences.kt`
- Modify: `app/src/main/java/io/github/xhzair/refresholdphone/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/drawable/ic_bluetooth.xml`
- Test: `app/src/androidTest/java/io/github/xhzair/refresholdphone/MainActivityTest.kt`（新增用例）

- [ ] **Step 1: 加 prefs 键**

`UserPreferences` 增加：

```kotlin
var lastMediaSourceId: String?
    get() = preferences.getString(KEY_LAST_MEDIA_SOURCE, null)?.takeUnless(String::isBlank)
    set(value) {
        preferences.edit { putString(KEY_LAST_MEDIA_SOURCE, value) }
    }
```

companion 增加：

```kotlin
const val KEY_LAST_MEDIA_SOURCE = "last_media_source"
```

- [ ] **Step 2: 桌面源弹窗接入**

`MainActivity.applyState` 中原来的 `renderMediaSource(MediaSourceSelection.currentSource(apps, preferences.lastLaunchedMediaPackage))` 改为：

```kotlin
val bluetooth = BluetoothOutputSource.current(this)
val entries = MediaSourceSelection.entries(
    apps = apps,
    bluetooth = bluetooth,
    localLabel = getString(R.string.local_music),
    bluetoothLabel = getString(R.string.bluetooth_music),
    disconnectedLabel = getString(R.string.bluetooth_not_connected),
)
renderMediaSource(
    MediaSourceSelection.currentEntry(
        entries,
        MediaSourceSelection.initialSourceId(
            preferences.lastMediaSourceId,
            preferences.lastLaunchedMediaPackage,
        ),
    ),
)
```

- [ ] **Step 3: 添加蓝牙图标**

`ic_bluetooth.xml`：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@color/white"
        android:pathData="M12,2 L18,8 L13,12 L18,16 L12,22 V14 L7,18 L5,16 L11,12 L5,8 L7,6 L12,10 Z" />
</vector>
```

- [ ] **Step 4: 修改 MainActivity**

将 `renderMediaSource`、`showMediaSourceDialog`、`launchMediaApp` 改为使用 `MediaSourceEntry`；关键代码：

```kotlin
private var renderedMediaSourceId: String? = null

private fun renderMediaSource(entry: MediaSourceEntry?) {
    if (mediaSourceRendered && entry?.id == renderedMediaSourceId) return
    mediaSourceRendered = true
    renderedMediaSourceId = entry?.id
    binding.mediaSource.setImageDrawable(sourceIcon(entry))
    binding.mediaSource.contentDescription = if (entry != null) {
        getString(R.string.media_source_entry_named, entry.label)
    } else {
        getString(R.string.media_source_entry)
    }
}

private fun sourceIcon(entry: MediaSourceEntry?): Drawable? = when (entry?.kind) {
    MediaSourceKind.LOCAL_MUSIC -> ContextCompat.getDrawable(this, R.drawable.ic_music_note)
    MediaSourceKind.BLUETOOTH_MUSIC -> ContextCompat.getDrawable(this, R.drawable.ic_bluetooth)
    MediaSourceKind.APP -> entry.packageName?.let(::appIcon)
    null -> null
} ?: ContextCompat.getDrawable(this, R.drawable.ic_artwork)

private fun showMediaSourceDialog() {
    val bluetooth = BluetoothOutputSource.current(this)
    val entries = MediaSourceSelection.entries(
        apps = apps,
        bluetooth = bluetooth,
        localLabel = getString(R.string.local_music),
        bluetoothLabel = getString(R.string.bluetooth_music),
        disconnectedLabel = getString(R.string.bluetooth_not_connected),
    )
    val current = MediaSourceSelection.currentEntry(
        entries,
        MediaSourceSelection.initialSourceId(
            preferences.lastMediaSourceId,
            preferences.lastLaunchedMediaPackage,
        ),
    )
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    var dialog: AlertDialog? = null
    entries.forEach { entry ->
        val row = layoutInflater.inflate(R.layout.item_media_source, rows, false)
        row.findViewById<ImageView>(R.id.sourceIcon).setImageDrawable(sourceIcon(entry))
        row.findViewById<TextView>(R.id.sourceLabel).text = entry.sublabel
            ?.let { getString(R.string.media_source_label_with_sublabel, entry.label, it) }
            ?: entry.label
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
            if (app != null) launchMediaApp(app)
        }
        MediaSourceKind.LOCAL_MUSIC -> {
            rememberSource(entry.id)
            startActivity(Intent(this, LocalMusicActivity::class.java))
        }
        MediaSourceKind.BLUETOOTH_MUSIC -> {
            if (entry.available) {
                rememberSource(entry.id)
                startActivity(Intent(this, LocalMusicActivity::class.java))
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

private fun rememberSource(sourceId: String) {
    preferences.lastMediaSourceId = sourceId
    if (sourceId.startsWith("app:")) {
        preferences.lastLaunchedMediaPackage = sourceId.removePrefix("app:")
    }
}
```

`launchMediaApp` 保持原逻辑，但在成功后调用：

```kotlin
val entry = MediaSourceSelection.currentEntry(
    MediaSourceSelection.entries(
        apps,
        BluetoothOutputSource.current(this),
        getString(R.string.local_music),
        getString(R.string.bluetooth_music),
        getString(R.string.bluetooth_not_connected),
    ),
    MediaSourceSelection.appId(app.packageName),
)
rememberSource(entry?.id ?: MediaSourceSelection.appId(app.packageName))
renderMediaSource(entry)
```

strings 追加：

```xml
<string name="media_source_label_with_sublabel">%1$s（%2$s）</string>
```

- [ ] **Step 5: 仪器化用例**

`MainActivityTest` 增加：

```kotlin
@Test
fun mediaSourceDialogShowsLocalAndBluetoothSources() {
    ActivityScenario.launch(MainActivity::class.java).use {
        onView(withId(R.id.mediaSource)).perform(click())
        onView(withText(R.string.local_music)).check(matches(isDisplayed()))
        onView(withText(containsString("蓝牙音乐"))).check(matches(isDisplayed()))
    }
}
```

导入 `org.hamcrest.Matchers.containsString`。

- [ ] **Step 6: 编译 + 单元测试**

Run：
```powershell
$env:ANDROID_USER_HOME='C:\Users\82562\Downloads\create_value\.android-user-home'
& "C:\Users\82562\Downloads\create_value\gradle-9.6.0\bin\gradle.bat" -g "C:\Users\82562\Downloads\create_value\.gradle-wrapper-home-3" --offline testDebugUnitTest assembleDebug --console=plain
```
Expected: BUILD SUCCESSFUL；现有单元测试通过。

- [ ] **Step 7: 提交**

```powershell
git add app/src/main/java/io/github/xhzair/refresholdphone/MainActivity.kt app/src/main/java/io/github/xhzair/refresholdphone/prefs/UserPreferences.kt app/src/main/res/values/strings.xml app/src/main/res/drawable/ic_bluetooth.xml app/src/main/java/io/github/xhzair/refresholdphone/ui
git commit -m "feat: expose local and Bluetooth sources on home"
```

---

### Task 7: 单源互斥纳入本应用 + 播放状态联动

**Files:**
- Modify: `app/src/main/java/io/github/xhzair/refresholdphone/media/ExternalPlaybackBridge.kt`
- Test: `app/src/test/java/io/github/xhzair/refresholdphone/media/PlaybackSnapshotTest.kt`

- [ ] **Step 1: 写失败测试**

在 `PlaybackSnapshotTest` 增加：

```kotlin
@Test fun `exclusive policy includes the local player package`() {
    assertTrue(
        shouldPauseOtherSession(
            selectedPackage = "com.tencent.qqmusic",
            otherPackage = "io.github.xhzair.refresholdphone",
            otherPlaying = true,
            mediaPackages = supportedPlaybackPackages,
        ),
    )
}
```

- [ ] **Step 2: 运行确认失败**

Run：
```powershell
$env:ANDROID_USER_HOME='C:\Users\82562\Downloads\create_value\.android-user-home'
& "C:\Users\82562\Downloads\create_value\gradle-9.6.0\bin\gradle.bat" -g "C:\Users\82562\Downloads\create_value\.gradle-wrapper-home-3" --offline testDebugUnitTest --tests "io.github.xhzair.refresholdphone.media.PlaybackSnapshotTest" --console=plain
```
Expected: FAIL（`io.github.xhzair.refresholdphone` 不在集合）。

- [ ] **Step 3: 加入本应用包名**

`ExternalPlaybackBridge.kt`：

```kotlin
internal val supportedPlaybackPackages = setOf(
    io.github.xhzair.refresholdphone.BuildConfig.APPLICATION_ID,
    "com.netease.cloudmusic",
    "com.tencent.qqmusic",
    "app.podcast.cosmos",
)
```

- [ ] **Step 4: 运行测试**

Run：同上。Expected: PASS。

- [ ] **Step 5: 编译并安装**

Run：`assembleDebug`，`adb install -r`，确认桌面卡片能显示本地播放器（需在 Task 8 手动验证）。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/io/github/xhzair/refresholdphone/media/ExternalPlaybackBridge.kt app/src/test/java/io/github/xhzair/refresholdphone/media/PlaybackSnapshotTest.kt
git commit -m "feat: include local player in single-source guard"
```

---

### Task 8: 真机验收、证据与文档

**Files:**
- Modify: `README.md`
- Modify: `docs/device-testing-mix2s.md`
- Modify: `docs/release-checklist.md`
- Add evidence: `docs/device-evidence/27-local-music-list.jpg`、`28-local-music-card.jpg`、`29-local-music-notification.jpg`、`30-bluetooth-source.jpg`

- [ ] **Step 1: 准备测试音频**

用仓库环境 Python 生成 2 个 WAV（30 秒 220Hz / 35 秒 330Hz），推入手机并触发扫描：

```powershell
$py='C:\Users\82562\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
$script=@'
import wave, struct, math, os
base = r"C:\Users\82562\Downloads\create_value\task23-qa"
os.makedirs(base, exist_ok=True)
for name, freq, seconds in [("local-a.wav", 220, 30), ("local-b.wav", 330, 35)]:
    path = os.path.join(base, name)
    with wave.open(path, "w") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(44100)
        for i in range(44100 * seconds):
            w.writeframes(struct.pack("<h", int(12000 * math.sin(2 * math.pi * freq * i / 44100))))
    print(path, os.path.getsize(path))
'@
$tmp=Join-Path $env:TEMP 'maketones.py'; Set-Content $tmp $script -Encoding UTF8; & $py $tmp
$adb=(Resolve-Path '..\.tools\platform-tools\adb.exe').Path
& $adb shell mkdir -p /sdcard/Music
& $adb push 'C:\Users\82562\Downloads\create_value\task23-qa\local-a.wav' /sdcard/Music/local-a.wav
& $adb push 'C:\Users\82562\Downloads\create_value\task23-qa\local-b.wav' /sdcard/Music/local-b.wav
& $adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/local-a.wav
& $adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/local-b.wav
```

- [ ] **Step 2: 真机 UI 验收**

1. 安装 debug APK，打开媒体桌面 → 媒体源图标 → 选择「本地音乐」→ 授予音频权限。
2. 列表显示两条测试音频；点击播放；桌面应显示标题/歌手/进度，播放控制可用。
3. 回到桌面/锁屏：播放不中断；下拉通知栏出现「本地音乐播放」通知，上一首/暂停/下一首可用。
4. 本地播放中启动 QQ 音乐：本地暂停；从本地音乐页继续播放后，QQ 暂停。
5. 媒体源弹窗显示「蓝牙音乐（未连接）」；点击弹「未连接蓝牙音频设备」并可打开系统蓝牙设置。
6. 如有蓝牙耳机/音箱：连接后弹窗显示「蓝牙音乐 · 设备名」；选择后播放，声音从蓝牙输出；耳机按键暂停/下一首有效（若硬件不可用，在验收记录中标注未覆盖）。

- [ ] **Step 3: 截图与压缩**

用仓库环境 Python + Pillow 压缩截图（与之前证据处理一致）：

```powershell
$py='C:\Users\82562\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
$script=@'
from PIL import Image
import os
base = r"C:\Users\82562\Downloads\create_value"
pairs = {
    "task23-qa/local-list.png": "27-local-music-list.jpg",
    "task23-qa/local-card.png": "28-local-music-card.jpg",
    "task23-qa/local-notification.png": "29-local-music-notification.jpg",
    "task23-qa/bluetooth-source.png": "30-bluetooth-source.jpg",
}
out_dir = os.path.join(base, "refresh_oldphone", "docs", "device-evidence")
for src, name in pairs.items():
    path = os.path.join(base, src)
    if not os.path.exists(path):
        print("missing", path)
        continue
    im = Image.open(path).convert("RGB")
    w, h = im.size
    im = im.resize((540, round(h * 540 / w)), Image.LANCZOS)
    target = os.path.join(out_dir, name)
    im.save(target, "JPEG", quality=82, optimize=True)
    print(target, im.size)
'@
$tmp=Join-Path $env:TEMP 'local_evidence.py'; Set-Content $tmp $script -Encoding UTF8; & $py $tmp
```

至少保存：本地列表、桌面卡片、通知栏、蓝牙源弹窗。

- [ ] **Step 4: 更新文档**

- README：
  - 功能列表加入「本地音乐」「蓝牙音乐」。
  - 界面加入本地音乐列表与卡片截图。
  - 已知限制：本地播放器仅首版（无搜索/歌单/随机循环）；蓝牙 AVRCP 需硬件实测。
- `docs/device-testing-mix2s.md`：新增 §9「本地音乐与蓝牙音乐验收」（步骤、结果、证据）。
- `docs/release-checklist.md`：新增：
  - 「本地音乐：扫描、播放、后台/锁屏、通知控制、与外部源互斥」
  - 「蓝牙源：未连接提示与设置入口；有设备时设备名、输出与按键」

- [ ] **Step 5: 全量验证**

```powershell
$env:ANDROID_USER_HOME='C:\Users\82562\Downloads\create_value\.android-user-home'
& "C:\Users\82562\Downloads\create_value\gradle-9.6.0\bin\gradle.bat" -g "C:\Users\82562\Downloads\create_value\.gradle-wrapper-home-3" --offline testDebugUnitTest assembleDebug lintDebug --console=plain
```
Expected：单元测试全部通过、lint 0 error；随后构建/安装 androidTest 并跑全部仪器化测试（含新增用例）。

- [ ] **Step 6: 提交文档与证据**

```powershell
git add README.md docs/device-testing-mix2s.md docs/release-checklist.md docs/device-evidence/27-local-music-list.jpg docs/device-evidence/28-local-music-card.jpg docs/device-evidence/29-local-music-notification.jpg docs/device-evidence/30-bluetooth-source.jpg
git commit -m "docs: record local and Bluetooth music acceptance"
```

---

## 验收清单（与 spec §13 对齐）

- [ ] 媒体源弹窗出现「本地音乐」「蓝牙音乐」，蓝牙状态正确。
- [ ] 本地音乐库扫描、排序、封面、点击播放。
- [ ] 桌面卡片、进度、上一首/暂停/下一首与外部源一致。
- [ ] 后台/锁屏继续播放，MediaStyle 通知可控制。
- [ ] 本地与 QQ/网易云/小宇宙双向单源互斥。
- [ ] 蓝牙设备名显示；未连接可跳转设置；有设备时音频走蓝牙。
- [ ] 权限拒绝、空库、坏文件、蓝牙断开不崩溃。
- [ ] 现有外部源、应用抽屉、设置、默认桌面无回归。
