# 测试与验收

## 仓库首次提交验证

2026-09-10，从 Git 暂存区导出不含真实客户端配置、本机 SDK 配置和原始调试记录的干净副本，构建常规版与独立验证版，并运行测试与 Lint。结果：81 项 Android 测试通过，0 失败／错误／跳过；8 项连接服务测试通过；Lint 通过。另确认构建 APK 不含 `quark_cli_client.json`，测试副本的 Android 文件与暂存内容一致。

提交检查覆盖忽略规则、个人绝对路径、常见凭证格式、本地签名密钥及已保存授权参数的精确值比对；文档相对链接有效。真实客户端配置保留在本机，未进入 Git 历史。

## 自动化

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

现有 97 项 Android 本机测试覆盖以下行为：

- 曲库与播放快照、队列策略、稳定缓存身份。
- 夸克二维码、授权页校验、设备绑定、访问与刷新令牌、并发更新保护。
- HTTP Range 与 Content-Range 校验、签名地址更新、完整缓存与纯缓存读取。
- 后 3 首目标选择、随机／循环顺序、临时网络失败持续重试、单首失败不阻塞其他目标、任务取消。
- 熄屏清除待播放意图、持久化、亮屏不自动恢复及监听释放。
- 640×360、667×375、853×480、1280×720 dp，以及 640×360 dp / 1.6 倍字体布局；按钮与歌曲行语义、主题切换保留设置上下文。

测试中的凭证和账号字段为合成样例。`open-service/` 的测试另通过 `python3 -m unittest discover -s open-service -v` 运行，不访问真实网盘。

## 0.5.3 数据维护回归

新增 8 个用例分别在 API 23 和 API 33 运行，共 16 项：反复扫描与跨来源隔离、失败扫描回滚与保守清理、当前／备用快照恢复、超过 SQL 参数常见上限的离线引用保留、旧数据库遗留记录清理、350 次进度写入的固定记录数量、真实文件 WAL 在大批量写入后的复用，以及界面引用与离线索引失败的处理。

文件 WAL 用例核对实际 WAL 模式、日志文件存在、完成回写及后续写入后的大小，并确认曲库完整。该测试不代表活跃读事务存在时也有同样文件大小上限；长期读事务的延迟回收属于 SQLite WAL 的边界。

## 0.5.3 正式 APK 的 BlueStacks 运行复验

2026-09-10，使用 GitHub Release 的正式 APK，在 BlueStacks Air / Android 13 上从 0.5.2 覆盖更新到 0.5.3，未清除数据。已有曲库、队列、缓存状态和暂停位置保留。实际继续播放、曲末自动切歌、进度跳转以及播放中刷新网盘目录均完成，刷新明确显示成功且未重新扫码。

在播放中结束目标进程，确认进程消失后重新启动；恢复同一队列项与位置，保持不自动播放。终止前后采样位置差为 508 ms，随后点击继续又进入正常播放状态。采样差包含命令调度时间，不是物理断电误差保证。本轮两个进程的限定日志未出现 SQLite 或崩溃匹配项。复验结束已暂停，显示配置保持原值。

这次运行验证没有直接读取正式包私有数据库，也未人为删除真实音乐文件，所以不能把界面正常等同于“在该安装实例量化验证了失效行数或 WAL 上限”；这些量化检查仍由前述数据维护自动化测试覆盖。原始截图、视频和会话日志包含个人曲库信息，仅保存在本机。

待确认显示现象：个别标题包含字面 HTML 实体 `&#39;`；需比对源文件名后确定是否补充显示解码。本轮未改代码，未重新发布 APK。

## 0.5.3 私有数据库量化补测

同日使用临时同签名 instrumentation 补齐量化检查：校验已安装正式 APK 的 SHA-256，直接调用其 DEX 中的数据库实现。读取正式实例的表计数并比较测试前后内容摘要；实际增删与压力写入只在该库的私有一致副本执行。真实业务数据前后一致，原实例此次没有待清理失效行。测试后副本、测试音频和组件均已移除，正式播放器恢复原暂停项与位置。

| 原生 Android 实测场景 | 结果 |
| --- | --- |
| 实体 WAV 文件删除 | 文件 3→2，对应数据库行 3→2 |
| 队列与外部引用 | 保留引用时不删；解除后清理；最新队列损坏时可回退 |
| 600 次加速进度写入 | 2 条快照，测试队列项保持 1 条；WAL 峰值 1,054,752 字节 |
| 20 轮替换扫描 | 每轮最终 100 条，未累计到 2,000 条 |
| 失败扫描 | 事务回滚，原记录保留 |
| 读取事务占用时写入 2,500 条大记录 | WAL 12,549,552 字节，约 892 ms 完成写入 |
| 释放读取并继续提交 | WAL 收敛至 1,048,576 字节 |

这次是在 BlueStacks Android 13 的实际 SQLite 上执行，不是重复运行主机上的 Robolectric。目录用例将实体测试文件的枚举结果交给正式 replaceScan 方法，不覆盖 SAF 或远端网盘写操作。600 次调用为加速压力，不代表 20 分钟真实定时。WAL 的 1 MiB 仍是回收后保留目标，不是活跃事务期间硬上限。

可复用组件与运行说明见 [database-audit](../tools/database-audit/README.md)。原始库和用户数据不上传，文档仅记录聚合结果。前一节的“未量化”描述属于首次 UI 复验范围，现已由本补测补齐。

## 已有模拟器观察

使用 BlueStacks Air 5.21.782.7501、Android 13 / API 33 / ARM64 完成过以下联调。这里只保留结果与验证方式，未收录个人目录、截图、会话日志或设备身份。

| 项目 | 结果与范围 |
| --- | --- |
| 独立授权 | 从空数据的独立验证版扫码，自动兑换并进入目录；排除主应用旧会话影响 |
| 文件读取 | 授权目录分页、曲库生成、FLAC 首次播放、界面时间推进 |
| 令牌刷新 | 主动执行真实刷新，随后直接读取目录与一个 64 KiB HTTP 206 分段；不经过播放器缓存 |
| 进程恢复 | 播放中结束目标进程、确认消失、重新启动后恢复歌曲／队列／近似位置，默认暂停；不等同于物理掉电 |
| 后 3 首缓存 | 应用内诊断核对实际播放顺序和三首文件的完整缓存状态 |
| 界面 | 横竖屏、日／夜系统栏、来源顺序、设置拖动与主题切换、继续／暂停状态 |
| 大字体 | 实屏发现时间文字截断与序号换行后修复；补充状态栏占用及文字实际高度断言 |
| 图标 | 0.5.2 覆盖安装后桌面新图标可见，点击可打开，曲库与暂停记录保留 |

图标另外检查了 48／72／96 像素、圆形及圆角方形、单色主题预览。API 23–25 多密度 PNG 尚未在旧系统实屏验证。界面状态和媒体会话不能替代扬声器声音或音质检查；可访问性层级不能替代 TalkBack 语音验收。未做帧耗时测量。

## BlueStacks 已知问题

- 部分属性查询成功并不证明调试完全开启；ADB 开关关闭时可能安装失败并报 `error: closed`。
- 曾发生 PackageManager `AppsFilterBase.shouldFilterApplication` 空指针，同时存在本任务 `package:null` 的残留 instrumentation 包。仅清理已确认可丢弃的那个测试组件后更新恢复；不是所有同类异常的通用结论。
- `run-as` 曾报 `setegid: Operation not permitted`，不能据此判断私有数据不存在。不要以清除主应用数据作为普通安装修复方式。
- 动态播放页的 `uiautomator dump` 可能无法等待空闲；必须确认导出成功，再读取文件，避免拿旧层级作证据。

## 真实账号测试入口

`DirectQuarkDeviceTest` 是显式启用的 instrumentation 测试，只有传入 `realQuark=true` 且设备已有用户授权后才执行。它会读取授权目录并主动刷新令牌，不能混入默认无凭证测试。在触发过上述包管理器问题的模拟器上，优先使用独立验证版内置诊断与界面复验。

## 待实车验收

目标厂商系统的实际 SDK／ABI、覆盖安装、冷启动与休眠唤醒、物理断电恢复、熄屏信号、导航／电话音频焦点、方向盘控制、长时间弱网、低磁盘空间和扬声器输出。周期保存不构成零丢失保证；厂商仅显示黑色遮罩而未报告熄屏时，还需确认可用的电源信号。

## 网页登录失效与播放错误修复（0.5.4）

在已安装的 0.5.3 正式 APK 内绕过媒体缓存，实际获取选中歌曲下载地址：网页 API 返回 HTTP 401、status 401、code 31004。临时改用 PC API 主机仍得到相同拒绝。该来源绑定当前网页账号；设备同时存在开放授权，但它不属于此来源。不能用已缓存歌曲的播放结果证明该网页凭证有效。

修复新增嵌套错误识别、错误按钮状态及原网页登录凭证槽位复用。101 项主机自动化测试通过，包含目录读取成功但下载失败时不覆盖旧凭证、旋转后的 Cookie 验证成功再保存、失败优先于残留播放意图，以及缓冲中仍可暂停；lintRelease 通过。原目录内容、队列更新规则和缓存策略保持原设计。

BlueStacks 已覆盖安装同签名的 0.5.4（versionCode 13，非调试 APK），曲库与队列仍为 160 首，选中索引与位置不变。对原未缓存歌曲点击播放，媒体会话进入错误状态，实际界面显示“夸克账号需重新连接”，主按钮显示“重新连接”；点击后进入恢复原目录的二维码页。新的真实登录及未缓存网络读取尚待用户扫码复验，不能将修复入口的验证描述为已恢复播放。

测试 APK SHA-256：`20a5f8260f475374026b6e2f7e7ce445d75563f8895ef219610d5ac0ed4be145`。临时网络诊断组件已卸载，保留主应用数据及已授权的 ADB 调试状态。

后续核对：退出旧网页登录，进入“添加音乐目录 → 夸克网盘目录”，在没有重新扫码的情况下，现有 Token 授权成功读取授权根目录，返回一个子目录。因此先前的 HTTP 401 只证明旧 Cookie 会话被拒绝，不能推断 Token 授权已失效；恢复方案应保持 Token 接入，不再要求用户重新网页登录。本次只验证 Token 目录读取，未据此声称 Token 下载或原目录迁移已通过。

### 模拟器改用 Token 测试（应用继续保留网页登录）

用户明确要求只移除模拟器里的网页登录状态。曾开始移除生产代码的变更已全部撤回，网页登录实现及其测试保留，101 项自动化测试再次通过。已安装 APK 仍为上述同一个 0.5.4，没有换成移除网页登录的构建。

旧目录与 Token 目录的文件标识未能直接匹配，迁移尝试在写入前终止。随后通过应用 Token 授权入口选择音乐目录，扫描 160 首歌曲并建立新的 Token 队列，实际播放位置推进到约 34 秒后暂停。使用显式授权的测试组件清理一个旧网页登录来源、160 条旧曲库元数据、其旧快照、旧 Cookie 槽位、网页账号入口及 WebView Cookie；保留已有 Token、新队列顺序与位置。未删除网盘文件。

清理后通过安装包实际 `ResolvingTrackSource` 绕过媒体缓存验证：来源为 `QUARK_OPEN`，获取下载地址 HTTP 200，媒体 Range HTTP 206，成功读取 65,536 字节。旧网页账号入口不存在，Token 账号保留。临时组件已卸载，正常重开显示 160 首 Token 曲库、恢复约 34 秒并保持暂停，供用户继续实测。

## 0.5.5 文件大小限制提示

Token 队列的未缓存歌曲读取复现 HTTP 400、status -1、errno 23018，服务端明确返回 `download file size limit[52428800]`。因此这次是 50 MiB 单文件下载限制，不是 Token 失效或网络中断。播放器现在识别该错误、展示服务端大小上限；未知错误详情不直接展示。文件限制不触发刷新令牌或网络无限重试，原有瞬时网络错误的重试策略保留。普通加载提示简化为“正在缓冲”。

103 项主机自动化测试通过，lintRelease 通过；新增用例验证 HTTP 400 下的业务码解析、50 MiB 显示、不重复请求或轮换 Token，以及未知详情脱敏。0.5.5（versionCode 14，非调试、同签名）已覆盖安装到原 BlueStacks 实例。APK SHA-256：`04e1c07781b9387fbc0d2e04adf66a7ec69b4e89ac8f89f1fe7cbab331a7643f`。

部署时 Mac 已锁屏，电脑操作工具无法读取应用画面，因此本次新提示的模拟器视觉复验尚未完成。临时诊断组件已卸载。原文件仍受到夸克侧下载限制，文案修复不能声称恢复了超限文件播放；网页登录代码保留，模拟器测试仍使用 Token 授权。

## 0.5.6 网页大文件音频路径

用户完成独立网页登录后，Python 实测一个名称和大小唯一匹配的 54,123,966 字节 FLAC：Cookie 视频播放接口返回 HTTP 400／code 14018、零媒体地址；PC 下载接口普通浏览器 UA 返回 HTTP 400／23018；同一 Cookie 和文件改用 PC 客户端 UA 后，地址 HTTP 200／code 0，媒体 Range HTTP 206，读取 65,536 字节并识别到 FLAC 文件头。

网页登录提供方已接入这一经验证的 PC 下载请求方式，并继续交给现有 Media3 数据源按需读取、缓存。Token 提供方未变更，未做隐式 Cookie 回退。105 项主机自动化测试及 lintRelease 通过，新增用例验证 PC 参数、请求头、轮换后 Cookie 向播放器传递，以及仍受限时的错误分类。

同签名非调试 0.5.6（versionCode 15）已覆盖安装到 BlueStacks，APK SHA-256：`7beecc8b50b13baf45df4216c9088913907dc50faf559a07f6b8bdf2c3a55915`。模拟器账号和来源保持 Token 测试配置。本次 Python Cookie 实验结束后销毁内存凭证，没有导入播放器；APK 网页来源的整首解码、跳转、完整离线仍需单独登录后验收，不能把首段读取结果描述为整首播放完成。

## 0.5.7 默认网页扫码登录

添加音乐目录的夸克入口改为优先复用网页会话，无网页账号时进入网页二维码。账号管理将网页扫码作为主要入口，Token 授权和其目录保留在“其他登录方式”；同名来源区分网页／Token。已有来源和队列不因默认入口变化而迁移。

107 项主机自动化测试及 lintRelease 通过。新增路由用例验证只有 Token 账号时默认入口仍打开网页二维码，并保留 Token 账号；另验证 Token 授权入口继续可用。0.5.7（versionCode 16，非调试、同签名）已覆盖安装到 BlueStacks。APK SHA-256：`6b9e2a0a2601892457d8db390d6bc6a030e4acbd3e0f42e46b1ccf30595b3351`。

实际界面中网页与 Token 来源均在，账号管理显示网页登录为主要入口、Token 为其他方式；默认添加目录入口复用已保存的网页登录，未要求再次扫码。更新前暂停，重开恢复原歌曲及约 1 分 42 秒位置。本轮仅验证默认入口及会话复用，不扩展为大文件整首播放验收。

## 0.5.8 来源登记、重复导入与撤销清理

按用户要求在来源登记阶段解决重复，而非在歌曲列表末端按歌名去重。新增数据库访问入口登记：已知账号/目录入口直接复用 `source_id`；不同登录方式首次关联由用户明确确认同一账号、同一目录，随后记住关联。目录选择器先检查登记，再决定是否读取目标目录；首次建库复用选择器已经读取的根列表。

114 项主机测试与 lintRelease 通过，覆盖并发重复添加 12 次仅扫描一次、跨方式确认后仍只有一个来源和一次扫描、根列表不被重复读取、不同账号独立添加、移除后别名清理、晚到的刷新任务及旧位置快照不能恢复已移除来源。首次跨登录方式的确认是必要的身份关联步骤：当前取得的网页/Token 账号与目录标识不能直接互认，不能用目录名自动断定账号相同。

BlueStacks 实际通过应用“移除 Token 授权与目录”清理已撤销的本机 Token，曲库从 320 条恢复为 160 条，网页来源保留。该操作没有向 Token 接口发送验证或续期请求，也未删除网盘文件。随后从网页入口再次定位相同目录，显示“该目录已在曲库中，可直接使用，无需重新读取”；点击使用后仍是 160 首，提示已使用现有曲库。

移除条目时发现序号未随位置变化即时刷新，已修复重新绑定序号；最终 APK 重开后确认序号从 01 连续排列且曲库为 160 首。当前歌曲保留在暂停状态。本轮没有再次授权已撤销的 Token；跨方式关联与扫描计数使用自动化用例验证。

最终安装及交付为 0.5.8（versionCode 17，同签名非调试包），SHA-256：`dde12a44d4af551ca66e98e6b4d735a94c54a48c5525b4047901c5efb7e94c5c`。早期同版本测试构建只用于发现序号刷新问题，不作为交付包。

## 0.5.9 缓存末片及时提交

用户报告首次播放到末尾仍显示部分，而下一首显示完整。读取已保存缓存时，指定曲目已连续覆盖全部 28,603,378 字节，没有缺口；此检查在目标进程结束后进行，不能反推现场每一时刻的缓存状态。进一步核对固定版本 Media3 1.9.4 的 CacheDataSink：末片在关闭时提交；ProgressiveMediaPeriod 可以在已读完数据但尚未继续读取 EOF/关闭的阶段等待播放缓冲。

播放流缓存增加 CompletingCacheSink，在已知范围长度的最后一个字节成功写入后立即关闭并提交末片。未知长度保持正常关闭流程，完整判断仍要求连续覆盖，不掩盖中途跳转造成的缺口。117 项主机测试及 lintRelease 通过；新增测试验证不请求 EOF/不关闭读取器时末片也可见、有前部缺口仍不算完整，以及未知长度和重复关闭。

BlueStacks 实测：仅清空指定曲目的临时流缓存（确认无离线缓存，清理后缓存字节数为 0），从头正常速度播放约 4 分 31 秒，不拖动、不提前保留离线。开始显示在线，随后部分；在 4:25／4:31 时实际画面已显示已缓存完整，尚未切歌。随后正常自动进入下一首，最终暂停。未进行扬声器音质评测，此结果也不意味着所有存在真实缺口的文件都会被标为完整。

同签名非调试 0.5.9（versionCode 18）已覆盖安装，APK SHA-256：`4c8b461e1a5c28965ad9e27ee13a55061003d233af15f70cdee1357e733fbe56`。临时诊断组件已卸载，账号、来源及显示设置保留。


## 0.5.10 界面审查修复

按 apple-design 与 animate 审查完成紧凑状态常显及详情入口、窄竖屏空间分配、明确循环模式选择、来源移除操作层级与确认框文字，以及进度刷新和缓存查询分离。实现详情与验证边界见 [设计审查修复记录](../docs/design-review-0.5.9.md#0510-修复与复验)。

123 项主机自动化测试（零失败、零错误）、Release 构建及 lintRelease 通过。BlueStacks Android 13 同签名覆盖安装 0.5.10（versionCode 19，非调试 APK），保留网页登录与 160 首曲库。实屏验证普通竖屏、360×640 竖屏和 640×360 横屏（后两者分别使用 1.0/1.6 倍字体），关键状态、时间、主要操作及至少一整行歌曲保持可见。状态可点开查看详情；模式选择实时反映到设置与主界面，并恢复原顺序模式；移除确认明确影响范围，实际测试只取消，没有删除数据。

继续播放、自动切歌、拖动进度后继续播放和暂停已实测。绘制采样 2,156 帧，janky frames 为 71（3.29%），P50/P90/P95/P99：6/15/23/57 ms；这是包括切歌、封面及拖动的短时采样，没有进行同负载旧版对照或持续下载压力测试，不作为声音卡顿已解决的证据。测试结束暂停于约 2:43，显示设置均恢复，保持调试开启。未覆盖完整日夜主题矩阵、TalkBack 语音、实车电源与音质。

安装包 SHA-256：`986bf0f1997af0f559a703d7cd433fd46e944107bed1e1875e673530ee411271`。签名 SHA-256：`6fc4d5fc1c0ac009fd2248e0fd200e9eda1c43242ec61ff4c6a3867cb91056c5`，与旧版一致。下载目录副本与本次测试包相同；未创建 Git 标签或发布 Release。

## 目录与排序设备回归

目录管理、提示消息与队列排序的设备回归使用合成音频，只允许运行在曲库和队列均为空的独立验证包中：

```sh
./gradlew -PdeviceTestBuildType=authCheck :app:assembleAuthCheck :app:assembleAuthCheckAndroidTest
adb install -r app/build/outputs/apk/authCheck/app-authCheck.apk
adb install -r app/build/outputs/apk/androidTest/authCheck/app-authCheck-androidTest.apk
adb shell am instrument -w -r -e class com.cruisetune.player.LibraryActionsDeviceTest \
  com.cruisetune.player.authcheck.test/androidx.test.runner.AndroidJUnitRunner
```

设备测试需实际报告 `OK (1 test)`，不能只看 `adb` 的退出码。测试覆盖曲库更新提示、自然排序、排序时保留当前歌曲和进度、播放与暂停状态、排序持久化、取消移除及确认后的清理范围；结束时清理自身创建的条目和音频。真实网盘连接与系统目录选择器需另外实测。

0.5.13 回归：BlueStacks Android 7.1.1（API 25）的添加目录崩溃源于 `Toast.makeText(Activity)`，系统提示布局经 AppCompat 字体解析时抛出 `ArrayIndexOutOfBoundsException`。主界面和网页登录提示改用应用上下文。129 项主机测试（零失败、错误、跳过）、lintDebug 和独立验证包构建通过；上述设备回归报告 `OK (1 test)`，并验证曲库和队列的排序选中态相互独立、空队列从所选排序的第一首开始播放。

独立验证包中通过系统目录选择器添加合成音乐目录，包含一个子目录，共读出 3 首；重复添加仍为 3 首，没有闪退。通过界面移除后原始测试音频仍在。本轮未覆盖真实网盘账号联调。

按 apple-design 对本次交互做实屏 review：修正曲库与队列的选中态混用，以及空队列开始播放时忽略排序的问题；排序面板改用单选列表，普通横屏四个选项完整可见，360×640 dp、1.6 倍字体下文字可换行并可滚动。最终界面改动后复验 13 项界面测试、lintDebug 和设备回归，均通过。未进行 TalkBack 语音与实车音质验收。

## 封面、离线与歌词回归

主机测试覆盖 LRC 时间戳、多时间标记、正负偏移、重复时间点、间奏、编码与大小限制，以及同目录匹配、切歌取消和界面状态。歌词使用合成文字；网盘目录与下载请求通过模拟提供方验证，不使用真实账号。

设备回归使用含歌手、专辑和封面的合成 FLAC。先在本机安装 FFmpeg，并在 `android-app/` 下生成测试资源：

```sh
mkdir -p app/build/generated/player-fixtures
ffmpeg -f lavfi -i color=c=0xDDBB84:s=256x256 -frames:v 1 \
  app/build/generated/player-fixtures/cover.png
ffmpeg -f lavfi -i anullsrc=r=8000:cl=mono \
  -i app/build/generated/player-fixtures/cover.png -map 0:a -map 1:v \
  -t 60 -c:a flac -c:v png -disposition:v attached_pic \
  -metadata title='Demo Track' -metadata artist='Demo Artist' -metadata album='Demo Album' \
  app/build/generated/player-fixtures/details-fixture.flac
./gradlew -PdeviceTestBuildType=authCheck :app:assembleAuthCheck :app:assembleAuthCheckAndroidTest
adb install -r app/build/outputs/apk/authCheck/app-authCheck.apk
adb install -r app/build/outputs/apk/androidTest/authCheck/app-authCheck-androidTest.apk
adb shell am instrument -w -r -e class com.cruisetune.player.PlayerDetailsDeviceTest \
  com.cruisetune.player.authcheck.test/androidx.test.runner.AndroidJUnitRunner
```

资源只进入独立测试 APK，不进入源码或正式应用包。测试要求独立验证包的曲库和队列为空，读取真实 FLAC 标签，验证歌词前后跳转、暂停与封面切换，以及 Media3 离线缓存和完成状态。提供方使用带延迟的本机合成文件，因此测试期间临时取消下载的网络前提，结束后恢复；不能把这个结果当作真实网盘网络可用性的验证。成功标准为 `OK (1 test)`。测试截图只包含合成内容，保存在验证包的外部文件目录，不纳入版本控制。

本轮验证：144 项主机测试通过；最终文字布局调整后，16 项相关界面测试及 lintDebug 复验通过。BlueStacks Android 7.1.1 上，原有排序／移除回归和上述详情回归分别报告 `OK (1 test)`。另通过系统目录选择器添加含空格文件名的合成 FLAC 与同名 LRC，确认歌词读取、暂停时调整进度及间奏显示；大字体竖屏可从详情面板切换封面／歌词，短横屏保留主要播放按钮和歌曲列表。测试后恢复原分辨率与字体大小。未覆盖真实网盘账号联调、FLAC 内嵌歌词、TalkBack 语音或实车音质。

审查修复后：进度、歌词和详情使用统一的拖动预览时间；当前歌词缓存跨显示模式和 Activity 重建保留，刷新音乐目录时失效，失败结果不缓存。详情改为页面内展开，保留歌名、进度和主要播放按钮，并在返回时恢复列表位置。歌手与专辑文本行预留高度，缺失或迟到的信息不再改变封面图框尺寸。

新增回归覆盖歌词往返只读取一次、缓存失效和失败重试、信息加载前后的图框一致、小屏展开与返回、列表位置恢复。设备回归会临时移除自己创建的歌词文件验证内存缓存，在拖动预览中强制刷新详情验证时间一致，并实际操作展开页的播放、暂停、上一首和下一首。

修复复验：151 项主机测试通过，最终导航宽度与安全边距调整后，16 项界面测试和 lintDebug 再次通过。BlueStacks 的普通横屏、640×360 与 360×640 dp／1.6 倍字体下，详情回归分别通过；实屏核对展开页不遮挡播放操作，顶部安全边距及返回入口正常。测试结束恢复原分辨率和字体大小。

v0.5.14 发布前验证：空曲库按钮改为“添加音乐”，补充显示完整性、来源入口和播放状态切换回归。153 项 Android 主机测试、15 项发布工具测试、8 项连接服务测试、release lint 及本地构建通过；该版本独立验证包在 BlueStacks 上的详情回归和目录／排序回归分别报告 `OK (1 test)`。合成音频仅存在于测试 APK，未进入 release APK；提交内容及文档通过隐私检查。

## 内嵌歌词回归

主机测试覆盖 Vorbis 字段名、ID3 USLT／ULT 编码与内容描述符、M4A 歌词字段、同步／纯文本优先级、损坏或过大标签、迟到的内嵌标签取消同目录请求，以及纯文本滚动位置保持。仅解析歌词字段，不把普通注释、歌手信息或不支持的 SYLT 帧当作歌词。

先按上一节生成 `details-fixture.flac`，再在 `android-app/` 下生成带合成歌词的音频（`-n` 拒绝覆盖已有文件）：

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
./gradlew -PdeviceTestBuildType=authCheck :app:assembleAuthCheck :app:assembleAuthCheckAndroidTest
adb install -r app/build/outputs/apk/authCheck/app-authCheck.apk
adb install -r app/build/outputs/apk/androidTest/authCheck/app-authCheck-androidTest.apk
adb shell am instrument -w -r -e class com.cruisetune.player.EmbeddedLyricsDeviceTest \
  com.cruisetune.player.authcheck.test/androidx.test.runner.AndroidJUnitRunner
```

测试只允许运行在空曲库、空队列的独立验证包中。它另行构造标准 ID3v2.4 USLT 测试文件，避免仅覆盖 FFmpeg 的同名 TXXX 写法；为每种音频生成不同内容的同名 LRC，验证内嵌歌词优先。还验证纯文本滚动、暂停时前后跳转、切歌清除旧歌词和无内嵌歌词时回退，并通过模拟提供方及真实离线缓存验证断开提供方后的歌词显示。测试检查音频内容未被改写，结束后清理自身创建的曲库记录、下载和临时文件；成功标准为 `OK (1 test)`。不使用真实网盘账号，也不验证在线歌词网站。

验证结果：166 项主机测试、lintDebug 和独立验证包构建通过。BlueStacks Android 7.1.1 的普通横屏、640×360 与 360×640 dp／1.6 倍字体下，内嵌歌词回归分别报告 `OK (1 test)`，并断言主要播放控件完整可见、纯文本视口至少容纳一行；原有封面／同目录歌词／离线回归也报告 `OK (1 test)`。应用 APK 未包含合成音频，测试结束恢复原分辨率与字体设置。未验证真实网盘账号、TalkBack 语音或实车使用。

v0.5.15 发布前复验：166 项 Android 主机测试、15 项发布工具测试、8 项连接服务测试、release lint 和本地构建通过；该版本独立验证包的内嵌歌词及原有播放详情回归分别报告 `OK (1 test)`。本地 release 包版本为 0.5.15／24，非 debuggable，未包含合成音频；提交内容与文档通过隐私检查。

## 切歌元数据归属回归

沿用上一节的合成音频资源。`TrackTransitionDeviceTest` 使用三首内容不同的测试歌曲：带封面和歌词的 FLAC、无封面及歌词的 MP3、带不同封面和歌词的 MP3。后两首在测试运行时生成，不改动任何用户音频；模拟提供方的延迟用于覆盖切歌过渡状态。

```sh
./gradlew -PdeviceTestBuildType=authCheck :app:assembleAuthCheck :app:assembleAuthCheckAndroidTest
adb install -r app/build/outputs/apk/authCheck/app-authCheck.apk
adb install -r app/build/outputs/apk/androidTest/authCheck/app-authCheck-androidTest.apk
adb shell am instrument -w -r -e class com.cruisetune.player.TrackTransitionDeviceTest \
  com.cruisetune.player.authcheck.test/androidx.test.runner.AndroidJUnitRunner
```

仅在空曲库、空队列的独立验证包中运行。测试实际点击下一首、上一首和队列行，并覆盖快速往返与自然播完自动切歌；验证新歌曲不继承旧封面、旧歌手信息或旧歌词，也能显示自身不同的封面和歌词。测试不申请音频焦点，结束时恢复验证包的相关设置并清理自身数据，成功标准为 `OK (1 test)`。

v0.5.16 发布前验证：171 项 Android 主机测试、15 项发布工具测试、8 项连接服务测试、release lint 和本地构建通过。BlueStacks Android 7.1.1 上，切歌元数据归属、内嵌歌词、原有播放详情及目录／排序四项设备回归分别报告 `OK (1 test)`；正式包曲库和音频未改动。release 包未包含合成音频，源码与文档通过隐私检查。

## 队列跟随回归

主机测试覆盖当前项定位、已可见时不滚动、重复状态刷新保留手动浏览位置、触摸／惯性滚动期间等待、排序后的 ID 匹配、空队列，以及列表重建后的行为。

沿用前文生成的 `details-fixture.flac`，在 `android-app/` 下构建、安装独立验证包后执行：

```sh
./gradlew -PdeviceTestBuildType=authCheck :app:assembleAuthCheck :app:assembleAuthCheckAndroidTest
adb install -r app/build/outputs/apk/authCheck/app-authCheck.apk
adb install -r app/build/outputs/apk/androidTest/authCheck/app-authCheck-androidTest.apk
adb shell am instrument -w -r -e class com.cruisetune.player.QueueFollowDeviceTest \
  com.cruisetune.player.authcheck.test/androidx.test.runner.AndroidJUnitRunner
```

测试要求验证包曲库和队列为空，使用 120 首引用合成音频的条目，验证进入队列、上一首／下一首、自然切歌、队列排序、详情返回、重新点播和清空队列；同时检查曲库浏览位置未被跟随覆盖。测试不申请音频焦点，完成后清理自身条目和临时音频，成功标准为 `OK (1 test)`。不在正式包中运行，不使用真实网盘账号。

验证结果：177 项主机测试、lintDebug 和独立验证包构建通过。BlueStacks Android 7.1.1 上，普通横屏、640×360 与 360×640 dp／1.6 倍字体的队列跟随回归分别报告 `OK (1 test)`；原有切歌元数据归属及目录／排序回归也分别通过。测试结束恢复原分辨率与字体设置，未修改正式版曲库或音频文件。

v0.5.17 发布前复验：177 项 Android 主机测试、15 项发布工具测试、8 项连接服务测试、release lint 和本地构建通过；该版本独立验证包的队列跟随、切歌元数据归属及目录／排序回归分别报告 `OK (1 test)`。release 包未包含合成音频，源码与文档通过隐私检查。

## 播放顺序快捷按钮回归

沿用合成 `details-fixture.flac`，仅在空曲库、空队列的独立验证包中运行：

```sh
./gradlew -PdeviceTestBuildType=authCheck :app:assembleAuthCheck :app:assembleAuthCheckAndroidTest
adb install -r app/build/outputs/apk/authCheck/app-authCheck.apk
adb install -r app/build/outputs/apk/androidTest/authCheck/app-authCheck-androidTest.apk
adb shell am instrument -w -r -e class com.cruisetune.player.PlaybackOrderDeviceTest \
  com.cruisetune.player.authcheck.test/androidx.test.runner.AndroidJUnitRunner
```

测试验证按钮位于歌词与离线入口之间、空队列禁用、切换期间防重复点击、随机选中态、返回顺序后的队列恢复、暂停时精确保留进度和播放中保留当前歌曲及播放意图；还检查队列排序会同步按钮状态，设置中已没有随机播放或循环方式入口。仅使用合成音频，不申请音频焦点、不使用真实网盘账号，测试结束清理自身数据；成功标准为 `OK (1 test)`。合成截图仅保存在验证包的外部文件目录，不纳入源码。

验证结果：179 项主机测试、lintDebug 和独立验证包构建通过。BlueStacks Android 7.1.1 的普通横屏、640×360 与 360×640 dp／1.6 倍字体下，快捷按钮回归分别报告 `OK (1 test)`，并断言三个操作文字完整、点击区域至少 48 dp；原有队列跟随与切歌元数据归属回归也通过。已按 apple-design 核对状态反馈与横屏实屏布局，测试后恢复原分辨率和字体大小；未改动正式版曲库或原始音频。

v0.5.18 发布前复验：179 项 Android 主机测试、15 项发布工具测试、8 项连接服务测试、release lint 和本地构建通过；该版本独立验证包的快捷按钮、队列跟随及切歌元数据归属回归分别报告 `OK (1 test)`。release 包未包含合成音频，源码与文档通过隐私检查。
