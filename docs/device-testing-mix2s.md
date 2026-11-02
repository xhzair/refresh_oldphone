# MIX 2S 真机验收记录（0.1 MVP）

- 验收日期：2026-10-30（北京时间，首轮 00:30–01:05；第二轮补测 11:50–12:00）
- 代码状态：`feature/media-shell-mvp` 已合入 `main`（merge commit `8f1d308`），版本 0.1.0（versionCode 1）；合入后 main 上 `clean testDebugUnitTest lintDebug assembleDebug` 通过
- APK：`app/build/outputs/apk/debug/app-debug.apk`（约 4.8 MB，debug 签名）
- 验收设备：Xiaomi MIX 2S（`polaris`），Android 10 / API 29，MIUI 12.5，1080 × 2160、440 dpi
- 验收人：自动化 adb 驱动 + 人工核对截图
- 证据截图：[docs/device-evidence/](device-evidence/)（分辨率已压缩至 540 宽）

> 注：本记录对应 0.1 MVP 时的首页 App 卡片行实现。2026-10-30 后续按用户反馈改为单个紧凑媒体源图标 + 弹窗切换（见 `docs/superpowers/specs/...` §5.5），并移除横屏固定文案、改为封面模式（§5.6），因此文中“点击卡片”、横屏文案等步骤与截图反映的是当时版本。

## 1. 设备基线

| 项目 | 结果 |
| --- | --- |
| 型号 / 代号 | MIX 2S / `polaris` |
| Android / API | 10 / 29 |
| 分辨率 / 密度 | 1080 × 2160 / 440 dpi |
| 验收前默认桌面 | `com.miui.home/.launcher.Launcher`（`isDefault=true`） |
| 网易云音乐 | 已安装 9.6.15（`firstInstallTime=2026-07-16 23:10:19`） |
| QQ 音乐 / 小宇宙 | 未安装 |
| 通知使用权（验收前） | 未包含本应用 |

## 2. 验收矩阵

| # | 验收项 | 结果 | 证据与说明 |
| --- | --- | --- | --- |
| 1 | 未设为默认桌面时作为普通 App 打开 | 通过 | `am start` COLD 启动成功；[01-portrait-home.png](device-evidence/01-portrait-home.png) |
| 2 | 竖屏播放优先布局 | 通过 | 时钟/日期/网络/电量在上，播放区居中，应用列表在下方；[01-portrait-home.png](device-evidence/01-portrait-home.png) |
| 3 | 横屏环境时钟优先布局 | 通过 | 左侧大时钟 + 日期 + 静默文案，右侧播放信息，底部应用行；[02-landscape-home.png](device-evidence/02-landscape-home.png) |
| 4 | 网易云音乐卡片启动官方 App | 通过 | 点击卡片后前台为 `com.netease.cloudmusic/.activity.PlayerActivity`；按 Home 返回 MIUI 桌面（未接管 HOME 时符合预期） |
| 5 | QQ 音乐、小宇宙缺失状态 | 通过 | 显示“未安装”，不崩溃、不弹错误循环；见首页截图 |
| 6 | Wi-Fi/移动数据在官方 App 内可用 | 通过（有限） | 设备 Wi-Fi 在线（状态栏 + 首页“Wi‑Fi”），官方 App 正常启动；未在播放中切换网络 |
| 7 | 通知使用权授权后显示 MediaSession 元数据 | 通过 | 系统中存在 `com.netease.cloudmusic` 会话（`state=2` 暂停，`metadata=Let Me Go, Daniel Caesar`，actions=822）；首页显示封面、标题、作者、来源、播放进度与三个控制按钮；[03-media-session-card.png](device-evidence/03-media-session-card.png) |
| 8 | 通知使用权状态展示 | 通过 | 设置页显示“通知使用权：已开启”；[04-settings-top.png](device-evidence/04-settings-top.png) |
| 9 | 撤销通知使用权后降级 | 通过 | 撤销后首页回到空状态并重新显示“开启播放状态显示”，应用仍可启动；[10-permission-revoked.png](device-evidence/10-permission-revoked.png)；`enabled_notification_listeners` 恢复为验收前列表 |
| 10 | 设置页完整性 | 通过 | 方向、常亮、横屏文案、附加应用、通知权限、6 个系统快捷入口、默认桌面与恢复、清除本地配置、版本与仓库链接；[04-settings-top.png](device-evidence/04-settings-top.png)、[05-settings-bottom.png](device-evidence/05-settings-bottom.png) |
| 11 | 系统快捷入口 Intent 可解析 | 通过 | Wi-Fi 按钮实际打开 `com.android.settings/.Settings$WifiSettingsActivity`，返回键回到设置页；其余 action 在设备上的解析结果见下表 |
| 12 | 暂时接管默认桌面 | 通过 | `cmd package set-home-activity` 后按 Home 回到本应用，`isDefault=true`；[06-default-home.png](device-evidence/06-default-home.png) |
| 13 | 通过“恢复系统桌面”恢复 MIUI 桌面 | 通过 | 设置页按钮打开系统默认主屏幕应用选择器（`DefaultAppActivity`），选择“系统桌面”后按 Home 回到 `com.miui.home/.launcher.Launcher`，`isDefault=true`；[07-system-home-chooser.png](device-evidence/07-system-home-chooser.png)、[08-restored-miui-home.jpg](device-evidence/08-restored-miui-home.jpg) |
| 14 | 清除本地配置 | 通过 | 确认框文案正确；确认后 `shared_prefs/media_shell.xml` 变为 `<map />`，网易云安装信息未变化，应用可继续启动；[09-clear-config-confirm.png](device-evidence/09-clear-config-confirm.png) |
| 15 | 旋转不打断播放 | 通过（第二轮补测） | 实际播放中竖屏→横屏→竖屏，`PlaybackState` 始终为 `state=3`（PLAYING），`AudioTrack` 保持 `state:started`，曲目从 “Let Me Go” 推进到 “Breathe (In the Air)”；[11-playing-rotation-landscape.png](device-evidence/11-playing-rotation-landscape.png)、[12-playing-rotation-portrait.png](device-evidence/12-playing-rotation-portrait.png) |
| 16 | 锁屏/解锁、进程重建 | 通过（分项，第二轮补测） | 播放中电源键锁屏 6 秒后仍 `state=3` 且音轨 started；解锁后首页恢复并显示播放卡片 [13-post-unlock-playing.png](device-evidence/13-post-unlock-playing.png)；旋转重建与 `force-stop` 后按 Home 冷启动（新 pid）均正常渲染首页、无崩溃；MIUI 忽略 `always_finish_activities`，该方式无法触发销毁（详见第 7 节） |
| 17 | 重启后持久化/无崩溃循环 | 通过（受限说明，第二轮补测） | 默认桌面为媒体桌面时重启，`sys.boot_completed=1` 用时约 29 秒；重启后 `resolve-activity` 显示 `isDefault=true` 且默认组件仍为 `io.github.xhzair.refresholdphone/.MainActivity`；crash buffer 无本应用记录。设备有安全锁屏，首次解锁前不启动 HOME 属 Android 预期行为，用户解锁后按 Home 正常进入媒体桌面（详见第 7 节） |
| 18 | 暗光可读性 | 通过（人工目视） | 深夜环境截图对比度正常；正文 ≥18sp、触控目标 ≥56dp |

### 系统快捷入口在 MIX 2S 上的解析结果

| 入口 | 设备解析结果 |
| --- | --- |
| `android.settings.WIFI_SETTINGS` | `com.android.settings/.Settings$WifiSettingsActivity` |
| `android.settings.DATA_ROAMING_SETTINGS` | `android/com.android.internal.app.ResolverActivity`（多候选，系统选择器） |
| `android.settings.BLUETOOTH_SETTINGS` | `com.android.settings/.Settings$BluetoothSettingsActivity` |
| `android.settings.SOUND_SETTINGS` | `com.android.settings/.MiuiSoundSettingsActivity` |
| `android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` | `com.android.settings/.Settings$NotificationAccessSettingsActivity` |
| `android.settings.HOME_SETTINGS` | `com.android.permissioncontroller/.../HomeSettingsActivity` |
| `android.settings.SETTINGS` | `com.android.settings/.MiuiSettings` |
| `android.settings.APPLICATION_DETAILS_SETTINGS`（带 package data） | `com.android.settings/.applications.InstalledAppDetails` |

## 3. 性能与稳定性

- 前台 `dumpsys meminfo`：`TOTAL PSS = 74,802 KB`（约 73 MB，含 Graphics 约 36 MB），Java Heap 约 8.5 MB；属调试构建观察值，不作跨机型基准。
- `logcat -b crash`：无本应用崩溃记录；本次验收期间未出现 `FATAL EXCEPTION`。
- 首页无活跃播放时不轮询：界面仅由电池/网络回调与 MediaSession 事件驱动；离开首页时 `HomeViewModel.onStop()` 注销播放与状态源（由单元测试覆盖 start/stop 计数）。

## 4. 测试环境限制与工作绕过（重要）

1. **`connectedDebugAndroidTest` 在离线环境不可用**：AGP 的 UTP 组件 `com.android.tools.utp:gradle-work-action:32.4.1` 未预缓存且外网 Maven 不可达。等价做法是手动安装两个 APK 后执行：
   - `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   - `adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
   - `adb shell am instrument -w io.github.xhzair.refresholdphone.test/androidx.test.runner.AndroidJUnitRunner`
   - 结果：`OK (7 tests)`（MainActivityTest 4 项 + SettingsActivityTest 3 项）。
2. **MIUI 后台启动限制**：`MediaShellApplication` 目标进程由 instrumentation 从后台拉起时，MIUI 的 `MIUIOP(10021)` 会拒绝 Activity 启动，表现为测试一直等待 `ActivityScenario`。临时授权后可正常执行：
   - `adb shell appops set io.github.xhzair.refresholdphone 10021 allow`
   - 测试完成后恢复：`adb shell appops set io.github.xhzair.refresholdphone 10021 default`
   - 注意：重新安装 APK 会重置该 appop，需要重新授权。
   - 该限制只影响测试进程的后台启动，不影响应用正常工作（作为默认桌面或前台打开时启动媒体 App、系统设置均正常）。
3. **Debug 签名**：沙箱环境无法写入用户目录的 `~/.android/debug.keystore`，构建时使用 `ANDROID_USER_HOME` 指向工作区目录生成调试密钥；因此覆盖安装前需卸载旧签名版本。

## 5. 验收结束后的设备状态

| 项目 | 恢复结果 |
| --- | --- |
| 默认桌面 | `com.miui.home/.launcher.Launcher`（`isDefault=true`）✓ |
| 通知使用权 | 恢复为验收前列表（不含本应用）✓ |
| 动画缩放 | 三项恢复为 1 ✓ |
| MIUIOP(10021) | 恢复 `default` ✓ |
| 测试 APK | 已卸载 ✓ |
| 本应用 | 保留安装（debug APK），未设置为默认桌面 ✓ |

## 6. 结论

里程碑 1 的核心路径在 MIX 2S 上通过：普通 App 打开、双方向布局、官方 App 启动、通知授权后的 MediaSession 展示、撤销后的降级、系统入口、HOME 接管与恢复、清除配置均符合设计规格。

第二轮补测（第 7 节）进一步覆盖了实际播放中的旋转连续性、锁屏/解锁、Activity 重建、进程冷启动和默认桌面重启持久化，全部通过。剩余未覆盖项只包括设计规格中声明的后续兼容矩阵：Android 6/7 32 位、1–2 GB RAM、非 MIUI 设备与平板尺寸设备。

## 7. 第二轮补测详情（2026-10-30 11:50–12:00）

补测前提：应用已安装（0.1.0 debug），通知使用权已临时授予，网易云音乐 9.6.15 正在实际播放音频；测试结束后恢复通知授权与默认桌面。

### 7.1 播放中旋转

1. 将“媒体桌面”临时设为默认桌面，网易云音乐开始播放（`dumpsys media_session`：`state=3`，`actions=822`）。
2. 锁定系统旋转后依次切竖屏 → 横屏 → 竖屏，每次等待 3 秒：
   - 三个时点 `PlaybackState` 均为 `state=3`（PLAYING）；
   - `dumpsys audio` 中网易云 `AudioTrack` 持续 `state:started`；
   - 期间曲目从 “Let Me Go” 自动推进到 “Breathe (In the Air)”，说明播放未被旋转中断或重启；
   - 首页在横屏/竖屏都正确显示封面、曲名、作者、来源与暂停按钮（[11-playing-rotation-landscape.png](device-evidence/11-playing-rotation-landscape.png)、[12-playing-rotation-portrait.png](device-evidence/12-playing-rotation-portrait.png)）。
3. 测试后恢复 `accelerometer_rotation=1`。

结论：旋转只重建本应用 UI，不触碰外部官方 App 的播放会话，满足“旋转不打断播放”目标。

### 7.2 锁屏/解锁

1. 播放中按电源键熄屏并锁定 6 秒：`dumpsys media_session` 仍为 `state=3`，`AudioTrack state:started`。
2. 唤醒后设备要求密码/指纹解锁（该 MIX 2S 设有安全锁屏），由用户手动解锁。
3. 解锁后首页恢复正常，播放卡片仍显示当前曲目与控制按钮（[13-post-unlock-playing.png](device-evidence/13-post-unlock-playing.png)）。

结论：锁屏不会暂停外部播放；解锁后媒体桌面可正常恢复展示。

### 7.3 Activity 重建与进程冷启动

- **旋转触发重建**：7.1 中每次旋转都会重建 `MainActivity`，播放卡片与状态由 ViewModel/外部会话恢复，无闪烁丢状态。
- **force-stop 后冷启动**：作为默认桌面时执行 `am force-stop io.github.xhzair.refresholdphone`，系统随后重建 HOME 进程；按 Home 回到 `MainActivity`（新进程 pid），首页正常渲染，`logcat -b crash` 无本应用记录。验证了“下一次启动仍进入安全首页、不形成崩溃循环”。
- **`always_finish_activities` 在 MIUI 上无效**：打开该开发者选项后从设置页返回，`MainActivity` 记录未被销毁，说明 MIUI 忽略了该全局开关；这是平台行为，不能作为应用缺陷。Activity 重建覆盖改由旋转与 force-stop 场景证明。

### 7.4 重启持久化

1. 将“媒体桌面”设为默认桌面（`isDefault=true`），执行 `adb reboot`。
2. 重启完成：`sys.boot_completed=1` 用时约 29 秒。
3. 重启后检查：
   - `resolve-activity ... HOME` 显示 `isDefault=true`，默认组件仍为 `io.github.xhzair.refresholdphone/.MainActivity`，说明默认桌面选择在 MIUI 上跨重启保留；
   - `logcat -b crash` 无本应用崩溃（仅有系统 `init` 的 SIGABRT 噪声，与本应用无关）；
   - 因设备设有安全锁屏，首次解锁前 Android 不会启动 HOME，属预期行为；用户解锁后按 Home 正常进入媒体桌面。
4. 测试后通过 `set-home-activity` 恢复 MIUI 桌面并核对 `isDefault=true`。

### 7.5 补测中发现的 MIUI 行为（非应用缺陷）

- **通知监听服务重绑（2026-10-30 已修复）**：在应用进程未运行或设备重启后，即使“通知使用权”已列入系统授权列表，MIUI 也不会立即绑定 `MediaNotificationListener`，当时首页会停留在“开启播放状态显示”状态，需要在应用运行时重新切换一次授权（或重启应用进程）才能连接。修复方式：播放桥的可用性判断改为只依赖“已启用的通知监听组件授权”（`NotificationManagerCompat.getEnabledListenerPackages`），不再要求监听服务处于绑定状态；`MediaSessionManager` 本身就以已启用组件的授权为准。修复后已验证应用更新/进程重建后无需切换授权即可继续显示播放状态。
- **安全锁屏**：设备启用密码/指纹后，adb 无法代替解锁；重启与解锁验证需要用户手动参与。本次已由用户完成一次解锁。
- **`always_finish_activities`**：MIUI 忽略该开发者选项（见 7.3）。

### 7.6 补测后设备状态

默认桌面已恢复 `com.miui.home/.launcher.Launcher`（`isDefault=true`）；通知使用权恢复为原列表（不含本应用）；动画缩放=1；`MIUIOP(10021)=default`；本应用保留安装且未设为默认桌面；媒体播放已暂停。

## 8. WebView 更新与小宇宙验证（2026-10-31）

- 设备原 Android System WebView 为 83.0.4103.101（2020 年版）；小宇宙 3.4.1 启动后进入 WebView 登录页并黑屏。
- 直接更新系统 WebView 被 MIUI 拒绝：`adb install`、`pm install`、系统安装器均返回 `INSTALL_FAILED_USER_RESTRICTED`，即使打开“USB 安装”也无法替换系统 WebView。
- 改用 Google 签名的 **Android System WebView Beta 158.0.8127.2**（`com.google.android.webview.beta`，minSdk 29，arm64/armv7，versionCode `812700201`）作为普通应用安装，并切换到该实现：
  `adb shell cmd webviewupdate set-webview-implementation com.google.android.webview.beta`
  `dumpsys webviewupdate` 显示 Current/Preferred 均为 beta 158。
- 结果：小宇宙 3.4.1 正常进入首页与单集页，播放《积极表态 EP.6》；媒体桌面显示标题、作者、封面、进度与暂停控制（[26-xiaoyuzhou-card.jpg](device-evidence/26-xiaoyuzhou-card.jpg)）。
- 单源互斥再次验证：小宇宙播放中启动 QQ 音乐，QQ 成为播放源，小宇宙停在 123 秒处自动暂停，桌面切换到 QQ 卡片。
- 回滚：`adb shell cmd webviewupdate set-webview-implementation com.google.android.webview` 切回系统稳定版；`adb uninstall com.google.android.webview.beta` 卸载 Beta。系统 WebView 83 本体未被修改。
- 注意：Beta WebView 属设备级实验性变更；若其他 WebView 应用出现异常，按上述回滚即可。

## 9. 本地音乐与蓝牙音乐验收（2026-10-31）

- 代码：`main`（Task 7 `581bec9` + 本轮文档）；APK 约 4.9 MB，versionName 0.1.0；设备仍以本应用为默认 HOME。
- 测试音频：生成 30 秒 / 35 秒单声道 WAV，推入 `/sdcard/Music`；MIUI 未自动索引，最终通过 `MediaScannerService` 扫描后进入 MediaStore（`is_music=1`）。
- 权限：MIUI 将本应用的 `READ_EXTERNAL_STORAGE` AppOps 置为 `ignore`（权限显示已授予但查询返回空）；在系统权限弹窗选择“始终允许”并执行 `adb shell appops set io.github.xhzair.refresholdphone READ_EXTERNAL_STORAGE allow` 后正常。
- 本地音乐库显示 2 首测试曲目（`local-a`、`local-b`），点击播放成功；MediaSession 状态为 PLAYING，元数据为曲名/未知歌手/Music（WAV 无内嵌歌手信息），Notification 渠道 `local_music_playback` 出现上一首/暂停/下一首三个动作（[27-local-music-list.jpg](device-evidence/27-local-music-list.jpg)、[28-local-music-card.jpg](device-evidence/28-local-music-card.jpg)、[29-local-music-notification.jpg](device-evidence/29-local-music-notification.jpg)）。
- 后台：切到 QQ 音乐/媒体桌面后本地播放继续，通知栏的 MediaStyle 控制可用；返回桌面按返回键恢复本地音乐页与播放卡片。
- 单源互斥（双向）：本地播放中启动 QQ 音乐，QQ 进入 PLAYING，本地会话自动暂停在 12.3 秒；QQ 播放中打开本地音乐并点歌，本地进入 PLAYING，QQ 自动暂停在 47.8 秒。
- 蓝牙：MIX 2S 当前没有已连接的 A2DP 音频设备；媒体源弹窗显示“蓝牙音乐（未连接）”，点击弹出“未连接蓝牙音频设备”并给出“打开蓝牙设置”入口（[30-bluetooth-source.jpg](device-evidence/30-bluetooth-source.jpg)）。设备名显示、蓝牙实际输出与耳机/车机 AVRCP 按键属于硬件实测项，本轮未覆盖。
- 未覆盖：安全锁屏下的锁屏播放控制（设备设有密码/指纹，本轮只验证了切后台/离开桌面与通知控制）；建议后续由用户解锁后再补一次锁屏场景。
- 已修复小问题（2026-11-02）：从本地音乐页按 HOME 未回到媒体桌面；根因是 `MainActivity` 作为 HOME 未声明 `singleTask`，已加 `android:launchMode="singleTask"`，回归验证本地音乐/设置/应用抽屉/收听记录页按 HOME 均回到 `MainActivity`。

## 10. 月度收听记录验收（2026-11-02）

- 代码：`main`，按产品决定改为**仅统计外部 App，本地音乐不计入**；安装最新 debug APK（2026-11-02 18:20 左右）。
- 本地：此前播放过 `local-a` 约 10 秒、`local-b` 约 6 秒；改版后月报中不再出现本地会话/本地来源（旧行被查询过滤）。
- 外部：QQ 音乐恢复播放并保持约 17 分钟；观察器在应用进程存活 + 通知使用权开启时持续记录。
- 打开“设置 → 收听记录”（月报页）：
  - 2026 年 11 月总收听 **16:59**、活跃 **1 天**、播放 **7 次**、最长 **3:58**；
  - 收听来源只有 **QQ 音乐 16:59**，没有“本地音乐”来源；
  - 24 小时分布柱状图与 0/6/12/18/23 标签正常，页面固定显示外部覆盖说明。
  - 证据：[月报汇总](device-evidence/33-listening-stats-summary.jpg)、[来源与时段分布](device-evidence/32-listening-stats-external.jpg)。
- 月份切换（上一版构建已测）：切到 2026 年 12 月显示 0 时长/0 活跃/0 播放，再切回 11 月恢复数据；空月份不崩溃。
- 未在本轮单独覆盖：旋转后月份保持（已实现但未走真机旋转）、清空记录的端到端点击、应用重启后的数据持久化、进程被杀后的外部覆盖缺口展示。上述均已在代码/说明中处理，建议后续补测。
- 隐私：收听记录仅写本机 `listening_stats.db`，只含外部 App 的播放元数据与时长；不联网，本地音乐不写入。

## 11. 1.0 发布前全场景回归（2026-11-02）

- 自动化：`testDebugUnitTest` 113/113 通过，`lintDebug` 0 errors，`assembleDebug` / `assembleRelease` 成功；Release APK 已用独立 4096-bit RSA 密钥签名（证书 SHA-256 `7488f536…cdfe53`）。
- 首次启动/默认桌面：`resolve-activity` 显示 `isDefault=true`，HOME 进入 `MainActivity`。
- 媒体源弹窗：网易云/QQ/小宇宙/本地音乐/蓝牙（未连接）均显示；选择本地音乐后列表 2 首可播放。
- 本地播放：播放/暂停、上一首/下一首、MediaSession 状态切换正常；播放中按 HOME 不再停留在本地页（已修复 `singleTask`，设置/应用抽屉/收听记录页同样验证）。
- 通知使用权：`cmd notification disallow_listener` 后首页显示授权引导、外部卡片消失；`allow_listener` 恢复后 QQ 卡片恢复。
- 设置：隐藏状态栏开关可即时显示/隐藏；固定横屏/竖屏/自动切换正常；应用抽屉可打开并按 HOME 返回。
- 收听记录（仅外部）：QQ/网易云来源正常，本地播放后数据库无 `LOCAL` 行；月份切换、横竖屏重建月份保持、清空确认后归零均通过。
- 进程：`force-stop` 后按 HOME 冷启动正常，默认桌面角色保留；重启后设备停留在安全锁屏的 `FallbackHome`，按 Android 预期需用户解锁后才会启动默认 HOME（与第 7 节一致）。
- 待补：解锁设备后安装已签名 Release APK（需先卸载 debug 包，签名不同），复核首次启动、默认桌面与收听记录空数据；以及安全锁屏媒体控制/蓝牙硬件按键的真实设备覆盖。
