# 正式 APK 数据库量化审计

Android 13 / API 33 及以上的临时同签名 instrumentation。它不编译播放器的数据库源码，通过目标应用的 ClassLoader 直接调用已安装 APK 中的 `LibraryDatabase`、`JsonCodec` 和数据模型。

## 范围与隔离

- 必须提供已安装正式 APK 的完整 SHA-256；不匹配即停止。
- 正式库只读取表统计与完整性摘要，并使用 SQLite 的正常 checkpoint 获取一致副本；不导出数据库内容或读取账号凭证。
- 审计启动时暂不运行目标应用的异步 onCreate 工作，避免后台任务干扰取样。结束后正常启动应用，原 onCreate 会照常执行。
- 在目标应用私有缓存目录创建一致副本，并验证其路径与真实库不同；所有增删、损坏注入与压力写入只发生在副本。
- 创建并删除实体测试 WAV 文件，将枚举结果交给正式 APK 的 `replaceScan`；这是扫描结果入库与清理测试，不是 SAF 选择器或远端网盘写操作测试。
- 结果只返回计数、文件大小、时长及断言，不返回源名称、曲名、账号、FID 或表内容。
- 主库四张业务表在测试前后计算摘要比较；副本与测试音频在 finally 中清理。

## 构建与执行

从仓库根目录构建（JDK／SDK 与主工程一致）：

```sh
python3 tools/android-build.py --project-dir ../tools/database-audit :app:assembleDebug
```

测试 APK 位于 `tools/database-audit/app/build/outputs/apk/debug/app-debug.apk`。它必须与正式包签名一致；默认 debug 签名只适用于确实使用同一证书的安装实例。签名不一致时，使用已授权的匹配密钥为组件签名，不要卸载正式包来绕过。

先暂停播放器、记录位置，并确认设备串号。以下变量需按本机环境设置：

```sh
CRUISE_ADB=adb
CRUISE_SERIAL=127.0.0.1:5555
CRUISE_APK_SHA=11b081efdb4206ce6b30ce0a6fd64fd02fd3445748ade11e41d73ade42ff4ea5

"$CRUISE_ADB" -s "$CRUISE_SERIAL" install -r tools/database-audit/app/build/outputs/apk/debug/app-debug.apk
"$CRUISE_ADB" -s "$CRUISE_SERIAL" shell am instrument -w -r \
  -e expectedApkSha "$CRUISE_APK_SHA" com.cruisetune.dbaudit/.AuditInstrumentation

# 无论测试成功与否，都清理本组件，然后恢复正常播放器。
"$CRUISE_ADB" -s "$CRUISE_SERIAL" uninstall com.cruisetune.dbaudit
"$CRUISE_ADB" -s "$CRUISE_SERIAL" shell am start -n com.cruisetune.player/.ui.MainActivity
```

`INSTRUMENTATION_RESULT: report=` 后为 JSON。必须同时确认 `passed`、`realDataUnchanged`、`privateCloneRemoved` 为 true；不能只看进程退出码。检查组件确已卸载，尤其是在曾出现残留测试包导致 PackageManager 异常的 BlueStacks 实例上。该组件不可作为普通用户版或 Release 附件发布。

## 用例

实体文件 3→2 与对应行删除；当前／备用队列保护及回退；600 次加速进度保存；外部保留引用释放；失败扫描回滚；20 轮替换扫描；人为保持读取事务期间写入 2500 条大记录，测量 WAL 峰值、写入耗时，以及读取释放后日志的复用。

600 次保存是加速调用，不代表已经完成 20 分钟真实定时或实车长稳测试。保留引用用例向数据库提供受控 ID 集合，不等同于重新完成一次真实离线下载。WAL 测量区分“读事务仍占用”与“释放后继续写入”，不将回收目标当成运行中硬上限。
