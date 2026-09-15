# 小八实现对 LynkAppManager 与 Cruise Tune 的参考价值

本次为只读评估。基于小八桌面 2.7.3 的运行时 DEX 分析、Cruise Tune 0.5.25 和 LynkAppManager 当前源码（`b4ac2ac`）。未修改两个项目的应用代码，未执行授权、卸载或车辆控制操作。

**后续进展：已取得并分析智控大师 1.6.3，找到 OneOS Binder 按键监听和部分日志补充采集链路，见 [智控大师方控分析](../diagnostics/xiaoba-master-steering-wheel.md)。下文对“尚未恢复方向盘接收链路”的描述是仅分析桌面 APK 时的阶段结论；实体车机验证仍未完成。**

## 结论

| 方向 | 参考价值 | 建议归属 | 当前证据 |
| --- | --- | --- | --- |
| 权限状态检查、按功能授权 | 高 | LynkAppManager | 小八已有权限分类、ADB grant / AppOps、特殊授权入口；管理器当前主要是安装卸载 |
| 系统应用按用户卸载 | 有参考，但现有实现已具备核心能力 | LynkAppManager | 现有管理器已限定当前用户、验证包名、核对真实卸载结果；小八已恢复的普通卸载路径调用系统 DELETE Intent |
| 停用、重新启用、恢复预装应用 | 值得补齐 | LynkAppManager | 属于标准包管理能力的扩展，不依赖复制小八私有接口；本轮未证明小八已实现全部恢复流程 |
| 接收标准媒体控制 | 高，现有基础已具备 | Cruise Tune | MediaLibrarySession、媒体按钮接收器、恢复队列回调已存在 |
| 指定播放器的控制与状态诊断 | 高 | 管理器可提供诊断入口；播放器保持媒体会话 | 小八按媒体会话发送播放/暂停/切歌；可用公开 Media3 控制器独立实现 |
| 方向盘物理按键直接接入 | 待实车验证 | 优先系统媒体路由，必要时独立适配层 | 当前桌面样本未恢复出完整的方向盘按键接收链路，不能只凭切歌函数认定已支持 |
| 隐藏原车底栏、读取 VIN / 车辆状态 | 排查线索 | 车机专用调查 | 已找到厂商接口，但不是权限管理或音乐播放必需依赖，也没有解决底栏高度查询 |

## 1. 权限：值得借鉴分类和核验，不照搬批量授予

### 小八的直接证据

`AppPermissionGrantActivity.h()` 读取 `PackageManager.getPermissionInfo()`、权限标签及保护级别；`l()` 区分普通权限、可尝试授予的权限和 AppOps。

可见命令路径包括 `pm grant`、`cmd appops set`，以及旧命令兼容分支。通知读取单独处理：发现目标 NotificationListenerService，尝试更新启用状态及 `cmd notification allow_listener`。它也有针对自身的权限管理页。

这些功能依赖已有的 ADB shell 或相应系统授权。**普通应用声明一个权限，不等于可以给自己或别的应用授予任意权限。** 签名权限也不能因普通用户点击一个通用授权按钮就自动获得。[Android 权限保护级别](https://developer.android.com/guide/topics/manifest/permission-element#plevel)

### LynkAppManager 可以怎样补齐

当前 `LocalAdbClient` 已使用用户主动建立的本机回环 ADB 连接，私有目录保存连接密钥，使用连接代次避免取消后旧连接回写。这套基础足够支撑受限的权限管理，不需要另复制加固包内的 ADB 代码。

建议新增“权限状态”页：展示目标应用实际声明的权限、当前状态、用途和可执行动作。运行时权限、AppOps、安装未知应用、通知读取、无障碍等分开处理；前台应用所需的普通运行时权限仍可由应用自己正常请求。

实现要求：

- 使用 `protectionLevel & PROTECTION_MASK_BASE` 分类，额外标志另行判断；系统签名类权限不可统一归为可授予。
- 只允许已识别的操作类型，沿用包名及用户 ID 校验，不开放任意 shell 文本。
- 状态与操作均明确作用于哪个 Android 用户；AppOps 与权限并非同一层授权，不能以其中一项成功代表功能整体可用。
- 操作后重新查询实际权限／特殊访问状态；不要只看 shell 没有报错就显示成功。
- 通知读取优先使用系统授权入口；如提供 ADB 辅助，按组件执行并重新核验，保留其他已授权监听器。不要照搬整段覆盖全局监听器字符串的做法。
- 不自行开启车机调试、申请 root 或把小八的系统属性写入尝试加入默认连接流程。

这与 LynkAppManager 当前项目约束相符：公共 API、受限本机 ADB、用户主动连接，不复制第三方源码或私有接口。

## 2. 系统应用卸载：现有管理器的重点是补可恢复管理

### 已有实现

`ShellUninstaller.uninstall()` 校验包名与 userId，发送限定用户的卸载命令，失败时尝试 `cmd package` 兼容路径。`LocalAdbClient.uninstall()` 先取得当前用户；`ManagerViewModel` 再通过 `AppRepository` 核对包是否仍安装，并区分系统应用更新被移除。

小八已恢复的 `j3`、`g1` 等普通卸载回调，主要是 `ACTION_DELETE` 配合 `package:` URI，交给系统卸载器；未在这些已核对路径中发现可替代现有实现的特殊系统包删除接口。不能将其说明文档中“使用应用管家移除 Dock”的能力自动归给当前桌面 APK。

### 推荐扩展

1. 区分“用户应用”“预装系统应用”“预装应用更新”“当前用户已移除的预装应用”。后者需要增加相应发现方式，现有 `getInstalledPackages(0)` 列表不能直接承担恢复目录。
2. 分别提供停用／启用与当前用户卸载；保留操作前状态及目标用户，便于核验和撤销可撤销的部分。
3. 增加对仍保留系统包文件的应用执行 `install-existing` 的恢复入口，确认目标用户实际恢复安装。
4. **恢复安装状态不等于恢复被删除的数据。** 当前卸载命令没有 `-k`，不要承诺卸载后账号和设置一定可找回；是否保留数据应作为明确产品选择。
5. 卸载及恢复的命令和核验必须针对同一个用户，避免 shell 当前用户与应用 Context 所属用户不同而产生误判。

当前用户卸载通常不删除只读系统分区中的预装 APK，停用也不等于删除文件；用户限制和设备管理策略仍可能拒绝操作。[Android 包管理命令](https://developer.android.com/tools/adb)

验证继续只使用本项目的卸载测试包；预装恢复场景使用可丢弃测试环境中的测试系统包，不能拿真实 Dock、空调或其他车机组件当测试对象。

## 3. 小八如何控制音乐：已找到的是媒体控制端

`MainActivity`、`MapMusicActivity` 使用 `MediaSessionManager.getActiveSessions()`，通过已启用的 `NotificationListener` 获得活动会话，并按选择的音乐应用／播放状态挑选 MediaController。

普通控制路径调用 `MediaController.TransportControls.play()`、`pause()`、`skipToNext()`、`skipToPrevious()`；缺少合适控制器时，还有 `AudioManager.dispatchMediaKeyEvent()` 的按下／抬起事件路径。例如下一首回退使用键码 87。样本还有蓝牙音源及特定音乐应用的兼容分支。

枚举所有活动媒体会话需要 `MEDIA_CONTENT_CONTROL` 或用户启用的通知监听器；仅在清单里声明通知服务绑定权限并不等于得到通知读取授权。[MediaSessionManager 文档](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName))

对我们的项目，最有价值的是“选定目标播放器、发送标准命令、核对实际状态”的流程。若只控制已知的 Cruise Tune 服务，可以使用公开 Media3 控制器直接连接目标服务，并遵守该服务的连接策略，不必先获取所有应用的通知或枚举所有媒体会话。

## 4. Cruise Tune 已有接收能力，方向盘仍需确认路由

当前 `PlaybackService` 已创建 `MediaLibrarySession`；清单包含 `MediaSessionService` / `MediaBrowserService` 服务入口及 `MediaButtonReceiver`。`onConnect()` 提供播放控制，私有会话命令只向自身包提供；`onPlaybackResumption()` 可返回保存的队列和位置。播放焦点、熄屏暂停及错误后的切歌准备已在播放服务中处理。

标准 MediaSession 的用途就是接收系统和外部控制器的媒体命令，物理按键也可经系统路由到它。[Media3 媒体会话说明](https://developer.android.com/media/media3/session/control-playback)

但必须区分两段：

```text
方向盘物理按键 → 领克系统分发／音源选择 → Cruise Tune 的媒体会话 → 播放服务
```

小八桌面里“调用 skipToNext”只能证明最后一段有媒体控制方式，不能证明最前面的方向盘事件怎样取得。已核对的主页面按键处理主要为 Ctrl+J、返回等；没有据此得到完整的物理方向盘按键桥接。

官网把方向盘按键映射、仪表盘媒体同步列在另一个产品“小八智控大师”下，我们当前分析的是“小八桌面”。这支持两者应分别核对，不能将另一 APK 的能力当成本样本已经恢复的实现。[小八产品说明](https://xbcars.cn/)

### 建议按以下顺序验证

| 检查 | 可以判断什么 |
| --- | --- |
| 测试控制器直接连接 Cruise Tune，播放／暂停／前后切歌，观察状态与位置 | 播放器服务接收端是否工作 |
| 系统媒体键路由，分别在播放器前台、退到桌面后测试 | 标准系统媒体路径与后台生命周期是否工作 |
| 实车按方向盘键，记录原始事件或来源、当前媒体会话、命令到达与执行结果 | 领克有没有把按键交给 Cruise Tune，或只交给原厂音源 |
| 短按、长按、连续操作、其他音源切换、导航提示和熄屏 | 重复事件、焦点与当前暂停策略是否一致 |

如果标准事件已经到达，不新增厂商层。如果只到原厂音源，先调查实际音源注册／路由能力；若只有厂商事件，再设计隔离适配层将事件转为对 Cruise Tune 的定向媒体命令。没有实车数据前不假设事件编号、广播 action 或需要 root。

不要同时从原始 KeyEvent 和厂家回调对同一次按键各执行一次切歌；音量、语音、通话键也应与音乐切歌分开。当前熄屏期间的播放限制仍由 Cruise Tune 服务统一执行。

## 5. 建议实施顺序

1. **LynkAppManager 权限状态页**：首先实现只读诊断、系统授权入口及按需 ADB 操作后的核验。
2. **媒体控制诊断**：优先定向连接 Cruise Tune，验证接收端，再到实车定位方向盘路由。正常播放器不需要为了接收媒体键而获取其他应用的通知读取权。
3. **停用／恢复管理**：补齐当前用户状态、恢复预装包及明确的数据保留语义。
4. **厂商按键适配**：仅在前三步证明标准路径不足时进行；独立评估，不把车辆 SDK、加固库或第三方授权逻辑移入通用管理器。

本报告提供实施方向，不表示已实现以上新增功能，也不表示已完成实车权限、系统包卸载或方向盘验证。两个项目的业务源码在本轮保持不变。
