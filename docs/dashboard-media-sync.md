# Cruise-Tune 车机媒体同步实现说明

本文说明 Cruise-Tune 的可选原车仪表媒体同步实现、状态边界和验证方式。仪表同步是播放服务的旁路能力；它不能阻塞播放、缓存、错误恢复或方向盘按键处理。

整体播放和数据职责见 [架构说明](architecture.md)，窗口和底部避让见 [界面说明](design.md)，OneOS 按键协议见 [方向盘按键适配](steering-wheel.md)，可复用命令见 [测试指南](../android-app/TESTING.md)。

## 组件关系

实现位于 `com.cruisetune.player.dashboard`：

- `DashboardSnapshot`：从播放线程当前状态生成的不可变快照，包含曲目 ID、来源包名、标题、歌手、专辑、播放状态、时长、位置和封面地址。
- `DashboardSyncPolicy`：只负责本地状态机，区分开始播放、切歌、缓冲、暂停和失效；不会推断仪表是否真的显示。
- `DashboardSyncController`：串行化接收器检查、状态发布、节流、重试和失效清理。所有远端调用在 IO dispatcher 执行，状态回到播放服务所属作用域更新。
- `DashboardCoverStore`：从 Media3 的 `artworkData` 生成受限 JPEG，API 28 提供 file URI，API 29+ 提供带版本参数的回环 HTTP URI。
- `DashboardTransport`：定义接收器检查和广播发送接口，便于单元测试和后续替换实现。
- `EcarxBroadcastTransport`：访问已配置的 Ecarx 媒体接收器，并检查包、组件、action、导出属性、系统应用身份和接收器权限。
- `EcarxMediaPayload`：生成接收器所需的最小字段集合，并统一清理文本、限制位置和保持数值类型。

`PlaybackService` 创建并持有 `DashboardSyncController`。Activity 只负责设置、状态展示和诊断复制，不直接发送车机广播。

## 接收器检查与发送

当前固定目标为：

- action：`ecarx.intent.broadcast.action.MEDIA_CONTROL_RECEIVER`
- package：`com.geely.online.service`
- receiver：`com.media.control.MediaControlReceiver`

启用同步后，控制器先通过 `PackageManager` 检查：

1. 目标包和组件存在；
2. 组件的 intent-filter 声明了目标 action；
3. 包和接收器均启用；
4. 接收器允许外部调用；
5. 目标包是系统应用或更新过的系统应用；
6. 接收器声明权限时，本应用已经拥有该权限。

普通广播返回成功只表示系统接受了发送请求。接收器没有回执时，界面只能显示“已发送，需在仪表媒体菜单确认”，不能声称仪表已显示。

## 媒体字段与类型

发送字段保持在已确认的兼容集合内：

- `RECEIVER_MEDIA_COLLECT_STATUS`
- `RECEIVER_MEDIA_FAVORITE_STATUS`
- `RECEIVER_MEDIA_PLAY_STATUS`
- `RECEIVER_MEDIA_BOOK_ID`
- `RECEIVER_MEDIA_FRAGMENT_ID`
- `RECEIVER_MEDIA_BOOK_NAME`
- `RECEIVER_MEDIA_BOOK_AUTHOR_NAME`
- `RECEIVER_MEDIA_BOOK_COVERURL`
- `RECEIVER_MEDIA_TOTAL_DURATION`
- `RECEIVER_MEDIA_CURRENT_POSITION`

时长和位置使用 `Long`，负数归零，位置在已知时长时不会超过时长。文本去除控制字符并限制为 256 个 Unicode code point。媒体 ID 是由来源包名和曲目 ID 生成的稳定正数，不把云端文件 ID 或临时下载地址写入仪表字段。

`RECEIVER_MEDIA_BOOK_COVERURL` 和兼容别名 `RECEIVER_MEDIA_COVER_URL` 使用当前歌曲的封面 URI；没有可用封面时保持为空，不把上一首封面带到新歌。封面从 `MediaMetadata.artworkData` 提取，缩放到最长边 320px，JPEG 输出限制在 512 KiB，文件以内容摘要命名并原子替换，旧文件最多保留 6 个。

Android 9/API 28 使用 `file:///.../xiaoba_covers/cover_<hash>.jpg`；Android 10/API 29 及以上使用 `http://127.0.0.1:9090/cover_<hash>.jpg?v=<hash>`。文件准备完成后，播放服务用同一曲目再次发送媒体广播；晚到的封面结果必须匹配曲目和 artwork 引用，不能覆盖切歌后的状态。实车仍需确认接收端能读取两种地址。

## 播放生命周期

播放服务在以下事件生成快照：

- 播放状态变化；
- 切歌、跳转和队列更新；
- 播放服务完成启动恢复；
- 播放错误和恢复流程；
- 定时位置刷新。

只有实际播放中的曲目会占用仪表。恢复队列、等待准备、通话或其它音频占用、熄屏、播放器错误和当前曲目未知时，控制器会失效当前发布状态。

失效会：

- 增加状态代次，丢弃尚未发送的旧快照；
- 取消合并发送和重试；
- 发送一次暂停状态清理已经发布的播放状态；
- 忽略失效代次的异步发送结果；
- 在关闭同步后仍允许完成清理，但不会再发送新的播放状态。

同一首歌的播放进度最多约每 1.8 秒发送一次；普通播放状态合并等待 200 ms，避免切歌和播放器回调连续到达时发送中间状态。发送失败时，临时错误按 2、5、15 秒最多重试三次；清理状态同样受有限重试约束。

## 设置和诊断

仪表同步默认关闭，入口为「设置 → 车辆 → 仪表媒体显示」。设置页提供：

- 开关；
- 当前接收器检查状态；
- 手动重新检查并发送；
- 脱敏诊断复制。

诊断包含同步状态、发送计数、接收器版本、UID、是否系统应用和最近失败原因，不包含歌名、账号、文件路径或网盘凭证。关闭同步只停止新的同步，并尝试清理已发布的播放状态；方向盘、缓存和播放恢复不受该开关控制。

## 与播放和方向盘的边界

仪表同步不枚举其它播放器，也不接管 Media3 的媒体会话。播放器仍由 `PlaybackService` 和 Media3 管理，标准媒体键仍走原有路径。OneOS 方向盘事件仍按 [方向盘说明](steering-wheel.md) 的服务身份、Binder 生命周期、菜单门控和去重策略处理。

仪表广播不是 OneOS 按键协议，也不应通过注册按键服务、RootShell、系统文件写入或全局按键注入来实现。车机厂商服务、权限和物理显示仍需在目标固件上分别确认。

## 测试与验证边界

本地验证入口：

```sh
cd android-app
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease
./gradlew :app:assembleRelease
```

仪表同步单元测试覆盖：

- 默认关闭和设置持久化；
- 文本清理、稳定 ID、时长和位置边界；
- 发送字段、接收器组件和 `Long` 类型字段；
- 播放、缓冲、暂停、切歌和失效状态机；
- 熄屏或关闭同步时发送暂停清理；
- 清理后不继续发送旧播放 tick。

`authCheck` 设备测试可验证播放服务、队列和生命周期，但模拟器没有 OneOS/Ecarx 原车服务，不能证明真实仪表收到广播。真实车机仍需检查：仪表媒体菜单、切歌顺序、暂停清理、熄屏、通话音频焦点、休眠唤醒和覆盖安装后的接收器权限。

## 当前限制
- 没有仪表回执时，发送成功不能等同于显示成功；
- 接收器字段和播放状态值属于当前兼容配置，跨车型、跨固件前必须重新核对；
- 仪表封面依赖目标接收端能读取受限 file URI 或回环 HTTP 地址；若固件拒绝该地址，保留文字、状态和进度同步；
- 不包含真实车机账号、VIN、设备标识或运行日志；
- 不依赖第三方实现中的加固库、特权权限或白名单写入逻辑。
