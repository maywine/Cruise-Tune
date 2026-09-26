# 测试指南

工具链版本见 [构建说明](../README.md#构建)。以下命令在 `android-app/` 执行；设备测试另需 ADB、FFmpeg，以及支持 ARM 应用的 Android 设备或模拟器。

## 本机测试

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease
```

Gradle 自动生成 AIDL 等构建输入。单元测试使用 Robolectric、合成数据和本地服务，无需网盘账号。HTML 报告位于 `app/build/reports/tests/testDebugUnitTest/`，JUnit 结果位于 `app/build/test-results/testDebugUnitTest/`。

原生解码需另按 [FLAC 模块说明](decoder-flac/README.md#本机验证) 构建主机 JNI 并运行 `SoftwareFlacPlaybackTest`。发布工具测试见 [发布流程](../docs/releases.md#本地验证)。

## 播放与缓存回归要点

播放和缓存修改必须覆盖 Android 9 / API 28。按改动选择 [播放测试](app/src/test/java/com/cruisetune/player/playback/)、[数据测试](app/src/test/java/com/cruisetune/player/data/) 或 [界面测试](app/src/test/java/com/cruisetune/player/ui/)，例如：

```sh
./gradlew :app:testDebugUnitTest --tests 'com.cruisetune.player.playback.*'
```

用例应覆盖正常、失败、取消和恢复路径，行为要求见 [播放架构](../docs/architecture.md#播放错误与恢复)。注入播放器错误前须等待播放线程确认已有命令；短音频用例应控制模拟播放头，避免提前播完影响断言。

## 设备自动化

使用独立包 `com.cruisetune.player.authcheck`，要求曲库和队列为空；测试 APK 与应用须签名一致。合成数据用例不使用真实账号，结束后清理自身数据。

### 选择用例

下列类位于 `com.cruisetune.player` 包，每类包含一个设备用例：

| 测试类 | 检查内容 | 所需素材 |
| --- | --- | --- |
| `LibraryActionsDeviceTest` | 目录移除、排序与播放状态保留 | 无，运行时生成 |
| `DeletedQuarkTrackDeviceTest` | 云端失效自动跳过、队列标记、普通错误保留当前歌曲、完整缓存播放 | 无，运行时生成 |
| `PlayerDetailsDeviceTest` | 封面、同目录歌词与离线下载 | 基础 FLAC |
| `QueueFollowDeviceTest` | 队列跟随与手动浏览 | 基础 FLAC |
| `PlaybackOrderDeviceTest` | 顺序切换与播放连续性 | 基础 FLAC |
| `DashboardStartupDeviceTest` | 启动恢复、待播放广播、延迟补发、重试与取消 | 基础 FLAC |
| `EmbeddedLyricsDeviceTest` | 内嵌歌词、优先级与纯文本显示 | 全部素材 |
| `TrackTransitionDeviceTest` | 切歌后的封面与歌词归属 | 全部素材 |

`DashboardStartupDeviceTest` 调用开机广播入口生成通知，再执行通知入口打开应用，使用真实播放服务与测试内接收器验证 Android 广播内容和时序；仅替换仪表接收器的发现与传输，不修改正式包的目标组件或权限检查。它不会重启模拟器，熄屏／亮屏通过注入对应回调验证，原车接收器、实际开机与物理屏幕行为仍需实车确认。

### 生成素材

素材写入 `app/build/generated/player-fixtures/`，仅打入测试 APK。先生成基础 FLAC；`-n` 拒绝覆盖已有文件，素材存在时可跳过对应命令。

```sh
mkdir -p app/build/generated/player-fixtures
ffmpeg -n -f lavfi -i color=c=0xDDBB84:s=256x256 -frames:v 1 \
  app/build/generated/player-fixtures/cover.png
ffmpeg -n -f lavfi -i anullsrc=r=8000:cl=mono \
  -i app/build/generated/player-fixtures/cover.png -map 0:a -map 1:v \
  -t 60 -c:a flac -c:v png -disposition:v attached_pic \
  -metadata title='Demo Track' -metadata artist='Demo Artist' -metadata album='Demo Album' \
  app/build/generated/player-fixtures/details-fixture.flac
```

内嵌歌词和切歌用例还需以下素材：

```sh
cruise_synced_lyrics='[00:01]内嵌第一句
[00:04]内嵌第二句
[00:08]内嵌第三句'
cruise_plain_lyrics=$(awk 'BEGIN { for (i=1;i<=60;i++) printf "合成纯文本歌词第 %d 行\n", i }')
ffmpeg -n -i app/build/generated/player-fixtures/details-fixture.flac -map 0 -c copy \
  -metadata lyrics="$cruise_synced_lyrics" app/build/generated/player-fixtures/embedded-synced.flac
ffmpeg -n -i app/build/generated/player-fixtures/details-fixture.flac -map 0 -c copy \
  -metadata lyrics="$cruise_plain_lyrics" app/build/generated/player-fixtures/embedded-plain.flac
ffmpeg -n -i app/build/generated/player-fixtures/details-fixture.flac -map 0:a -c:a libmp3lame -ar 22050 \
  -metadata lyrics="$cruise_synced_lyrics" app/build/generated/player-fixtures/embedded.mp3
ffmpeg -n -i app/build/generated/player-fixtures/details-fixture.flac -map 0:a -c:a aac -ar 44100 \
  -metadata lyrics="$cruise_synced_lyrics" app/build/generated/player-fixtures/embedded.m4a
ffmpeg -n -i app/build/generated/player-fixtures/details-fixture.flac -map 0:a -c:a libvorbis -ar 44100 \
  -metadata lyrics="$cruise_synced_lyrics" app/build/generated/player-fixtures/embedded.ogg
```

### 构建与运行

生成所需素材后构建、安装，替换 `cruise_device_test` 选择上表中的用例：

```sh
./gradlew -PdeviceTestBuildType=authCheck :app:assembleAuthCheck :app:assembleAuthCheckAndroidTest
adb install -r app/build/outputs/apk/authCheck/app-authCheck.apk
adb install -r app/build/outputs/apk/androidTest/authCheck/app-authCheck-androidTest.apk
cruise_device_test=LibraryActionsDeviceTest
adb shell am instrument -w -r -e class "com.cruisetune.player.$cruise_device_test" \
  com.cruisetune.player.authcheck.test/androidx.test.runner.AndroidJUnitRunner
```

`DirectQuarkDeviceTest` 单独进行真实账号联调：用户扫码授权后，显式传入 `-e realQuark true` 才会执行目录读取和令牌刷新。不要将其加入默认合成数据测试。

## 设备人工检查

记录应用版本、固件、API、ABI、屏幕尺寸与密度。调整显示或测试设置前保存原值，结束后恢复。

### 底部留白

1. 保持原车 Dock 显示，对比自动、标准和特大档位的实际窗口坐标与 Insets，确认没有重复扣除空间。
2. 检查主界面、登录页、弹窗和大字体布局；按钮完整可用，遮罩与触摸区域截止在保留区上方。
3. 检查弹窗外部点击的关闭规则、Dock 点击、重开后的档位保存，以及恢复自动档后的窗口边界。

测量可使用 [Window Probe](../tools/window-insets-probe/README.md)，最终以播放器窗口为准。实现约定见 [窗口处理](../docs/design.md#底部留白与窗口边界)。

### 其他检查

- 播放：声音输出、切歌、跳转、完整缓存离线读取、弱网恢复及低磁盘空间。
- 生命周期：覆盖安装、冷启动、休眠唤醒、物理断电恢复、熄屏暂停及导航／电话音频焦点。
- 方向盘：按 [设备检查步骤](../docs/steering-wheel.md#设备检查) 核对事件、菜单与动作。

## 结果判定

- 本机检查以退出码及目标用例实际执行为准；全部跳过不算通过。
- 单个设备用例应报告 `OK (1 test)`，同时确认未跳过；不能只看 `adb` 退出码。
- 模拟器用于应用逻辑和布局，目标车机用于厂商接口、音频输出与 Dock 行为。结果保存在构建报告或本机忽略目录，不追加到本文。
