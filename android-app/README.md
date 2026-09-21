# Cruise Tune Android

版本由 [version.properties](version.properties) 统一维护。功能、配置和构建入口见 [项目说明](../README.md)。

## 工程结构

应用源码位于 `app/src/main/java/com/cruisetune/player/`：

- `core/`：歌曲、来源、播放队列与用户可读错误。
- `data/`：曲库、播放快照、凭证加密、夸克网页接入。
- `data/open/`：直接授权、访问令牌刷新、签名、目录分页和下载地址解析。
- `playback/`：Media3 服务、分段读取、缓存、离线下载、网络重试与熄屏监听。
- `ui/`：播放器、来源与设置面板、扫码页面、适配布局和统一控件。

`decoder-flac/` 提供 FLAC 软件解码，源码来源、ABI 与 JNI 测试方法见 [模块说明](decoder-flac/README.md)。

## 构建变体

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleAuthCheck
./gradlew :app:testDebugUnitTest :app:lintDebug
```

- 常规开发包：`com.cruisetune.player`。
- 独立授权验证包：`com.cruisetune.player.authcheck`，独立应用数据，用来排除旧登录和缓存影响。
- 验证包的 Activity 类仍为 `com.cruisetune.player.ui.MainActivity`，不要把 applicationId 后缀自动加到类名上。

真实账号联调需用户在验证设备上自行扫码。常规单元测试使用本地模拟响应，不需要网盘凭证。

设备回归命令、测试数据与结果判定见 [测试说明](TESTING.md)。

## 歌词与播放交互

歌词优先使用当前歌曲已解析出的内嵌标签：支持 FLAC／Ogg 的 `LYRICS` 等常见字段、MP3 的 ID3 `USLT`／`ULT` 与歌词 `TXXX` 字段，以及 M4A 的 `©lyr`。内嵌同步歌词优先于内嵌纯文本；没有可用内嵌歌词时，读取本地或已授权网盘同目录中的同名 `.lrc`，例如 `Song.flac` 对应 `Song.lrc`。暂不支持 ID3 `SYLT` 二进制同步帧或其它私有歌词封装。

内嵌歌词在播放器首次加载歌曲后可用，不额外下载或改写音频文件；已保留的离线歌曲同样可读取。带 LRC 时间戳的内容双行同步显示，纯文本标明“未同步歌词”并允许手动滚动，不随播放时间自动移动。同曲目切换封面／歌词复用已读结果，刷新音乐目录会清除歌词内存缓存。小窗口通过“详情”或设置中的“封面与歌词”在页面内展开，歌名、进度和主要播放按钮始终可操作，返回后恢复列表位置。

切歌时，封面和歌词只使用与当前曲目匹配的播放引擎数据；新歌曲没有封面或内嵌歌词时不会沿用上一首的内容。

队列在切歌、当前项因排序移动或进入队列时自动定位当前歌曲；当前项已完整可见时不滚动，拖动或惯性滚动期间延后跟随。播放进度及缓存状态刷新不会反复拉回列表，点击“队列”可重新定位。曲库浏览位置单独保留；从详情返回时，未切歌则恢复原位置，已切歌则跟随新的当前项。

封面／歌词区域底部依次为“歌词／封面”“顺序／随机”“循环”“保留离线”。“顺序／随机”和“循环”分别控制播放排列与队列是否回到开头，点击后保留当前歌曲和进度；顺序切换期间短暂禁用以防重复操作，空队列时不可用。队列标题只显示队列名称和歌曲数，循环状态由按钮和播放状态展示。设置中不再显示随机播放或播放顺序／循环方式入口。

直接扫码客户端配置保存在 Git 忽略的 `app/src/main/assets/quark_cli_client.json`；空模板位于 `config/quark_cli_client.example.json`。配置方式见 [夸克接入说明](../docs/quark-integration.md)。

## 交付和兼容

最低 Android 6.0 / API 23，目标车机为 Android 9 / API 28。FLAC 默认使用 Media3 FLAC 扩展与 libFLAC 1.5.0 软件解码，APK 包含 armeabi-v7a 和 arm64-v8a 的 `libflacJNI.so`；其他音频格式沿用 Media3 的格式支持。

本地构建和正式发布分别按 [构建入口](../README.md#构建) 与 [发布流程](../docs/releases.md) 执行。APK 和签名材料不进入源码仓库；设备回归要求见 [测试指南](TESTING.md)。

## 自动发布

新版本标签触发签名构建与 GitHub Release，标签必须为 `v` 加代码版本；内部版本号须递增。一次性 Secrets 设置及操作步骤见 [发布说明](../docs/releases.md)。
