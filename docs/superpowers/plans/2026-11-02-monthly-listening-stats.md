# 月度收听记录 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 本地记录本地音乐与进程存活时可观测的外部 MediaSession 播放，提供安静的月度收听汇总页面（设置页入口），数据仅存本机 SQLite，并明确外部覆盖缺口。

**Architecture:** 新增 `stats/` 包：纯逻辑 `ListeningSessionTracker` 与 `ListeningStatsAggregator`、SQLite `ListeningStatsStore`、`ListeningHistoryRepository`、Application 级 `PlaybackHistoryObserver`。本地播放服务直接上报，外部源通过 MediaSession 观察；`ListeningStatsActivity` 展示月报。

**Tech Stack:** Kotlin、平台 `MediaSession`/`MediaSessionManager`、SQLiteOpenHelper、AppCompat/Views、JUnit4；无新依赖、无网络。

---

## 前置约束与命令

- 工作目录：`C:\Users\82562\Downloads\create_value\refresh_oldphone`
- 离线构建：
  ```powershell
  $env:ANDROID_USER_HOME='C:\Users\82562\Downloads\create_value\.android-user-home'
  & "C:\Users\82562\Downloads\create_value\gradle-9.6.0\bin\gradle.bat" `
    -g "C:\Users\82562\Downloads\create_value\.gradle-wrapper-home-3" --offline `
    testDebugUnitTest assembleDebug lintDebug --no-watch-fs --console=plain
  ```
- 仪器化/真机由 Task 7 执行；MIUI 测试包安装限制已知。
- 文件 UTF-8，使用 edit/write；不新增依赖。

## 文件结构

新增：

- `app/src/main/java/io/github/xhzair/refresholdphone/stats/ListeningSourceKind.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/stats/ListeningSession.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/stats/ListeningSessionTracker.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/stats/ListeningStatsAggregator.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/stats/MonthlyListeningSummary.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/stats/ListeningStatsStore.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/stats/ListeningHistoryRepository.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/stats/PlaybackHistoryObserver.kt`
- `app/src/main/java/io/github/xhzair/refresholdphone/ui/ListeningStatsActivity.kt`
- `app/src/main/res/layout/activity_listening_stats.xml`
- 单元测试：`ListeningSessionTrackerTest`、`ListeningStatsAggregatorTest`
- 证据：`docs/device-evidence/32-listening-stats-month.jpg`（Task 7）

修改：

- `MediaShellApplication.kt`
- `LocalPlaybackService.kt`
- `MainActivity.kt`、`MediaNotificationListener.kt`
- `SettingsActivity.kt`、`AndroidManifest.xml`、`strings.xml`
- `README.md`、`docs/privacy.md`、`docs/release-checklist.md`、`docs/device-testing-mix2s.md`

---

### Task 1: 数据模型 + 会话状态机（纯逻辑）

**Files:**
- Create: `stats/ListeningSourceKind.kt`
- Create: `stats/ListeningSession.kt`
- Create: `stats/ListeningSessionTracker.kt`
- Test: `app/src/test/java/io/github/xhzair/refresholdphone/stats/ListeningSessionTrackerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package io.github.xhzair.refresholdphone.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningSessionTrackerTest {
    private val written = mutableListOf<ListeningSession>()
    private val tracker = ListeningSessionTracker(
        store = { written += it },
        uidFactory = { "id-${written.size}" },
    )

    private fun observed(
        title: String = "Song",
        playing: Boolean = true,
        position: Long = 0,
        duration: Long = 60_000,
        packageName: String? = "com.tencent.qqmusic",
    ) = PlaybackObservation(
        sourceKind = if (packageName == null) ListeningSourceKind.LOCAL else ListeningSourceKind.EXTERNAL,
        packageName = packageName,
        title = title,
        artist = "Artist",
        album = "Album",
        durationMs = duration,
        positionMs = position,
        playing = playing,
    )

    @Test
    fun `accumulates played wall time and flushes on close`() {
        tracker.onObserved(observed(), 1_000)
        tracker.onObserved(observed(playing = true), 6_000)
        tracker.flush(11_000)
        assertEquals(10_000L, written.last().listenedMs)
        assertTrue(written.last().closed)
    }

    @Test
    fun `pause does not accumulate and resume continues same session`() {
        tracker.onObserved(observed(), 0)
        tracker.onObserved(observed(playing = false), 5_000)
        tracker.onObserved(observed(playing = false), 20_000)
        tracker.onObserved(observed(playing = true), 21_000)
        tracker.flush(26_000)
        assertEquals(5_000L + 5_000L, written.last().listenedMs)
        assertEquals(1, written.count { it.closed })
    }

    @Test
    fun `identity change closes old and opens new`() {
        tracker.onObserved(observed(title = "A"), 0)
        tracker.onObserved(observed(title = "B"), 4_000)
        tracker.flush(6_000)
        assertEquals(2, written.count { it.closed })
        assertEquals("A", written.first().title)
        assertEquals("B", written.last().title)
        assertEquals(4_000L, written.first().listenedMs)
        assertEquals(2_000L, written.last().listenedMs)
    }

    @Test
    fun `unknown metadata is ignored`() {
        tracker.onObserved(observed(title = ""), 0)
        tracker.flush(5_000)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `paused first observation does not open a session`() {
        tracker.onObserved(observed(playing = false), 0)
        tracker.flush(5_000)
        assertTrue(written.isEmpty())
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
cd 'C:\Users\82562\Downloads\create_value\refresh_oldphone'
$env:ANDROID_USER_HOME='C:\Users\82562\Downloads\create_value\.android-user-home'
& "C:\Users\82562\Downloads\create_value\gradle-9.6.0\bin\gradle.bat" -g "C:\Users\82562\Downloads\create_value\.gradle-wrapper-home-3" --offline testDebugUnitTest --tests "io.github.xhzair.refresholdphone.stats.ListeningSessionTrackerTest" --rerun-tasks --no-watch-fs --console=plain
```
Expected：编译失败，找不到 stats 类型。

- [ ] **Step 3: 实现模型**

`ListeningSourceKind.kt` / `ListeningSession.kt`：

```kotlin
enum class ListeningSourceKind { LOCAL, EXTERNAL }

data class PlaybackObservation(
    val sourceKind: ListeningSourceKind,
    val packageName: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
)

data class ListeningSession(
    val uid: String,
    val sourceKind: ListeningSourceKind,
    val packageName: String?,
    val title: String,
    val artist: String,
    val album: String,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val lastPositionMs: Long,
    val closed: Boolean,
)
```

- [ ] **Step 4: 实现 `ListeningSessionTracker`**

```kotlin
package io.github.xhzair.refresholdphone.stats

import java.util.UUID

class ListeningSessionTracker(
    private val store: (ListeningSession) -> Unit,
    private val uidFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Active(
        val uid: String,
        val identity: String,
        val observation: PlaybackObservation,
        val startedAtMs: Long,
        var lastObservedAtMs: Long,
        var playing: Boolean,
        var listenedMs: Long,
        var lastPositionMs: Long,
    )

    private var active: Active? = null

    fun onObserved(observation: PlaybackObservation, nowMs: Long) {
        if (observation.title.isBlank() || nowMs <= 0L) return
        val identity = identityOf(observation)
        val current = active
        if (current != null && current.identity != identity) {
            closeCurrent(nowMs)
        }
        val existing = active
        if (existing == null) {
            if (!observation.playing) return
            active = Active(
                uid = uidFactory(),
                identity = identity,
                observation = observation,
                startedAtMs = nowMs,
                lastObservedAtMs = nowMs,
                playing = true,
                listenedMs = 0,
                lastPositionMs = observation.positionMs,
            )
        } else {
            if (existing.playing) {
                existing.listenedMs += (nowMs - existing.lastObservedAtMs).coerceAtLeast(0)
            }
            existing.playing = observation.playing
            existing.observation = observation
            existing.lastPositionMs = observation.positionMs
            existing.lastObservedAtMs = nowMs
        }
        persist(closed = false)
    }

    fun flush(nowMs: Long) {
        closeCurrent(nowMs)
    }

    private fun closeCurrent(nowMs: Long) {
        val current = active ?: return
        if (current.playing) {
            current.listenedMs += (nowMs - current.lastObservedAtMs).coerceAtLeast(0)
        }
        active = null
        persist(current, closed = true, nowMs = nowMs)
    }

    private fun persist(closed: Boolean) = active?.let { persist(it, closed, it.lastObservedAtMs) }

    private fun persist(active: Active, closed: Boolean, nowMs: Long) {
        store(
            ListeningSession(
                uid = active.uid,
                sourceKind = active.observation.sourceKind,
                packageName = active.observation.packageName,
                title = active.observation.title,
                artist = active.observation.artist,
                album = active.observation.album,
                startedAtMs = active.startedAtMs,
                updatedAtMs = nowMs,
                listenedMs = active.listenedMs,
                durationMs = active.observation.durationMs,
                lastPositionMs = active.lastPositionMs,
                closed = closed,
            ),
        )
    }

    private fun identityOf(observation: PlaybackObservation): String =
        listOf(
            observation.sourceKind.name,
            observation.packageName.orEmpty(),
            observation.title.trim(),
            observation.artist.trim(),
            observation.album.trim(),
            observation.durationMs.toString(),
        ).joinToString("\u0000")
}
```

- [ ] **Step 5: 运行测试**

Expected：5 tests PASS。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/io/github/xhzair/refresholdphone/stats app/src/test/java/io/github/xhzair/refresholdphone/stats/ListeningSessionTrackerTest.kt
git commit -m "feat: add listening session tracker"
```

---

### Task 2: 月报聚合纯逻辑

**Files:**
- Create: `stats/MonthlyListeningSummary.kt`
- Create: `stats/ListeningStatsAggregator.kt`
- Test: `app/src/test/java/io/github/xhzair/refresholdphone/stats/ListeningStatsAggregatorTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package io.github.xhzair.refresholdphone.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class ListeningStatsAggregatorTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private fun session(
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        startedAt: Long,
        listened: Long,
        source: ListeningSourceKind = ListeningSourceKind.LOCAL,
        packageName: String? = null,
    ) = ListeningSession(
        uid = title + startedAt,
        sourceKind = source,
        packageName = packageName,
        title = title,
        artist = artist,
        album = album,
        startedAtMs = startedAt,
        updatedAtMs = startedAt + listened,
        listenedMs = listened,
        durationMs = 60_000,
        lastPositionMs = 0,
        closed = true,
    )

    @Test
    fun `filters by month and computes totals and rankings`() {
        val oct = 1_760_000_000_000L
        val nov = 1_763_000_000_000L
        val sessions = listOf(
            session("A", artist = "X", startedAt = nov, listened = 60_000),
            session("A", artist = "X", startedAt = nov + 1, listened = 30_000),
            session("B", artist = "Y", startedAt = nov + 2, listened = 90_000),
            session("C", artist = "Z", startedAt = oct, listened = 10_000),
        )
        val summary = ListeningStatsAggregator.summarize(sessions, 2025, 11, zone)
        assertEquals(180_000L, summary.totalListenedMs)
        assertEquals(3, summary.sessionCount)
        assertEquals("A", summary.topTracks.first().label)
        assertEquals(90_000L, summary.topTracks.first().listenedMs)
        assertEquals("B", summary.topTracks[1].label)
        assertEquals("X", summary.topArtists.first().label)
        assertEquals(1, summary.sources.size)
    }

    @Test
    fun `computes active days hours and longest session`() {
        val day = 1_763_000_000_000L
        val summary = ListeningStatsAggregator.summarize(
            listOf(
                session("A", startedAt = day, listened = 30_000),
                session("B", startedAt = day + 86_400_000, listened = 50_000),
            ),
            2025, 11, zone,
        )
        assertEquals(2, summary.activeDays)
        assertEquals(2, summary.hours.count { it != 0L })
        assertEquals(50_000L, summary.longestSessionMs)
    }

    @Test
    fun `empty data is a zero summary`() {
        val summary = ListeningStatsAggregator.summarize(emptyList(), 2025, 11, zone)
        assertEquals(0L, summary.totalListenedMs)
        assertEquals(0, summary.sessionCount)
        assertEquals(0, summary.activeDays)
        assertEquals(0, summary.sources.size)
    }
}
```

- [ ] **Step 2: 运行确认失败**（找不到类型）。
- [ ] **Step 3: 实现 `MonthlyListeningSummary` 与聚合器**

`MonthlyListeningSummary.kt`：

```kotlin
data class RankedListeningItem(val label: String, val listenedMs: Long)

data class ListeningSourceShare(
    val label: String,
    val packageName: String?,
    val sourceKind: ListeningSourceKind,
    val listenedMs: Long,
)

data class MonthlyListeningSummary(
    val monthLabel: String,
    val totalListenedMs: Long,
    val sessionCount: Int,
    val activeDays: Int,
    val topTracks: List<RankedListeningItem>,
    val topArtists: List<RankedListeningItem>,
    val topAlbums: List<RankedListeningItem>,
    val sources: List<ListeningSourceShare>,
    val hours: LongArray,
    val longestSessionMs: Long,
)
```

`ListeningStatsAggregator.kt`：

```kotlin
object ListeningStatsAggregator {
    private const val TOP_LIMIT = 5

    fun summarize(
        sessions: List<ListeningSession>,
        year: Int,
        monthZeroBased: Int,
        zone: TimeZone,
    ): MonthlyListeningSummary {
        val calendar = Calendar.getInstance(zone).apply {
            clear()
            set(year, monthZeroBased, 1, 0, 0, 0)
        }
        val start = calendar.timeInMillis
        calendar.add(Calendar.MONTH, 1)
        val end = calendar.timeInMillis
        val inMonth = sessions.filter { it.startedAtMs in start until end && it.listenedMs > 0 }
        val monthLabel = String.format(Locale.CHINA, "%d年%d月", year, monthZeroBased + 1)
        if (inMonth.isEmpty()) {
            return MonthlyListeningSummary(monthLabel, 0, 0, 0, emptyList(), emptyList(), emptyList(), emptyList(), LongArray(24), 0)
        }
        val hours = LongArray(24)
        val days = mutableSetOf<Int>()
        val dayCalendar = Calendar.getInstance(zone)
        inMonth.forEach { session ->
            dayCalendar.timeInMillis = session.startedAtMs
            days += dayCalendar.get(Calendar.DAY_OF_MONTH) +
                dayCalendar.get(Calendar.MONTH) * 100 +
                dayCalendar.get(Calendar.YEAR) * 10_000
            hours[dayCalendar.get(Calendar.HOUR_OF_DAY)] += session.listenedMs
        }
        fun rank(selector: (ListeningSession) -> String): List<RankedListeningItem> =
            inMonth.groupBy(selector)
                .map { (label, list) -> RankedListeningItem(label, list.sumOf { it.listenedMs }) }
                .sortedWith(compareByDescending<RankedListeningItem> { it.listenedMs }.thenBy { it.label })
                .take(TOP_LIMIT)
        val sources = inMonth.groupBy { it.sourceKind to it.packageName }
            .map { (key, list) ->
                ListeningSourceShare(
                    label = when (key.first) {
                        ListeningSourceKind.LOCAL -> "本地音乐"
                        ListeningSourceKind.EXTERNAL -> key.second ?: "外部应用"
                    },
                    packageName = key.second,
                    sourceKind = key.first,
                    listenedMs = list.sumOf { it.listenedMs },
                )
            }
            .sortedWith(compareByDescending<ListeningSourceShare> { it.listenedMs }.thenBy { it.label })
        return MonthlyListeningSummary(
            monthLabel = monthLabel,
            totalListenedMs = inMonth.sumOf { it.listenedMs },
            sessionCount = inMonth.size,
            activeDays = days.size,
            topTracks = rank { it.title.ifBlank { "未知歌曲" } },
            topArtists = rank { it.artist.ifBlank { "未知歌手" } },
            topAlbums = rank { it.album.ifBlank { "未知专辑" } },
            sources = sources,
            hours = hours,
            longestSessionMs = inMonth.maxOf { it.listenedMs },
        )
    }
}
```

Imports: `java.util.Calendar`, `java.util.Locale`, `java.util.TimeZone`.
Note：上面的测试月份时间戳需要按实际 2025-11 生成；实现时用 `Calendar` 生成测试时间，避免硬编码不匹配。测试里改用 helper：
```kotlin
private fun time(year: Int, month: Int, day: Int, hour: Int) = Calendar.getInstance(zone).apply {
    clear(); set(year, month - 1, day, hour, 0, 0)
}.timeInMillis
```
按此修正测试，再运行。

- [ ] **Step 4: 运行测试**，Expected PASS（月份边界测试使用文档辅助的 `time()`）。
- [ ] **Step 5: 提交** `feat: aggregate monthly listening stats`

---

### Task 3: SQLite 存储 + Repository + Application 初始化

**Files:**
- Create: `stats/ListeningStatsStore.kt`
- Create: `stats/ListeningHistoryRepository.kt`
- Modify: `MediaShellApplication.kt`
- Test：无纯逻辑测试；以编译 + 真机 Task 7 覆盖。

实现要点：

- `ListeningStatsStore(context)`：
  - DB 名 `listening_stats.db`，version 1；建表/索引同 spec §5。
  - `ExecutorService` 单线程；`upsert(session)`、`closeStaleOpenSessions(nowMs)`、`loadSessions(startMs, endMs, callback: (List<ListeningSession>) -> Unit)`、`clear(callback)`、`close()`。
  - 所有 SQL 在 executor 内执行；查询回调 `mainHandler.post`。
  - `upsert` 用 `insertWithOnConflict(CONFLICT_REPLACE)`。
- `ListeningHistoryRepository(context)`：
  - 持有 store + tracker；`tracker = ListeningSessionTracker(store::upsert)`。
  - `onLocal(track: LocalTrack, playing: Boolean, positionMs: Long)`：构造 `PlaybackObservation(LOCAL, null, title, artist, album, durationMs, positionMs, playing)`，`tracker.onObserved(..., System.currentTimeMillis())`。
  - `onExternal(observation)`：只接受 EXTERNAL，`tracker.onObserved`。
  - `flush()`、`closeStaleOnStart()`、`loadMonth(year, month, callback)`（通过 store 查询 start/end，再 `ListeningStatsAggregator.summarize`）。
  - `clear(callback)`。
  - `shutdown()` flush + store.close。
- `MediaShellApplication`：
  - `lateinit var listeningHistory: ListeningHistoryRepository`，`onCreate` 中 `listeningHistory = ListeningHistoryRepository(this); listeningHistory.closeStaleOnStart()`。
  - 后续 Task 5 再创建 observer。

验证：`assembleDebug` + 现有 89 单测。

提交：`feat: persist listening sessions locally`

---

### Task 4: 本地播放服务上报

**Files:**
- Modify: `LocalPlaybackService.kt`

- [ ] **Step 1: 添加上报 helper**
```kotlin
private fun reportLocalPlayback(playing: Boolean) {
    val track = queue?.current ?: return
    val app = application as? MediaShellApplication ?: return
    app.listeningHistory.onLocal(
        track = track,
        playing = playing,
        positionMs = player?.currentPosition?.toLong() ?: 0L,
    )
}
```
- [ ] **Step 2: 调用点**
  - `startCurrentTrack` 设置 track 后（准备前）：`reportLocalPlayback(playing = false)`? 为避免记录未实际播放，只在 `onPlayerPrepared` 的首播、`startOrResumePlayback`、`pausePlaybackInternal`、`onTrackCompleted`/error/stop 上报。
  - 最小可靠集合：
    - `onPlayerPrepared` 中若自动播放/已播放 → `reportLocalPlayback(true)`；
    - `startOrResumePlayback()` 成功 start 后 → true；
    - `pausePlaybackInternal()` 成功 pause 后 → false；
    - `handlePlaybackError()` → false；
    - `stopPlayback()`/`onDestroy` → `flush()`；
    - `onTrackCompleted` 切歌时先 `reportLocalPlayback(false)`，下一首 prepared 再上报 true。
  - 使用 `MediaShellApplication.listeningHistory`.
- [ ] **Step 3: 构建 + 单测**；提交 `feat: record local playback history`.

---

### Task 5: 外部 MediaSession 观察器

**Files:**
- Create: `stats/PlaybackHistoryObserver.kt`
- Modify: `MediaShellApplication.kt`
- Modify: `MainActivity.kt` / `MediaNotificationListener.kt`（启动钩子）

实现要点：

```kotlin
class PlaybackHistoryObserver(
    context: Context,
    private val repository: ListeningHistoryRepository,
    private val mediaPackages: () -> Set<String>,
) {
    private val manager = ...
    private val component = ComponentName(context, MediaNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var activeListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val controllers = mutableMapOf<MediaSession.Token, MediaController>()
    private var lastIdentitySource: String? = null

    fun start() { /* permission check, add listener, evaluate */ }
    fun stop() { /* remove callbacks/listener, flush */ }
    fun refresh() { /* access may have changed */ }
}
```

- 权限判断同 `SingleSourcePlaybackGuard`（`NotificationManagerCompat.getEnabledListenerPackages`）。
- `sync(sessions)`：
  - 移除已消失 token 的 callbacks；
  - 新 token 注册 `MediaController.Callback`：`onPlaybackStateChanged`/`onMetadataChanged`/`onSessionDestroyed` → `evaluate()`；
  - 过滤包名：`supportedPlaybackPackages + mediaPackages()`，排除 `BuildConfig.APPLICATION_ID`。
- `evaluate()`：
  - `val playing = controllers.values.firstOrNull { it.playbackState?.state == PLAYING }`
  - 有 playing：构造 `PlaybackObservation(EXTERNAL, package, title, artist, album, duration, position, true)` → `repository.onExternal(...)`；记录 `lastIdentitySource`。
  - 无 playing：若上次的会话仍在 controllers 中（identity 匹配），构造 paused 观测 → `repository.onExternal(false snapshot)`；否则 `repository.flush()`。
  - 元数据缺失/空白 → 跳过。
- `MediaShellApplication`：创建 observer；`ensureListeningHistoryObserverStarted()`；`stop...()`；MainActivity.onStart 调用 ensure；MediaNotificationListener connected/disconnected 调用 start/stop；onCreate 启动一次。
- 服务/监听器已有 appops/permission 逻辑可参考。
- 验证 `assembleDebug` + 单测；提交 `feat: observe external playback for history`.

---

### Task 6: 月报页面 + 设置入口

**Files:**
- Create: `ui/ListeningStatsActivity.kt`
- Create: `res/layout/activity_listening_stats.xml`
- Modify: `SettingsActivity.kt`、`AndroidManifest.xml`、`strings.xml`

页面实现：

- 顶部返回 + 标题「收听记录」；
- 月切换 `‹` `›`（`ImageButton` 或 `Button`），中间 `monthLabel`；
- `coverageNote`（固定文案）；
- `summaryTotal` / `summaryDays` / `summarySessions`；
- `topTracks`、`topArtists`、`topAlbums`（多行 TextView，格式化 "1. 标题 · 歌手  12:34"）；
- `sources`（多行）；
- `hoursChart`：`LinearLayout` 动态添加 24 个 `View`（细条），用 `LayoutParams.height` 按最大值缩放，底部可加 0/6/12/18/23 标签；避免图表库；
- `clearButton`：AlertDialog 确认 → `repository.clear { refresh() }`；
- 加载：`lifecycleScope` / repository 回调 → 渲染；`onResume` 刷新；
- 外部包名显示：`PackageManager.getApplicationLabel`，失败回退包名；
- 时长格式 helper `formatDuration` 放 `stats/ListeningStatsFormat.kt`（纯函数 + 单测）：`h:mm:ss`/`m:ss`。

设置页入口：

- 在“附加媒体应用”/通知区附近添加按钮「收听记录」，点击 `startActivity(Intent(this, ListeningStatsActivity::class.java))`。

Manifest 注册 Activity `exported=false`；strings 添加标题/说明/清空/月切换等。

验证 `assembleDebug`、lint；提交 `feat: add monthly listening stats screen`.

---

### Task 7: 真机验收 + 文档

- 安装最新 APK；确保本地音乐可播放。
- 本地播放 2 首、暂停/继续/切歌；打开设置 → 收听记录，确认总时长/Top/来源/时段；截图 `32-listening-stats-month.jpg`。
- 进程存活时播放 QQ/网易云，返回桌面再打开月报，确认外部源出现、覆盖说明显示。
- 清除记录、月切换、空月份、重启持久化；未授权通知使用权时本地仍记录、无崩溃（可在设置关闭通知使用权测试）。
- 更新 README 功能/界面/已知限制、privacy（收听历史保存与清除、外部覆盖）、release-checklist、device-testing §10。
- 提交 `docs: record monthly listening stats acceptance`.

---

## 验收清单

- [ ] 设置页入口可打开月报；空数据不崩溃。
- [ ] 本地播放产生会话与月报数据；暂停/继续/切歌不重复计时。
- [ ] 进程存活时外部 App 被记录；覆盖说明准确。
- [ ] 月切换、清除、重启持久化正常。
- [ ] 未授权通知使用权：本地可用、外部无数据、无崩溃。
- [ ] 89+ 单测、lint 0 error、APK 构建成功；文档与证据更新。
