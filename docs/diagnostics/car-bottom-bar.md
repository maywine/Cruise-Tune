# 领克 OSN 2.0 底栏遮挡调查

调查日期：2026-09-15。范围：领克 01、OSN 2.0、原车底部控制栏遮挡 Cruise Tune。用户已确认照片底栏为原车自带。

## 结论与证据边界

Android 可以提供系统栏占用的窗口边距；能否据此得到这台车的底栏高度，取决于原车系统是否正确上报。**目前未取得实车窗口读数，不能承诺已自动识别，也不能把照片中的比例或其他应用的布局常量当成实车高度。**

Cruise Tune 已经应用当前系统栏和刘海边距。本次发现的缺口是：尚未对这台车核实稳定边距、窗口裁剪和当前边距之间的差异，现有实现也没有针对“系统未上报底栏”的校准入口。

小八的资源和其网站说明证明它提供车型专用的原车 Dock 显隐控制及自建底栏。没有恢复出其受加固保护的业务方法，不能声称已经找到它读取原车底栏高度的算法。

本次增加独立窗口诊断工具，未修改播放器的布局、播放逻辑或版本号；此前未提交的播放与缓存修复保留。

## 照片和当前代码

照片中原车底栏覆盖到了歌曲详情下方的操作区域，主要播放控制未完整显示。照片有透视，无法换算出可靠的屏幕 px/dp。

代码位置：

- `android-app/app/src/main/java/com/cruisetune/player/ui/CruiseActivity.kt`：调用 `WindowCompat.setDecorFitsSystemWindows(window, false)`，由应用处理系统栏边距。
- `android-app/app/src/main/java/com/cruisetune/player/ui/MainActivity.kt`，`buildScreen()`：根布局监听 Insets，将 `systemBars | displayCutout` 的当前值加到初始 padding；底边为 `8dp + safe.bottom`。
- 三个夸克登录 Activity 采用相同的当前边距策略，后续修复需要覆盖全部页面及相关弹窗。
- `PlayerLayoutSpec` 目前按配置中的窗口 dp 选择布局。若额外避让压缩可用空间，需要复核紧凑布局判定和详情区高度，确保控制按钮始终有位置。

这符合 Android 标准边到边布局的常规做法，但不是车机定制栏一定上报正确的证据。[Android 边到边布局说明](https://developer.android.com/develop/ui/views/layout/edge-to-edge)

## 小八 APK 静态分析

原包：`xiaoba-launcher-2.7.3-0619.apk`。

| 字段 | 结果 |
| --- | --- |
| 包名 | `com.xiaoba.launcher` |
| 版本 | `2.7.3`，code `207399` |
| min / target SDK | `24 / 31` |
| 编译 SDK | `34` |
| SHA-256 | `25effcacd74d56c3941adc2e44022ede75cf0f3de838ee56901d762bae1240ba` |
| 分析方式 | JADX 1.5.6，解码 manifest、资源和可见 DEX |

分析产物留在被 Git 忽略的 `outputs/car-bottom-bar/xiaoba/`，不将第三方 APK、反编译源码或用户照片纳入公开仓库。

| 证据位置（相对反编译目录） | 已确认事实 | 不能推出的结论 |
| --- | --- | --- |
| `sources/com/stub/StubApp.java` | Application 加载 `libjiagu`；业务类未以可读 Java 方法恢复 | 不能说已经完整反编译业务逻辑，也不能把缺少方法当作功能不存在 |
| `resources/AndroidManifest.xml` | 有 `FloatingDockService`、`FloatingBottomBarService`、`DockAccessibilityService`；声明悬浮窗、系统设置、状态栏等权限 | 声明权限不等于系统实际授予；不能照搬权限列表来修复播放器布局 |
| `resources/res/values/styles.xml`，`Theme.XiaoBaLauncher` | 全屏主题、透明系统栏 | 不是读取底栏高度的实现 |
| `resources/res/layout/activity_desktop_settings.xml`，约 1615–1656 行 | `menu_lynk09_dock` / `lynk09_dock_switch` 明确对应领克 01/03/05（OSN 2.0）/09 的底栏控制 | 不能得知内部使用的广播、服务、设置键或高度 API |
| 同文件，约 1267–1306 行 | 有“底部栏尺寸”设置 | 不能当作原车底栏的自动测量 |
| `resources/res/layout/activity_main.xml`，约 412–422 行 | 自己的 `dock_container` XML 高度为 `112dp`，内部带上下 padding | **112dp 属于小八自己的布局，不是领克底栏高度**；运行时也可能改写 |
| `resources/res/layout/floating_bottom_bar.xml` | 另有自建悬浮底部栏布局，根高度为 `wrap_content` | 不能与原车系统栏混为一谈 |

## 小八使用指南提供的新线索

用户提供的[横屏使用指南](https://app.xbcars.cn/desktop-guide)中，第 13 节区分了小八自身底栏尺寸和车型专用原车 Dock 控制；第 16 节说明通知按钮的长按操作可切换原车 Dock 显隐。这与 APK 资源中的车型选项一致。

从该页进入的[分车型适配说明](https://doc.xbcars.cn/10-%E5%B8%B8%E8%A7%81%E9%97%AE%E9%A2%98/02-%E5%88%86%E8%BD%A6%E5%9E%8B%E9%80%82%E9%85%8D%E5%9D%91)明确将 OSN 2.0 升级后 Dock 无法隐藏列为问题，并说明该系统使用领克 09 对应开关。**这支持“按车型控制原车栏”的判断，没有给出高度查询接口。**

进一步的[悬浮底部栏说明](https://doc.xbcars.cn/03-%E6%82%AC%E6%B5%AE%E4%B8%8E%E5%A4%9A%E5%B1%8F/02-%E6%82%AC%E6%B5%AE%E5%BA%95%E9%83%A8%E6%A0%8F)说明其底栏按应用白名单显隐，显示时会隐藏原车 Dock。“底部栏白名单”不能视为让目标应用自动获得正确窗口边距的接口。

文档没有提供可直接接入 Cruise Tune 的厂商 API、广播参数或高度属性名。在线指南与手中 2.7.3 APK 的设置分组也不完全一致，应以用途交叉确认，不能用新版文档替代旧包的方法级证据。网站是小八产品自己的说明，不是领克官方 SDK 文档。

## BlueStacks 实测

环境：BlueStacks Air，Android 13 / API 33，arm64-v8a，1920 × 1080 px，320 dpi，字体比例 1.0。

### 参考 APK

- 原 APK 安装返回 `Success`，版本读取正确。
- 启动观察到白色页面，随后退出，未能进入配置页。
- 系统 `ApplicationExitInfo` 记录 `reason=2 (SIGNALED)`、`status=9`，即 SIGKILL。启动日志出现过 `CoreComponentFactory` 类找不到的记录，但后续仍执行了加载和绘制；不能单凭这条日志认定退出原因，也不能断言一定是反模拟器检测。
- 未设为默认桌面，未额外授予悬浮窗、无障碍或写系统设置权限。结束时强停参考应用，默认 Home 仍为 BlueStacks Launcher。
- BlueStacks 没有领克原车 Dock，因此即使参考应用能运行，也不能直接证明领克专用显隐接口有效。

### Cruise Tune 与窗口探针

- 模拟器现有 Cruise Tune 为 `0.5.21 / code 30`，未覆盖安装；底部“上一首 / 继续 / 下一首”均可见，停留在暂停恢复状态。
- 独立探针 `com.cruisetune.windowprobe / 1.0` 安装、启动成功，初始页面已目视检查。
- 切换“请求系统避让”后，日志确认根布局由 `1920 × 1080`、屏幕原点 `(0,0)` 变为 `1920 × 1032`、原点 `(0,48)`；根 padding 上边由 `64px` 变为 `16px`，避免重复扣除顶部系统栏。
- 两种模式的当前/稳定底边和可见区域底差都是 `0px`，顶部系统栏均报告 `48px`。
- 系统资源 `navigation_bar_height` 和横屏值均为 `96px / 48dp`，即使当前并无底部导航栏占位仍可非零。**不能只查这个资源就当成当前底栏高度。**
- 模式切换后的最终视觉复验遇到 Mac 锁屏而停止；上述切换尺寸结论来自日志，未宣称该终态已目视复验。未做实车测试。
- 没有改变尺寸、密度、字体、旋转、默认桌面或播放器登录数据；ADB 按此前用户要求保持开启。

## 应用能读取什么

| 信息 | 用途与边界 |
| --- | --- |
| `WindowInsetsCompat.getInsets(systemBars | displayCutout)` | 当前系统上报的边距，Cruise Tune 已使用；若厂商栏未上报或可见标记异常，可能不足 |
| `getInsetsIgnoringVisibility(...)` / 旧系统稳定边距 | 获取与暂时显隐无关的系统保留区域；当前值为 0、稳定值正确时可能直接解决。仍不是任意覆盖窗口的高度探测器 |
| `getWindowVisibleDisplayFrame()` 与根布局屏幕位置 | 对照系统报告的可见矩形；只在相同屏幕坐标内求交，不能把整块物理显示和分屏窗口直接相减；键盘影响必须另行区分 |
| 当前窗口 bounds、根布局尺寸 | 判断系统是否已经缩小窗口，防止重复扣边距 |
| 导航栏资源尺寸 | 只用于诊断对照；无栏、横屏、厂商定制或隐藏时可能仍非零 |

Android 对当前 Insets 和稳定 Insets 的定义见 [WindowInsets API](https://developer.android.com/reference/android/view/WindowInsets)。API 30 以下可使用 AndroidX 兼容层；诊断工具为减少依赖直接展示平台旧接口，实际修复仍需在播放器的兼容层中复验。

## 建议的修复决策

1. 在实车保持原车底栏正常显示，打开探针，记录首屏和复制出的读数；再切换“请求系统避让”记录一次。独立包可能受到与播放器不同的厂商应用白名单规则，结果需回到播放器确认。
2. 若当前边距不足而稳定边距正确，统一应用安全区域处理，按同一坐标下的占用取最大值，不能简单累加当前值和稳定值。
3. 若请求系统避让后系统确实缩小了窗口且底部完整，评估该车采用系统布局路径；避免再手动补同一块空间。Android 15 / target 35 的强制边到边行为需单独处理。
4. 若所有标准读数都缺失，播放器提供可保存、可恢复自动值的“底部留白”校准。以实际可用内容区域重新排布控件；不要仅平移按钮或覆盖一个透明块。
5. 只有获得经过实车验证、适合普通应用使用的厂商高度接口时，才增加厂商适配。保留原车空调栏并避让即可，不把隐藏/禁用原车 Dock 作为播放器的默认方案。

验收需包含底栏显示/隐藏切换、横屏详情与列表、来源/登录/设置页面、重开应用、字体放大，以及全部播放按钮完整可见且可点击。

## 交付

- 独立诊断源码及构建说明：`tools/window-insets-probe/`。
- 本地 APK：`outputs/car-bottom-bar/CruiseTune-WindowProbe-1.0.apk`，同一文件复制至用户下载目录。
- 原始照片、第三方反编译产物及设备日志仅保留在被忽略的 `outputs/` 中；本报告不包含账户、曲库文件标识或授权信息。

尚未取得实车读数，播放器底栏适配未被标记为修复完成，也未提交或发布新版本。

## 补充复验：BlueStacks 启动与 mock 边界

同日按用户要求继续尝试运行小八，取得如下新证据：

1. 系统 Activity 日志显示启动路径为 `SplashActivity → MainActivity → LicenseActivity`。这证明壳后业务至少执行到了主界面入口和激活页跳转，不能将启动时的 `CoreComponentFactory` 日志直接当作整个应用无法加载的原因。
2. 激活页阶段约 2–3 秒发生一次进程退出，系统再次拉起该 Activity，连续观察到多个不同 PID。退出信息仍为 SIGKILL，屏幕未显示可操作的激活表单。
3. 应用自己的外部文件目录中存在 `logs/restart/`，新产生的报告只记录启动初始化及未正常销毁；`logs/crash/` 为空。报告中的“内存不足/未捕获异常”等内容是通用说明，不是已定位原因。
4. 本地 APK 的 v2/v3 签名验证通过。模拟器抽样可用内存约 2.7 GiB、`/data` 可用存储约 113 GiB；这不能绝对排除瞬时资源异常，但没有资源耗尽的证据。
5. 按指南临时开启小八的悬浮窗权限（AppOps 确認为 `allow`）后重测，仍走到 `LicenseActivity` 并反复 SIGKILL。测试后关闭该权限、强停小八，保留默认桌面和原播放器数据。没有授予通知读取或无障碍等其他权限。
6. 官网下载入口仍指向 `xiaoba-launcher-2.7.3-0619.apk`。尝试重新下载时超时，未将未完成文件用于安装，未宣称完成官网包与本地包的哈希比对。

[激活说明](https://doc.xbcars.cn/01-%E5%BC%80%E5%A7%8B%E4%BD%BF%E7%94%A8/04-%E6%BF%80%E6%B4%BB%E4%B8%8E%E8%AE%BE%E4%B8%BA%E9%BB%98%E8%AE%A4%E6%A1%8C%E9%9D%A2)要求读取稳定 VIN 后进行服务端激活；[支持范围](https://doc.xbcars.cn/01-%E5%BC%80%E5%A7%8B%E4%BD%BF%E7%94%A8/02-%E6%94%AF%E6%8C%81%E8%BD%A6%E5%9E%8B%E4%B8%8E%E7%B3%BB%E7%BB%9F)列出车机系统，没有 BlueStacks 方案。VIN 依赖是正常使用的前提，**尚未证明缺少 VIN 导致当前 SIGKILL，也未证明一定是反模拟器检测**。

可行的模拟分层：

- **播放器布局**：我们控制源码，可以注入不同系统栏 Insets，并用独立遮挡层模拟底栏；应分别覆盖“正确上报高度”和“有遮挡但上报 0”。这只能验证对给定输入的处理，不能证明真实 OSN 会产生这些输入。
- **小八车辆接口**：需要明确 SDK 方法、Binder 服务或系统属性的读取路径，才能制作返回模拟车辆状态的测试替身。带加固的原包目前还没有这些方法级证据，改模拟器的型号字段不足以补齐服务。
- **小八完整运行**：车辆状态 mock 不等于完成授权，也不自动提供原车 Dock 服务。较可靠的下一步是获取适配普通 Android 的开发测试包或可定位的启动异常栈。

本轮没有修改小八 APK、注入 VIN 或实现车辆服务 mock；原包仍未在 BlueStacks 稳定运行。
