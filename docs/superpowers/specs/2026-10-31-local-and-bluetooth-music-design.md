# 本地音乐与蓝牙音乐播放源设计

- 日期：2026-10-31
- 状态：待实现
- 适用版本：0.2 及以上
- 相关文档：[主设计规格](2026-10-29-old-android-media-shell-design.md)、[MIX 2S 真机验收记录](../../device-testing-mix2s.md)

## 1. 背景与目标

当前媒体桌面只展示和控制系统里其他 App 暴露的 MediaSession；媒体源弹窗只列出已安装的媒体 App。用户需要把手机里的本地音乐也作为播放源，并增加一个「蓝牙音乐」源，用于把本地播放通过蓝牙耳机/音箱输出。

目标：

- 在媒体源弹窗中新增「本地音乐」和「蓝牙音乐」两个常驻源。
- 内置本地音乐播放器：扫描系统音频库、歌曲列表、点击播放、上一首/下一首、进度、封面。
- 播放不依赖桌面可见性：离开桌面、锁屏后继续播放；用前台服务 + MediaStyle 通知承载。
- 蓝牙源显示已连接的 A2DP 设备名；音频由系统路由到蓝牙，耳机/车机按键通过 MediaSession/AVRCP 生效。
- 与现有外部 App 源、媒体桌面卡片、单源互斥机制一致，不引入第二套控制通道。

非目标（首版不做）：

- 专辑、歌手、文件夹、搜索、歌单、随机/循环、无缝播放、歌词。
- 接收另一台设备通过蓝牙推来的音频（Android 手机通常不能作为 A2DP 接收端）。
- 下载、网络音乐、媒体库编辑、ID3 标签修改。
- Media3/ExoPlayer（当前离线构建环境没有该依赖缓存）。

## 2. 总体架构

```
LocalMusicActivity ──play queue──▶ LocalPlaybackService
      │                                  │
      │ 读取 MediaStore                   ├─ android.media.MediaPlayer
      │ 查询蓝牙 A2DP 状态               ├─ android.media.session.MediaSession
      ▼                                  ├─ 前台 MediaStyle 通知
   LocalLibrary                          └─ 音频焦点 / 耳机拔出
                                              │
Home（MainActivity / HomeViewModel）          │ MediaSession 被系统暴露
      │                                  ▼
      └── ExternalPlaybackBridge ◀── MediaSessionManager ──▶ 桌面播放卡片
```

- 本地播放器是「本应用内的媒体源」，但注册标准 MediaSession；桌面桥接层无需为它新增特例，继续用现有快照/控制路径。
- 单源互斥守卫把本应用包名纳入媒体源集合：本地播放与外部源双向互斥。
- 蓝牙音乐不是独立播放器，而是「本地播放器的输出源视图」：选择它打开同一个本地音乐库，输出由系统 A2DP 路由决定。

## 3. 媒体源模型

### 3.1 源类型

```kotlin
enum class MediaSourceKind { APP, LOCAL_MUSIC, BLUETOOTH_MUSIC }

data class MediaSourceEntry(
    val id: String,                 // app:<package> / local / bluetooth
    val kind: MediaSourceKind,
    val label: String,              // 「本地音乐」「蓝牙音乐 · WH-1000XM3」
    val sublabel: String?,          // 例如「本地音频库」「未连接」
    val available: Boolean,         // 蓝牙未连接时为 false
    val official: Boolean,
    val packageName: String?,       // 仅 APP 源
)
```

`MediaSourceSelection` 增加纯函数：

- `entries(apps, bluetoothDeviceName): List<MediaSourceEntry>`：已安装官方 App → 本地音乐 → 蓝牙音乐 → 用户添加的媒体 App。
- `currentEntry(entries, lastSourceId): MediaSourceEntry?`：优先匹配持久化 id，否则第一个可用项。
- 蓝牙未连接时仍显示该行（`available=false`），点击给出「去连接」入口，而不是把行隐藏。

### 3.2 持久化

- 新增 SharedPreferences 键 `last_media_source`，值域：`local`、`bluetooth`、`app:<package>`。
- 首次升级读取旧键 `last_launched_media_package` 作为回退；之后写新键。
- 保留旧键写入，兼容旧版本读回。

### 3.3 桌面图标与文案

- 本地音乐：`ic_music_note` 图标；蓝牙音乐：`ic_bluetooth`；App 源继续使用应用图标。
- 弹窗行、桌面左下角图标的 contentDescription 使用源名称；蓝牙未连接时显示「蓝牙音乐（未连接）」。
- 桌面播放卡片仍显示实际正在播放的 MediaSession；源图标只表达「上次选择的源」，不伪造播放状态。

### 3.4 交互流程

- 选择「本地音乐」：检查/申请音频权限 → 打开 `LocalMusicActivity`。
- 选择「蓝牙音乐」：
  - 已有 A2DP 连接：打开 `LocalMusicActivity`，顶部显示「蓝牙输出：设备名」；
  - 无连接：弹窗提示「未连接蓝牙音频设备」，提供「打开蓝牙设置」按钮。
- 选择外部 App：保持现有启动逻辑。
- 从通知/桌面控制本地播放：走 MediaSession，不经过源列表。

## 4. 本地音乐库

### 4.1 数据查询

- 使用 `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`。
- 条件：`IS_MUSIC != 0`、`DURATION > 0`。
- 字段：`_ID`、`TITLE`、`ARTIST`、`ALBUM`、`DURATION`。
- 构建 `content://media/external/audio/media/<id>` 作为播放 URI。
- 排序：`TITLE COLLATE NOCASE ASC`，同标题按 `ARTIST`。
- 映射为 `LocalTrack(id, uri, title, artist, album, durationMs)`；空值使用「未知歌曲」「未知歌手」「未知专辑」。
- 去重按 `id`。

### 4.2 权限

- Build.VERSION.SDK_INT >= 33：`READ_MEDIA_AUDIO`。
- Build.VERSION.SDK_INT <= 32：`READ_EXTERNAL_STORAGE`。
- 进入本地音乐页时检查；未授权显示说明和「去设置」按钮，不自动重复弹窗骚扰。
- 权限被拒绝后返回桌面不崩溃。

### 4.3 封面

- 优先 `MediaMetadataRetriever.embeddedPicture`。
- 回退 `ContentResolver.loadThumbnail`（API 29+）/ `MediaStore.Audio.Albums` 封面（低版本）。
- 内存 LRU 缓存（约 32 张、每张最长边 ≤512）；失败使用 `ic_artwork`。
- 不做磁盘缓存，不阻塞列表滚动：封面在 ViewHolder 中异步加载/复用。

### 4.4 界面

- 独立 `LocalMusicActivity`，暗色 Walkman 风格，与设置页/应用抽屉一致。
- 顶部：返回按钮、标题「本地音乐」；第二行「全部歌曲 · N 首」；蓝牙已连接时显示「蓝牙输出：设备名」。
- 列表：`RecyclerView` + `LocalTrackAdapter`；每行 64dp 以上，封面 48dp、标题、歌手 · 专辑、时长；点击整行播放。
- 当前曲目高亮，并在行尾显示「正在播放/已暂停」文字状态；不提供第二套播放控制。
- 空态：「手机里还没有音乐」；权限拒绝：「需要读取音频权限才能显示本地音乐」+「去设置」。
- 返回键回到媒体桌面；不自动开始播放。

## 5. 本地播放服务

### 5.1 组件

`LocalPlaybackService : Service`，仅本应用启动（不导出）：

- 持有 `MediaPlayer`、当前队列、当前索引、播放状态。
- 持有 `MediaSession`，设置 `MediaSession.Callback`：play/pause/next/previous/seekTo/stop。
- `MediaPlayer` 使用 `AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MUSIC)`；`setWakeMode` 不需要，前台服务保证进程活跃。
- 前台通知：`Notification.MediaStyle` + session token + 上一首/播放暂停/下一首 + 点击打开 `MainActivity`；通知渠道「本地音乐播放」，低重要性。
- 通知在播放时 ongoing；暂停后保留可恢复，但允许用户划掉；服务在通知移除/任务移除时停止。

### 5.2 队列与命令

- 来源页点击歌曲时，调用 `LocalPlaybackContract.playQueue(context, trackIds: LongArray, startIndex: Int)`。
- 服务根据 `trackIds` 从 MediaStore 解析队列与元数据，避免通过 Binder 传递大量字符串/封面。
- 顺序播放；播放完成自动下一首；最后一首结束后进入暂停态并保留队列。
- 上一首/下一首在边界不循环：第一首按上一首、最后一首按下一首时停在当前曲目，不切换到队列另一端（与「不做随机/循环」一致）。
- 错误处理：`MediaPlayer.OnErrorListener` → 暂停、发错误状态、通知显示当前曲目；不崩溃、不自动跳歌。
- 单曲 play 与暂停均通过 MediaSession 更新 `PlaybackState`，桌面进度插值继续使用现有桥接逻辑。

### 5.3 音频焦点与设备事件

- 播放前 `AudioManager.requestAudioFocus`（AUDIOFOCUS_GAIN，AudioFocusRequest API 26+；低版本用旧接口）。
- `AUDIOFOCUS_LOSS` → 暂停；`AUDIOFOCUS_LOSS_TRANSIENT` → 暂停；`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` → 降低音量（简单实现或暂停）。
- 注册 `ACTION_AUDIO_BECOMING_NOISY`：耳机拔出/蓝牙断开时暂停。
- 蓝牙耳机/车机按键由系统按 MediaSession 优先级路由，不再单独注册 MediaButtonReceiver。

### 5.4 与桌面/单源互斥的集成

- 把本应用包名加入 `supportedPlaybackPackages`，使：
  - 本地播放时桌面桥接层显示其卡片/进度/控制；
  - `SingleSourcePlaybackGuard` 在本地播放与外部源之间双向暂停；
  - 外部源开始时，本地 `MediaSession.Callback.onPause` 被调用，服务暂停；
  - 本地开始时，其他已接入源被暂停。
- 不改变 `ExternalPlaybackBridge` 的公开接口；不新增轮询。
- 未授予通知使用权时，本地播放仍可用，但桌面不显示跨应用卡片（与现有可选能力一致）。

## 6. 蓝牙音乐源

### 6.1 检测

- `BluetoothOutputSource`：通过 `AudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)` 查找 `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP` 输出，读取 `productName` 作为设备名。
- 不需要 `BLUETOOTH` / `BLUETOOTH_CONNECT` 权限；音频路由和耳机/车机按键全部由系统 MediaSession/AVRCP 处理。
- 未连接/蓝牙关闭：源行 `available=false`，副标题「未连接」。
- 打开系统蓝牙设置：`Settings.ACTION_BLUETOOTH_SETTINGS`。

### 6.2 播放与按键

- 音频输出由系统 A2DP 自动路由；不主动切换音频路由、不修改音量。
- 耳机/车机的播放、暂停、上一首/下一首、音量由系统 MediaSession/AVRCP 处理。
- 蓝牙断开时，系统自动切回扬声器/听筒；`ACTION_AUDIO_BECOMING_NOISY` 停止播放，避免外放尴尬。
- 源名称在弹窗打开、本地音乐页 onResume 时刷新；不做常驻广播监听。

## 7. 权限与清单

Manifest 新增：

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- `LocalPlaybackService`：`android:exported="false"`、`android:foregroundServiceType="mediaPlayback"`。
- `LocalMusicActivity`：`android:exported="false"`。
- 运行时请求：
  - 音频权限：进入本地音乐页时；
  - 通知权限（API 33+）：进入本地音乐页或首次播放时请求，拒绝不阻塞播放。
- 不申请 `BLUETOOTH` / `BLUETOOTH_CONNECT`、存储写入、后台定位、忽略电池优化。

## 8. 错误与边界

- 无音频文件：空态 + 提示如何添加音乐。
- 权限拒绝：说明 + 设置入口；返回桌面正常。
- 音频文件损坏/格式不支持：暂停、通知保留当前曲目、桌面卡片保留；提示「无法播放这首歌曲」。
- 蓝牙在播放中断开：暂停，避免突然外放；再次连接后可手动继续。
- 本地播放器与外部源同时被触发：由单源互斥守卫统一暂停旧源，不出现叠音。
- 服务被系统杀死：桌面卡片消失；再次进入本地音乐页可重新播放，不自动恢复（首版）。
- 通知权限被拒：前台服务仍可运行，通知可能不显示；不阻塞播放。

## 9. UI 与文案

- 源弹窗：本地音乐、蓝牙音乐行与现有媒体源行同一视觉规格；蓝牙未连接行置灰但仍可点（打开设置）。
- 本地音乐页：标题、曲目数、蓝牙输出提示、返回按钮；触控目标 ≥56dp（行高 ≥64dp）。
- 文案：
  - 「本地音乐」「蓝牙音乐」「未连接」「蓝牙输出：%1$s」「去连接」「需要音频权限」「去设置」「手机里还没有音乐」「无法播放这首歌曲」。
- 不新增品牌图标、拟物元素、动画；沿用现有黑色/暖白/琥珀视觉。

## 10. 实施范围（文件级）

新增：

- `local/LocalTrack.kt`
- `local/LocalLibrary.kt`（MediaStore 查询 + 纯排序/过滤逻辑）
- `local/BluetoothA2dpOutput.kt`
- `local/LocalPlaybackContract.kt`
- `local/LocalPlaybackService.kt`
- `ui/LocalMusicActivity.kt`
- `ui/LocalTrackAdapter.kt`
- `res/layout/activity_local_music.xml`
- `res/layout/item_local_track.xml`
- `res/drawable/ic_music_note.xml`
- `res/drawable/ic_bluetooth.xml`

修改：

- `ui/MediaSourceSelection.kt`（entries/currentEntry、纯逻辑）
- `MainActivity.kt`（弹窗行、图标映射、选择处理、prefs）
- `prefs/UserPreferences.kt`（last_media_source）
- `media/ExternalPlaybackBridge.kt`（`supportedPlaybackPackages` 增加本应用包名）
- `AndroidManifest.xml`
- `res/values/strings.xml`
- 对应单元/仪器化测试

不改：

- 现有外部 App 启动行为、通知使用权流程、默认桌面逻辑、应用抽屉、单源互斥算法本身。

## 11. 测试与验收

### 11.1 单元测试

- `LocalLibrary`：过滤 `IS_MUSIC`/duration、排序、空值回退、去重。
- `MediaSourceSelection`：内置源顺序、蓝牙未连接/已连接、旧 prefs 回退、当前源匹配。
- `PlaybackSnapshot`/单源互斥：本应用包名纳入暂停策略。
- 本地队列：上一首/下一首边界、播放完成推进（把队列逻辑抽成纯类测试）。

### 11.2 仪器化测试

- 媒体源弹窗包含「本地音乐」「蓝牙音乐」行。
- 权限拒绝时本地音乐页显示说明和设置入口（不崩溃）。
- 应用抽屉/设置页/主界面现有测试不回归。

### 11.3 真机验收（MIX 2S / Android 10）

1. 生成 2–3 个测试音频（WAV/MP3）推入 `/sdcard/Music`，触发媒体扫描；进入本地音乐库应列出。
2. 点击歌曲：桌面显示标题/歌手/封面/进度；播放/暂停、上一首/下一首、拖动进度生效。
3. 按 Home、锁屏：继续播放；通知栏出现 MediaStyle 通知，上一首/暂停/下一首可用；桌面卡片状态同步。
4. 本地播放中启动 QQ 音乐：本地自动暂停；QQ 播放中回到本地音乐继续：QQ 自动暂停（单源互斥双向）。
5. 无蓝牙设备：源列表显示「未连接」，点击可打开系统蓝牙设置。
6. 有蓝牙耳机/音箱（用户配合）：显示设备名；声音从蓝牙输出；耳机按键播放/暂停/下一首有效。
7. 重启/重新安装后不崩溃；权限拒绝路径、空库路径可返回桌面。

### 11.4 证据

- 本地音乐库截图、桌面卡片截图、通知截图、蓝牙源状态截图、单源互斥测试截图；放入 `docs/device-evidence/`，并在 README/验收记录中引用。

## 12. 风险与约束

- **平台 MediaPlayer 格式支持有限**：优先支持 mp3/flac/aac/ogg/wav；损坏文件走错误态。
- **MIUI 后台限制**：前台服务 + 通知需在真机验证；不申请忽略电池优化，接受系统回收后不自动恢复。
- **蓝牙实测依赖硬件**：没有蓝牙耳机时只能验证检测、未连接状态和设置跳转；AVRCP 行为标为「硬件实测项」。
- **蓝牙检测**：使用 AudioManager 输出设备判断 A2DP，不需要蓝牙权限；不同厂商对 `productName` 的返回可能为空，此时显示「蓝牙音频」。
- **通知权限（Android 13+）**：设备当前 API 29 不涉及；代码按版本处理，避免高版本崩溃。
- **包可见性/MediaStore**：Android 11+ 查询 MediaStore 不需要 `queries`；存储权限仍按版本处理。
- **单源互斥**：把本应用加入白名单后需验证不会误暂停自身；守卫始终保留最新播放的会话。

## 13. 验收标准

- [ ] 媒体源弹窗出现「本地音乐」「蓝牙音乐」，蓝牙状态正确。
- [ ] 本地音乐库能扫描、排序、显示封面并播放。
- [ ] 桌面卡片、进度、控制与外部源体验一致。
- [ ] 后台/锁屏继续播放，MediaStyle 通知可控制。
- [ ] 本地与外部源双向单源互斥。
- [ ] 蓝牙设备名显示；无设备时提供设置入口；音频路由由系统完成。
- [ ] 权限拒绝、空库、坏文件、蓝牙断开均不崩溃。
- [ ] 现有外部源、应用抽屉、设置、默认桌面行为无回归。
