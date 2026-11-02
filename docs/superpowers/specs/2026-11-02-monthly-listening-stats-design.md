# 月度收听记录设计

- 日期：2026-11-02
- 状态：已实现（2026-11-02，MIX 2S 验收；旋转/清空/进程缺口展示未逐项真机复测）
- 适用版本：0.2 及以上
- 相关文档：[主设计规格](2026-10-29-old-android-media-shell-design.md)、[本地与蓝牙音乐设计](2026-10-31-local-and-bluetooth-music-design.md)

> **2026-11-02 范围调整（用户决定）**：收听记录只统计外部 App（MediaSession）的播放，**本地音乐不计入**。下文涉及“本地音乐上报/本地来源”的段落保留为早期设计记录，实际实现以本注、README 与真机验收为准。

## 1. 背景与目标

媒体桌面已经能播放本地音乐，并能观察外部音乐/播客 App 的 MediaSession。用户希望有一个类似网易云「听歌报告」、小宇宙「收听报告」的功能，首版聚焦月度回顾。

目标：

- 记录外部媒体 App 的收听会话（网易云/QQ/小宇宙 + 用户添加的媒体 App）；本地音乐不计入。
- 外部 App 只在**本应用进程存活且通知使用权开启**时通过 MediaSession 观察记录；不读取通知正文。
- 提供月度汇总页面：总时长、活跃天数、播放次数、Top 歌曲/歌手/专辑、来源占比、24 小时时段分布、最长一次收听。
- 数据只保存在本机，不上传、不联网；提供清除入口。
- 所有会话按来源 App 区分，并明确外部数据的覆盖缺口；本地音乐不进入报告。

非目标（首版）：

- 年度回顾、分享图/导出、图表库、云同步、跨设备。
- 从第三方 App 读取账号历史或私有 API。
- 进程被杀/未授权期间的外部播放补录。
- 跨月长会话按时间拆分（首版按会话开始时间归属月份，见 §12）。
- 播放完成度、评分、标签、社交。

## 2. 总体结构

```
LocalPlaybackService ──local events──┐
                                     ├──▶ ListeningHistoryRepository
PlaybackHistoryObserver ─external───┘        │
 (Application, MediaSession)                  ├─ ListeningSessionTracker（纯状态机）
                                              ├─ ListeningStatsStore（SQLite）
                                              ▼
                                    ListeningStatsActivity（月报）
                                              ▲
                                     SettingsActivity 入口
```

- `PlaybackHistoryObserver` 挂在 `MediaShellApplication`，跟随进程生命周期；不新建常驻前台服务。
- 外部 App 的记录走 MediaSession，需要通知使用权；未授权时不记录外部会话。

## 3. 数据模型

```kotlin
enum class ListeningSourceKind { LOCAL, EXTERNAL }

data class ListeningSession(
    val uid: String,              // UUID，客户端生成，异步写库不需要自增 id
    val sourceKind: ListeningSourceKind,
    val packageName: String?,     // 外部源包名；本地为 null
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

- 同一来源、同包名、同标题/歌手/专辑/时长视为同一身份（identity）。
- 本地音乐身份来自 `LocalTrack.id` 对应的元数据；外部身份来自 MediaSession metadata。
- `durationMs` 未知时为 0；`listenedMs` 只累计观察到/本地上报的播放时间，不根据总时长猜测。

## 4. 采集

### 4.1 本地音乐

`LocalPlaybackService` 在以下时机上报：

- 开始/切换曲目：`snapshot(playing=true, position=0, duration)`；
- 播放/暂停切换：`snapshot(playing=…, position=currentPosition)`；
- 播放完成、错误、停止、销毁：先上报最终状态，再 `flush()`。

本地事件直接进入 `ListeningSessionTracker`，不经过 MediaSession，因此与通知使用权无关。

### 4.2 外部 App

`PlaybackHistoryObserver`：

- 通过 `MediaSessionManager.addOnActiveSessionsChangedListener(listener, MediaNotificationListener component, handler)` 监听活跃会话；
- 对每个会话注册 `MediaController.Callback`（playback/metadata/destroyed），事件到达后重新评估；
- 仅记录包名属于「预置媒体源 + 用户添加的媒体 App」的会话，排除本应用（本地已单独记录）；
- 选择当前正在播放的会话；没有 playing 时，若 tracker 当前打开的会话仍存在，则上报一次暂停快照；会话全部消失时 flush；
- 无通知使用权时不启动 observer；授权后由 `MainActivity`/`MediaNotificationListener` 触发启动。

覆盖缺口（必须写入 UI 与文档）：

- 进程被系统杀死后无法继续记录外部播放；
- 通知使用权关闭、系统未回调或 MediaSession 不暴露的 App 无记录；
- 外部 App 的暂停/继续若没有事件，可能按最后观察到的时间结束。

### 4.3 会话状态机（纯逻辑）

`ListeningSessionTracker`：

- `onObserved(snapshot, nowMs)`：身份变化 → 关闭旧会话并开启新会话（仅当 playing）；相同身份且 playing → 累加 `now - lastObservedAtMs`；暂停 → 累加最后一段后保持会话打开；继续 → 重置 lastObservedAt；
- `flush(nowMs)`：累加最后一段（若 playing），标记 closed；
- `closeStaleOpenSessions()`：应用启动时把上次进程遗留的未关闭会话关闭（不补时长）。
- 只做内存状态，写库通过 `ListeningSessionSink` 接口异步执行，便于单测。

## 5. 存储

`ListeningStatsStore`（SQLiteOpenHelper，数据库 `listening_stats.db`）：

```sql
CREATE TABLE listening_sessions(
    uid TEXT PRIMARY KEY,
    source_kind TEXT NOT NULL,
    package_name TEXT,
    title TEXT NOT NULL,
    artist TEXT NOT NULL,
    album TEXT NOT NULL,
    started_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    listened_ms INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    last_position_ms INTEGER NOT NULL,
    closed INTEGER NOT NULL
);
CREATE INDEX idx_listening_started ON listening_sessions(started_at);
```

- 写操作通过单线程 `Executor` 顺序执行；不阻塞主线程。
- 启动时关闭遗留 open 会话；报告读取在同一 executor 上查询后回主线程。
- 清除数据：删除表内所有行（保留数据库结构）。
- 不保存封面图片、不保存通知内容、不保存账号信息。

## 6. 月报聚合（纯逻辑）

`ListeningStatsAggregator` 输入会话列表 + 月份 + 时区，输出：

```kotlin
data class MonthlyListeningSummary(
    val monthLabel: String,          // 2026年11月
    val totalListenedMs: Long,
    val sessionCount: Int,
    val activeDays: Int,
    val topTracks: List<RankedItem>, // label=标题, detail=歌手, listenedMs
    val topArtists: List<RankedItem>, // label=歌手, detail=null
    val topAlbums: List<RankedItem>, // label=专辑, detail=null
    val sources: List<SourceShare>,  // 本地 / 外部包名 + 时长 + 占比
    val hours: LongArray,            // 24 小时收听时长
    val longestSessionMs: Long,
)
```

规则：

- 排名标签统一 `trim()`；空白标题/歌手/专辑分别回退「未知歌曲/未知歌手/未知专辑」，空白外部包名回退「外部应用」。
- 歌曲按「标题 + 歌手」分组，同名不同歌手不合并；歌手、专辑按规范化后的名称分组。
- 按 `startedAtMs` 落在目标月份过滤；时区使用设备默认时区。
- 活跃天数：按会话开始日去重。
- Top 排序：先按累计收听时长降序，再按名称/包名稳定排序；Top 5。
- 来源占比：仅外部按包名分组（显示 App 名称，失败回退包名）。
- 时段分布：`startedAtMs` 的小时（0–23），累加该会话 listenedMs。
- 空数据返回零摘要，不抛异常。

## 7. 界面

入口：设置页新增「收听记录」按钮，打开 `ListeningStatsActivity`。

页面：

- 顶部：返回、标题「收听记录」；月份切换 `‹ 2026年11月 ›`；
- 覆盖说明：「外部 App 仅在媒体桌面进程存活且通知授权时记录，数据可能不完整」；
- 汇总卡：总收听时长、活跃天数、播放次数；
- Top 5 歌曲 / 歌手 / 专辑；
- 来源占比（各外部 App，按时长）；
- 24 小时时段分布（24 根细条，纯 View，无图表库）；
- 最长一次收听；
- 「清除收听记录」按钮（二次确认）。

视觉沿用黑底/暖白/琥珀，不做动画、不做弹窗式年度仪式。

## 8. 隐私与权限

- 不需要新权限；沿用通知使用权（可选）；本地音乐记录不依赖通知使用权。
- 数据字段仅包含播放元数据与时长，保存在本机 SQLite；不上传、不联网。
- 设置页提供清除；卸载应用删除数据库。
- 更新 `docs/privacy.md`：新增收听记录数据类型、保存位置、清除路径、外部覆盖限制。

## 9. 实施文件

新增：

- `app/src/main/java/io/github/xhzair/refresholdphone/stats/ListeningSourceKind.kt`
- `.../stats/ListeningSession.kt`
- `.../stats/ListeningSessionTracker.kt`
- `.../stats/ListeningStatsStore.kt`
- `.../stats/ListeningHistoryRepository.kt`
- `.../stats/MonthlyListeningSummary.kt`
- `.../stats/ListeningStatsAggregator.kt`
- `.../stats/PlaybackHistoryObserver.kt`
- `.../ui/ListeningStatsActivity.kt`
- `app/src/main/res/layout/activity_listening_stats.xml`
- 单元测试：tracker、aggregator、时间格式

修改：

- `MediaShellApplication.kt`：创建 repository/observer，启动时关闭遗留会话；
- `LocalPlaybackService.kt`：上报本地播放事件；
- `MainActivity.kt` / `MediaNotificationListener.kt`：通知授权后确保 observer 启动；
- `SettingsActivity.kt`：入口；
- `AndroidManifest.xml`：注册 Activity；
- `strings.xml`、`README.md`、`docs/privacy.md`、`docs/release-checklist.md`、`docs/device-testing-mix2s.md`。

## 10. 测试与验收

单元测试：

- Tracker：开始→累加→暂停→继续→切歌→flush；身份变化；空/未知元数据忽略；暂停不增加时长；恢复继续同一身份。
- Aggregator：月份过滤、时区边界、Top 排序/并列、活跃天数、来源分组、小时分布、空数据、跨月长会话按开始时间归属。
- 时间/时长格式工具。

真机验收（MIX 2S）：

1. 播放外部音乐 1–2 首，暂停/继续/切歌；打开「收听记录」，确认总时长、Top、来源为对应 App；本地音乐不计入。
2. 桌面进程存活时播放 QQ/网易云；回到桌面后打开月报，确认外部源出现且时长合理；页面显示覆盖说明。
3. 杀掉本应用进程后播放外部 App；重开应用，确认缺口不被伪造（顶部说明与实际数据一致）。
4. 月切换、空月份、清除记录、重启后数据仍在。
5. 未授予通知使用权时：无外部记录，页面不崩溃；本地音乐始终不计入。
6. 播放服务暂停/退到后台时，记录能在下一次事件后正确收尾（最长延迟为下一次事件）。

## 11. 风险与限制

- 外部覆盖不完整：进程被杀、未授权、无事件期间会丢失；报告必须明示，不能与官方年度报告等同。
- 跨月长会话按开始时间归属，可能把整段算入开始月份；首版接受并记录。
- SQLite 写入异步，进程在写入队列未清空前被杀可能丢失最后一条更新；可接受。
- 不引入 Room、图表库或网络依赖。
- 旧设备性能：事件驱动写入，不做轮询；不增加常驻前台服务。

## 12. 验收标准

- [ ] 设置页有「收听记录」入口，月报页面可打开且不崩溃。
- [ ] 外部 App 播放产生正确的会话与月报数据；本地音乐不产生记录。
- [ ] 进程存活时外部 App 播放被记录，来源占比与覆盖说明正确。
- [ ] 暂停/继续/切歌/停止不产生明显重复或漏记。
- [ ] 月切换、空数据、清除记录、重启持久化正常。
- [ ] 未授权通知使用权时无外部数据、页面不崩溃；本地音乐不记录。
- [ ] 隐私文档、README、验收记录已更新。
