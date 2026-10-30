# 发布检查清单（0.1 MVP）

本清单用于把当前代码打成可交付的调试/发布构建，并在真机上确认可逆性。执行前确认自己了解：本项目**不会**自动修改设备默认桌面，也**不应**在未经用户明确同意时推送、发版或永久改变设备设置。

## 1. 环境

- JDK 17（`JAVA_HOME` 指向 JDK 17）。
- Android SDK：Platform 36、Build-Tools 36.0.0、Platform-Tools。
- 仓库根目录存在不入库的 `local.properties`：

  ```properties
  sdk.dir=C\:\\path\\to\\android-sdk
  ```

- Gradle 9.6.0：优先使用仓库自带 wrapper；离线环境使用本地 Gradle 9.6.0 与已预热的依赖缓存。

## 2. 自动化验证

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```

离线环境示例（本仓库开发机即为此环境：Maven 不可达，需预热的依赖缓存）：

```powershell
$env:ANDROID_USER_HOME = "<workspace>\.android-user-home"   # 避免写 ~/.android 被拒
& "<gradle-9.6.0>\bin\gradle.bat" -g "<GRADLE_USER_HOME>" --offline clean testDebugUnitTest lintDebug assembleDebug
```

> `ANDROID_USER_HOME` 指向工作区后，首次构建会在该目录生成调试签名 `debug.keystore`；更换该目录等于更换签名，覆盖安装前需卸载旧版本。

验收标准：

- 单元测试全部通过（0.1 基线：8 个测试类、51 个用例，覆盖 App 目录、播放会话选择、元数据映射、系统状态、设置快捷 action、首页状态与 ViewModel 生命周期）。
- lint 无 error；允许存在非阻塞 warning（见第 6 节）。
- 生成 `app\build\outputs\apk\debug\app-debug.apk`。

## 3. APK 校验

```powershell
& "<android-sdk>\build-tools\36.0.0\aapt2.exe" dump badging app\build\outputs\apk\debug\app-debug.apk
```

确认：

- `package: name='io.github.xhzair.refresholdphone'`
- `minSdkVersion:'23'`、`targetSdkVersion:'36'`
- `versionName='0.1.0'`
- `application-icon` 指向本应用图标，且存在 HOME 与 LAUNCHER intent-filter。
- 权限仅 `ACCESS_NETWORK_STATE`，以及通知监听服务的系统绑定权限。

## 4. 真机安装与冒烟

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n io.github.xhzair.refresholdphone/.MainActivity
```

依次确认：

1. 可作为普通 App 打开，首页显示时钟/日期/网络/电量与一个紧凑的媒体源图标（显示当前/最近使用的媒体 App 图标）。
2. 点击媒体源图标弹出“选择媒体应用”列表，只列出设备已安装的媒体源；选择网易云音乐可启动已安装的 `com.netease.cloudmusic`，QQ 音乐、小宇宙未安装时不出现在列表中且不崩溃。
3. 通知使用权未开启时显示授权提示；开启后首页出现正在播放卡片（若 App 暴露 MediaSession），控制按钮只出现会话声明支持的操作。
4. 系统快捷键（Wi-Fi、移动网络、蓝牙、音量、应用详情、系统设置）可打开对应系统页面。
5. 方向设置三档生效；旋转不打断外部 App 播放。
6. “恢复系统桌面”能进入 HOME 设置并可选择 MIUI/系统桌面。
7. “清除本地配置”只清本应用 SharedPreferences，第三方 App 数据不受影响。
8. 锁屏/解锁、进程重建后首页可恢复，不出现崩溃循环。
9. 默认隐藏系统状态栏；设置“隐藏系统状态栏”开关可恢复显示；顶部下滑能临时唤出状态栏。

完整验收矩阵与真机证据见 `docs/device-testing-mix2s.md`。

## 5. 仪器化测试（设备连接时）

推荐方式（需要网络以下载 AGP 的 UTP 组件）：

```powershell
adb devices -l
.\gradlew.bat connectedDebugAndroidTest
```

离线环境的等价方式（本仓库已验证）：手动安装两个 APK 后直接运行 instrumentation（0.1 基线：`OK (7 tests)`）：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleDebugAndroidTest
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w io.github.xhzair.refresholdphone.test/androidx.test.runner.AndroidJUnitRunner
```

> MIUI 注意事项：instrumentation 从后台拉起 Activity 时会被 MIUI 的 `MIUIOP(10021)` 拒绝，测试会卡在 Activity 启动。临时执行 `adb shell appops set io.github.xhzair.refresholdphone 10021 allow` 后重跑即可；测试完成后用 `appops set ... 10021 default` 恢复。重新安装 APK 会重置该 appop。
>
> 要求：仅连接一台已授权设备，测试全部通过；未连接设备时允许跳过，但需在发布记录中注明。真机验收细节见 `docs/device-testing-mix2s.md`。

## 6. 已知非阻塞 warning（2026-10-30 基线）

- `OldTargetApi`（targetSdk 36 非最新）与 `GradleDependency`（compileSdk 37、部分 AndroidX 有新版本）。
- `AndroidGradlePluginVersion`（Gradle 9.8.2 已存在）。
- 0.1 已补充自适应/传统启动图标，`MissingApplicationIcon` 已消除；lint 总数 6 条、0 error，均为构建配置类提示。

## 7. 密钥与占位扫描

```powershell
rg -n "(api[_-]?key|client[_-]?secret|password|TODO|FIXME|TBD)" . -g "!*.lock" -g "!docs/superpowers/plans/*"
```

要求：无真实凭据、无发布阻塞级占位符（文档中的说明性 TODO 不计为阻塞）。

## 8. 文档与仓库状态

- `README.md` 覆盖安装、构建、权限、恢复桌面与已知限制。
- `docs/privacy.md` 覆盖本地数据、通知使用权用途与删除路径。
- `docs/device-testing-mix2s.md` 记录真机验收证据。
- `git status --short` 只包含本次发布的有意改动。

## 9. 发布边界

- 未经用户明确要求：不 push、不创建 GitHub Release、不上传 APK、不永久把设备 HOME 改成媒体桌面。
- 不修改设备系统分区、不停用/卸载系统应用、不申请设备管理或无障碍权限。
- 版本号、变更说明与已知限制同步写入 Release 说明。
