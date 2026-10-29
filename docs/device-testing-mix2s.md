# MIX 2S 真机验收记录（0.1 MVP）

- 验收日期：2026-10-30（北京时间）
- 代码状态：`feature/media-shell-mvp`，版本 0.1.0（versionCode 1），提交 `75d9c69` 之后的调试构建
- APK：`app/build/outputs/apk/debug/app-debug.apk`（约 4.8 MB，debug 签名）
- 验收设备：Xiaomi MIX 2S（`polaris`），Android 10 / API 29，MIUI 12.5，1080 × 2160、440 dpi
- 验收人：自动化 adb 驱动 + 人工核对截图
- 证据截图：[docs/device-evidence/](device-evidence/)（分辨率已压缩至 540 宽）

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
| 15 | 旋转不打断播放 | 未覆盖 | 本次未实际播放音频（避免在用户设备上出声）；应用自身不持有播放器，旋转只重建首页 UI，外部播放由官方 App 持有 |
| 16 | 锁屏/解锁、进程重建 | 未覆盖 | 需人工长时间观察；Activity 重建由 `HomeViewModel` 生命周期测试覆盖 |
| 17 | 重启后持久化/无崩溃循环 | 未测试 | 需用户明确同意后重启设备，本次未执行 |
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

里程碑 1 的核心路径在 MIX 2S 上通过：普通 App 打开、双方向布局、官方 App 启动、通知授权后的 MediaSession 展示、撤销后的降级、系统入口、HOME 接管与恢复、清除配置均符合设计规格。未覆盖项（实际音频播放中旋转、锁屏/重启持久化）需要在用户明确同意后补测。
