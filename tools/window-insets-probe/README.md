# 车机窗口诊断

独立应用 `com.cruisetune.windowprobe`，版本 1.0，minSdk 23 / targetSdk 35。用于调查车机系统栏遮挡，不覆盖 Cruise Tune，也不读取其数据。无网络、存储、悬浮窗、无障碍或系统设置权限。

## 车机使用

1. 安装 `CruiseTune-WindowProbe-1.0.apk`，打开“车机窗口诊断”，保持原车底栏正常显示。
2. 拍下首屏，确认底部测试按钮是否完整可见；可点“复制测量结果”保存文字读数。
3. 点“请求系统避让”，再记录一次画面和读数。按钮变为“返回播放器布局”，可切回初始模式。
4. 提供两种模式的结果。数据为 0 只表示该接口没报告占用，不能据此断言不存在底栏。

初始模式采用边到边窗口并应用当前系统栏/刘海 padding，与播放器的处理方向一致。对照模式在创建窗口时请求系统负责避让，不重复添加 Insets。Android 15 起 target 35 的强制边到边可能影响这项请求。

为避免父视图消耗 Insets 后看不到读数，测量使用根窗口的原始 Insets。可见矩形差值按根布局的屏幕坐标计算，不将物理显示尺寸差当作底栏高度。旧系统显示平台稳定边距，新系统显示 `getInsetsIgnoringVisibility`。

这是测量辅助工具，独立包可能受不同的厂商白名单策略影响；确定修复后还需要在播放器里验证。

## 构建

从仓库根目录运行：

```sh
python3 tools/window-insets-probe/build.py
```

脚本复用 `~/.cache/cruise-tune-toolchain/paths.json` 中的 `java` / `sdk` 路径（可用 `--toolchain` 指定另一份），需要 JDK 17、Android SDK Platform 36、Build Tools 35.0.0。没有网络下载和 Gradle 依赖。

产物默认写入 `outputs/car-bottom-bar/`；可用 `--output` 改变 APK 路径。测试专用签名密钥也在该被忽略的目录中，和播放器签名无关；不要删除后重建再期待能覆盖安装旧探针。

## 验证和限制

- BlueStacks API 33 安装成功、初始页面可读、底部测试按钮可见。
- 对照切换的日志验证了系统布局避让顶部 48px，而初始模式由 padding 避让同一高度，没有重复扣除。
- 当前测试环境没有底部系统栏：实际当前/稳定底边为 0，导航栏资源仍为 96px，证明资源值不能直接当作当前占位。
- 最终切换后的视觉复验因 Mac 锁屏未继续；未宣称实车通过，未验证 API 23 设备的实际运行。
- 只在点击复制时写剪贴板；没有上传、账号标识、录音或车辆控制。

详细调查见 `docs/diagnostics/car-bottom-bar.md`。
