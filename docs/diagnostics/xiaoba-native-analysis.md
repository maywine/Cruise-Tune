# 小八 2.7.3：GDB 与运行时 DEX 分析

本次分析承接 `car-bottom-bar.md` 的初步静态调查。现在已取得此前加固层隐藏的部分业务代码，本文中的方法级证据优先于此前“未恢复出业务方法”的阶段结论。

## 分析范围和结果

- 对象仍为用户提供的 `xiaoba-launcher-2.7.3-0619.apk`，SHA-256：`25effcacd74d56c3941adc2e44022ede75cf0f3de838ee56901d762bae1240ba`。
- 环境为 BlueStacks Air / Android 13 / arm64-v8a。开始时小八已无安装记录，重新安装同一原包；未清除或覆盖 Cruise Tune。
- 使用模拟器原有 root 入口附加小八进程，没有修改 SELinux、APK 签名、应用代码、授权状态或车辆数据。
- GNU GDB 16.3（aarch64-linux-android）来自 Termux 软件仓库；下载依赖按仓库索引逐项核对 SHA-256，仅解包到本任务临时目录运行，不安装 Termux 应用或执行包安装脚本。
- GDB 原生附加成功，并取得运行时 DEX。JADX 恢复出 `VinHelper`、`HvacReflectionController`、车辆信号模块及底栏相关业务方法。仍有部分方法反编译失败或保持 `native`，不是完整源码恢复。

原始 APK、DEX、反编译文件与调试日志均留在被忽略的 `outputs/xiaoba-native/`，不发布第三方源码或运行内存。

## 1. 退出调用的直接证据

系统调用跟踪捕获到小八主线程执行 `kill(selfPid, SIGKILL)`。随后两次 GDB 附加分别在 `tgkill` 系统调用处截停，目标线程属于同一小八进程，信号参数为 9。

GDB 捕获的调用方返回地址，换算后位于 `libjiagu_64.so` 首个映射起点之后 `0x21d974`。它处于匿名可执行 `.bss` 区域；结合该库 ELF 的 PT_LOAD 内存范围，可归入加固库加载段的扩展内存范围。调用附近反汇编包含装入信号 9 并调用终止路径的指令。普通回溯在后续帧中止，未取得有名称的业务调用栈。

**结论限定：在本次跟踪条件下，是加固库内存区域中的代码触发了本进程终止。** 附加调试器和 strace 本身可能触发保护；这不能直接证明不带调试器时的退出必然由同一保护条件造成，更不能将其认定为 VIN 缺失或确定的模拟器检测。没有修改返回值、跳过检查或抑制 SIGKILL。

## 2. 如何得到业务代码

在 GDB 截停终止调用时，仅从目标进程中筛选结构合法的 DEX 容器，检查头部、文件长度及各索引表边界；对可能被擦除的容器魔数提供读取用的头部恢复。未将任意堆内存保存为对外交付物，也未改写目标进程。

捕获了 6 个 DEX 容器，包含重复或不同内存副本。其中业务容器为 8,858,712 字节、4,756 个类。主要分析样本为 `d373644c8c8e6f62.dex`，JADX 1.5.6 报告 22 项反编译错误；以下列出的类名、方法字符串和简单调用链均可从成功恢复的部分核对。不能从错误反编译的复杂分支推导所有机型上的精确行为。

## 3. VIN：已确认使用的接口

位置：`com.xiaoba.launcher.license.VinHelper`。

| 步骤 | 实际调用 |
| --- | --- |
| 判断适配类是否存在 | `Class.forName("com.ecarx.xui.adaptapi.device.Device")` |
| 创建设备接口 | 反射调用静态 `create(Context)` |
| 读取 VIN | 对返回对象反射调用 `getVin()` |
| 本地已验证 VIN 缓存 | 私有 SharedPreferences；内存还缓存设备 ID 约 60 秒 |
| 同步等待读取 | 工作线程读取，最多等待约 500 毫秒 |
| 降级设备标识 | `Settings.Secure.getString(contentResolver, "android_id")`，前缀为 `AID_` |
| 仍无标识 | 构造 `RND_` 前缀标识 |

VIN 的字符串校验为 17 位，限定字母数字并排除 I/O/Q。这是应用输入有效性检查，不是服务端权限检查。

**需要修正此前推断：没有 VIN 不等于没有设备标识。** 原包已经有 Android ID 降级。在恢复出的 `LicenseActivity` 激活分支中，只有检测到上述 ECARX Device 类存在、同时当前标识不是有效 VIN 时，才拦住激活并提示等待稳定 VIN；仍需正常调用授权校验。故“为模拟器 mock 一个 VIN 就能解决当前退出”没有依据。

## 4. 车辆信号与空调接口

### 车辆信号

位置：混淆类 `com.xiaoba.launcher.hw` 的 `r()` 及相关订阅、读取方法。

恢复出的调用包括：

1. 反射 `android.os.ServiceManager.getService("ecarxcar_service")` 获取 Binder。
2. `ecarx.car.IECarXCar$Stub.asInterface(IBinder)` 包装服务。
3. `ecarx.car.ECarXCar.createCar(Context, IECarXCar)` 创建车机对象。
4. 调用 `getCarManager("car_signal", ...)` 获取车辆信号管理对象；另有经 ECARX adaptapi Car 对象获取 `car_signal` 的兼容分支。
5. 以 `ecarx.car.hardware.signal.CarSignalManager$CarSignalEventCallback` 和 `SignalFilter` 注册回调。
6. 通过 `com.ecarx.xui.adaptapi.car.Car.create(Context).getSensorManager()` 获取传感器对象，调用 `getSensorLatestValue(int)`、`getSensorEvent(int)` 等。

本次在 BlueStacks 查询 `ecarxcar_service`，返回 `not found`。这里只验证了目标服务名是否存在，没有调用任何车辆控制操作。

### 空调和功能状态

位置：`com.xiaoba.launcher.hvac.HvacReflectionController`。

| 分支 | 实际调用 |
| --- | --- |
| ECARX 初始化 | `com.ecarx.xui.adaptapi.car.Car.create(Context)` → `getICarFunction()` |
| 整数状态读取 | `getFunctionValue(int propertyId, int zone)` |
| 浮点状态读取 | 优先尝试 `getCustomizeFunctionValue(int, int)`，另有 `getFloatProperty(int, int)` 兼容路径 |
| 状态订阅 | `registerFunctionValueWatcher(int[], IFunctionValueWatcher)`，用动态代理接收变化 |
| 吉利另一分支 | `com.geely.os.car.GlyCar.create(Context)` |

这些是厂商 API，不是前文举例的标准 Android Automotive `android.car.hardware.property.CarPropertyManager` 接入链路。相同的短方法名 `getFloatProperty` 不能用来认定所属 API 相同。

## 5. 原车底栏控制

位置：`FloatingDockService`、辅助类 `eb`、`com.geely.lib.oneosapi.systemui.DockBarManager`。

- 在领克 09 / OSN 2.0 所对应分支中，`eb.b(Context, boolean hide)` 构造 action 为 `com.geely.action.handler_dock`、目标包为 `com.geely.dockbar` 的 Intent，携带 `val`：隐藏为 `1001`，显示为 `1000`。最终经 `Context.sendBroadcast()` 发送。
- OneOS 分支调用 `DockBarManager.showDockNoAnim(int)`，常量 `5` 为隐藏、`1` 为全部显示；内部委托 `IDockBarService`。
- 另有 `com.geely.systemui.plugin.nav.DynamicDockBar` 及系统 UI 可见标记的机型兼容分支。

**这些证据确认的是底栏显隐控制，不是读取底栏高度。** 没有在本次核对的 DockBarManager 接口中找到高度查询方法；未向真实车辆或模拟器发送上述控制广播，也未更改底栏策略。

### 5.1 进一步核对：高度来自哪里

只沿底栏高度计算路径继续核对，得到以下直接证据：

- `DesktopSettingsActivity` 静态初始化定义 `B1 = {100, 112, 124, 136}`，分别对应小、标准、大、特大；`g0(Context)` 从本应用设置读取 `dock_size` 档位，默认索引为 1。
- `FloatingBottomBarService.onCreate()` 用 `R(B1[g0(context)])` 设置自己创建的悬浮窗口高度；其中 `R(int)` 只是 `Math.round(dp * density)`。
- 同服务 `q()` 在尺寸变化时，将相同计算值写入自己的 View 高度及 `WindowManager.LayoutParams.height`。因此这四个高度属于小八自建底栏，不是从原车 Dock 测得的值。
- `FloatingDockService.getStatusBarHeight()` 查询 Android 资源 `status_bar_height`，用于悬浮工具条上边位置；它不是底部原车控制栏的高度。
- `MainActivity.l4()` 调用系统栏隐藏接口；已恢复的 `IDockBarService` 只有显隐、滑动及动画相关方法，没有高度返回方法。

这些已核对路径未提供可复制的“原车底栏高度查询”。部分业务方法仍是 native，因此不将此次搜索扩大为所有潜在代码都不存在此能力。也没有依据把 112dp 写死为领克原车底栏高度。

播放器可读取的是当前窗口的系统栏 Insets 及稳定 Insets；只有车机将原车栏正确登记并上报时，才可直接用于避让。如果原车栏可见而所有读数均为 0，应在实车查看 `com.geely.dockbar` 的实际窗口矩形／布局资源，或提供可保存的留白校准。ADB 下读取窗口信息与普通 APK 的能力不同，不能把调试权限下获得的结果宣传为任意 APK 都可直接查询。

## 6. 车辆信息是否任何 APK 都能读取

读取成功必须同时满足：厂商类/服务存在、调用接口匹配、服务端允许该调用者访问。Java 反射只是按名字调用方法，不会自行获得服务端权限。

这次取得的是小八的客户端实现，尚未取得领克车机中 Device、ECARX 车辆服务和 Dock 接收器的实现，因此不能确认它们是否检查调用 UID、签名、权限或白名单，不能得出“任意 APK 都能读取 VIN”或“上述广播对所有车机都有效”的结论。

普通 BlueStacks 缺少对应车辆服务。后续 mock 现在有了具体接口目标，但应在隔离测试环境中模拟所需返回值，而不是把修改设备型号当成完整 OSN 环境。正常授权、原车服务和界面适配仍是不同的验证事项。

## 7. 测试结束状态

GDB 均正常 detach；小八已停止。设备上本任务临时 GDB、依赖及 DEX 导出目录已清理，并确认目录不再存在。模拟器原有 ADB 调试保持开启，未设置小八为默认桌面，未修改 Cruise Tune 播放器代码或发布新版本。
