# 82daae4 修复核对与回归审查

日期：2026-09-14。目标：0.5.19（82daae45affe29cea2dd694815bda43dbea1b9e6），主要对照父提交 0.5.18（64937d7），并核对 0.5.10 是否也包含相同旧逻辑。

结论：显式重试、随机切换重建队列、裸 EOF 误判，以及横屏按钮宽度限制均有旧代码依据；不能因此认定此前实际播放卡顿由缓存损坏造成。自动修复、失败跳过及一轮预算属于新增能力。该提交另有两项网络恢复边界问题；后续 0.5.20 修复说明见文末。

## 当前提交的问题

### [P1] 缓存恢复遇到临时网络错误后停止自动重试

位置：[NetworkRetry.kt 34–38](../../android-app/app/src/main/java/com/cruisetune/player/playback/NetworkRetry.kt#L34)、[PlaybackService.kt 200–206](../../android-app/app/src/main/java/com/cruisetune/player/playback/PlaybackService.kt#L200)。

触发：歌曲进入绕过缓存重读阶段，下载地址或媒体读取出现 HTTP 超时、断连、可重试 5xx 等临时错误。

`PersistentNetworkLoadPolicy` 对所有处于 bypass 的读取直接返回 `C.TIME_UNSET`，包括正常应重试的网络错误。Media3 将其作为不再重试的加载失败。服务随后识别为 shared failure，调用 `cancelRecovery()` 并返回；播放器停在 IDLE，没有重新加载任务，也没有网络恢复后继续执行的路径。错误不会跳过歌曲，但会终止原本要求持续重试的行为。

隔离测试向真实 Robolectric 服务注入恢复阶段的 HTTP SocketTimeout，确认：错误仍被识别为 transient，调用失败处理后却为 STATE_IDLE、recovery=null、recoveryAction=null。要求自动重试仍有执行路径的断言失败。现有 `recoveryLoadIsBoundedWithoutChangingNormalWeakNetworkRetries` 测试只确认普通路径继续重试，反而明确接受恢复路径不重试，未覆盖服务接管后的结果。

建议：为文件解析错误保留一次重读预算；可重试的网络错误仍保持退避等待，不消耗文件失败预算。若需要限制一次恢复加载时长，应在退出该次加载后安排网络恢复重试，不能只取消状态。

### [P2] 45 秒未推进可能把弱网判成坏歌并跳过

位置：[PlaybackService.kt 196–198](../../android-app/app/src/main/java/com/cruisetune/player/playback/PlaybackService.kt#L196)、[270–276](../../android-app/app/src/main/java/com/cruisetune/player/playback/PlaybackService.kt#L270)。

触发：绕过缓存后等待网盘地址、慢服务器或缓冲超过 45 秒，尚未产生明确文件解析错误。当前网络仍声明 `NET_CAPABILITY_INTERNET`。

超时逻辑只检查 INTERNET 能力就执行 `skipFailedTrack()` 并将歌曲计入失败集合。该能力并不证明当前请求可以完成；无实际互联网连通性、等待门户认证或单纯慢网均可能命中。即使网络已 validated，服务端慢也不能证明文件坏了。这会改变当前歌曲与位置，多首相继超时还可能导致整轮停止。

隔离测试保留 INTERNET、移除 VALIDATED，并给恢复记录设置已等待 46 秒；未注入第二次文件错误，实际索引仍由 0 变为 1。要求不将该网络等待自动计为坏歌的断言失败。

建议：将文件再次解析失败与网络等待分开处理。不能仅凭 45 秒无播放进展标记坏文件；临时网络故障应保持原曲目与位置，等待重试。

## 旧版是否存在对应问题

| 修复描述 | 对旧版的判断 | 依据与适用范围 |
| --- | --- | --- |
| 重试当前歌曲没有真正重载 | 确实存在，需限定状态 | 0.5.18 的 `prepareAndPlay()`（145–148 行）只有 prepare/play；0.5.10 相同。实际依赖 Media3 1.9.4 的 ExoPlayerImpl.prepare 在非 IDLE 时直接返回，因此 READY/BUFFERING 中的重试不能打断旧加载。但已报 fatal error、回到 IDLE 时旧 prepare 能重新准备，不能说旧重试在所有场景都无效。 |
| 切换随机／顺序导致当前缓冲被重建 | 确实存在 | 0.5.18 的 toggleShuffle（200–211 行）重设全部 MediaItems 后 prepare；0.5.10 也如此。保持歌曲 ID 与毫秒位置不等于保留解码器及缓冲。新 moveMediaItem 方案方向正确。0.5.18 的手动曲库/队列排序已有 move 逻辑，不能扩大为所有排序操作都受影响。是否形成可听见的卡顿仍取决于缓存、媒体及设备。 |
| 本地解析 EOF 被无限当作网络重试 | 确实存在，已合成复现 | 旧 NetworkRetry 明确包含 EOFException，延迟上限 30 秒、次数上限 Int.MAX_VALUE。用真实 FLAC 解析器读取一个已完整缓存、但元数据被截断的合成文件，得到裸 EOF：旧策略第 1 次延迟 2 秒、第 1,000,000 次仍延迟 30 秒；新策略不再重试。旧版本原本已排除 ParserException，问题不是所有解析异常都被无限重试。 |
| 之前的真实音乐缓存已经损坏 | 无法据此确认 | 上述合成用例证明“这些字节存在时会怎样”，没有证明用户实际文件或落盘缓存就是这些字节。缓存覆盖完整只代表字节范围完整，不代表音频可解码；HTTP 取址、解码器、输出设备及真实网络等原因仍需现场证据。 |
| 自动修复、跳过坏歌、一轮失败停止 | 新增恢复能力 | 0.5.18 的 onPlayerError 只更新提示，没有这套自动跳过循环。因此不能把旧版描述成“已有自动跳过但会无限循环”；一轮失败预算主要用于约束本次新逻辑。 |
| 暂停、切歌、熄屏取消恢复 | 新增恢复流程所需的配套 | 旧版没有恢复任务，原本已经在熄屏时 pause 并取消预缓存。此提交不能证明旧版普遍在熄屏后自动继续播放。 |
| 横屏主按钮没有铺满左栏 | 确实存在，是布局限制 | 0.5.18 的 footer（321 行）使用最大 480dp 的 BoundedControlRow；左栏超过该宽度时按钮组不会铺满。新横屏 LinearLayout 移除了上限，竖屏保留。旧版不等于按钮不可点击或文字必然被裁切。 |

旧版源码：[0.5.18 PlaybackService](https://github.com/maywine/Cruise-Tune/blob/64937d7/android-app/app/src/main/java/com/cruisetune/player/playback/PlaybackService.kt)、[0.5.18 NetworkRetry](https://github.com/maywine/Cruise-Tune/blob/64937d7/android-app/app/src/main/java/com/cruisetune/player/playback/NetworkRetry.kt)、[0.5.18 MainActivity](https://github.com/maywine/Cruise-Tune/blob/64937d7/android-app/app/src/main/java/com/cruisetune/player/ui/MainActivity.kt)。以上结论来自本地 Git 对象，未依赖发布说明作事实证明。

## 验证范围

- 在忽略目录中的隔离代码副本运行 19 项定向主机测试：17 项通过，2 项新增预期行为断言失败，分别对应上面的两个发现。原有选定测试全部通过；这不是全量测试结果。
- 旧策略对照仅复制父提交的 NetworkRetry 并更名隔离，结合真实 Media3 FLAC 解析器复现 EOF 的重试决策；未将其包装为旧 APK 端到端播放回归。
- 检查当前固定 Media3 1.9.4 依赖的编译产物，确认 prepare 的状态分支；另检查 ProgressiveMediaPeriod 将 TIME_UNSET 转为 DONT_RETRY_FATAL 的实现。
- 新边界测试构造恢复中的服务状态、HTTP 错误与网络能力，没有访问真实网盘，不修改账号、曲库或媒体文件。这轮没有做模拟器音频或实车回归。
- 正式生产代码未修改；报告与测试结果均不含个人歌曲、账号、Cookie 或 Token。


## 0.5.20 修复

两项发现已修复。普通与缓存旁路加载统一按错误类型决定重试，不再按旁路标识禁止网络重试；若临时错误直接进入播放器终止错误回调，服务保留恢复状态并安排可取消的退避重载。移除 45 秒跳歌和基于 INTERNET 能力的文件判断，也移除初始断网时只提示手动重试的分支。文件失败预算仅用于明确的文件错误；未知错误不自动跳过。

新增 RecoveryNetworkTest 使用真实 Media3 播放器与解析器验证：已缓存的截断 FLAC 触发恢复后，模拟两次 HTTP 网络超时仍能读取新音频并进入 READY；终止网络错误的延迟重载确实执行，曲目和位置保留；断网、未验证网络、已验证网络下等待 90 秒均不跳歌；暂停、切歌与熄屏可取消延迟动作；登录和未知错误不会自动跳歌。网络上游使用合成数据，不访问真实网盘。设备验证和完整结果见 android-app/TESTING.md 的 0.5.20 记录。
