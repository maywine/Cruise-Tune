# FLAC 软件解码

本模块使用 Media3 官方 FLAC 扩展把 `.flac` 解码为 PCM，再交给原有音频输出。`DefaultExtractorsFactory` 在 native library 可用时自动选择此扩展，不再把 FLAC 帧交给设备的 `MediaCodec`。缓存仍保存原始 FLAC 字节，16／24 位样本在解码阶段保持原位深；最终输出格式由 Media3 的 AudioSink 和设备能力决定。

## 来源与构建

- Java 和 JNI 来源：[Media3 1.11.0 decoder_flac](https://github.com/androidx/media/tree/1.11.0/libraries/decoder_flac)，原文件未修改，摘要见 `upstream-sha256.json`，许可见 `LICENSE`。`FlacDecodeErrors.java` 是本项目添加的错误类型适配。
- libFLAC 固定为 [1.5.0](https://github.com/xiph/flac/tree/1.5.0)，CMake 下载并校验固定 SHA-256；许可见 `LICENSE.libFLAC`，亦包含在应用的第三方许可页面。
- Android 构建固定 NDK 27.2.12479018、CMake 3.22.1，包含 armeabi-v7a／arm64-v8a，静态链接 C++ runtime；arm64-v8a 启用 16 KiB page size 支持。
- 第一次 native 构建需要下载已固定版本的源码。无需提交生成的 `.so`、AAR 或构建目录。

## 本机验证

在项目根目录、已配置 JDK／Android SDK 的 Linux 环境执行：

```sh
cmake -S android-app/decoder-flac/src/main/jni \
  -B android-app/decoder-flac/build/host -DCMAKE_BUILD_TYPE=Release -DWITH_ASM=OFF
cmake --build android-app/decoder-flac/build/host --target flacJNI -j 2
cd android-app
./gradlew :app:testDebugUnitTest -PnativeFlacLibraryDir=../decoder-flac/build/host
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

第一轮测试必须实际加载 JNI。它在 API 28 Robolectric 环境中验证原生软件解码、16／24 位 PCM 一致性、标签、无 SeekTable 的跳转、缓存播放及错误分类；只替换 Android 日志接口，不模拟解码。第二轮执行常规应用回归。两轮分开运行，避免 Robolectric 不同 sandbox 的 native library classloader 冲突。

可在第一轮添加 `-PflacSamplesDir=/path/to/local/samples`，逐首读取本机 FLAC，校验完整解码后的样本数量和内嵌 PCM MD5。音频不上传、不进入仓库。该选项要求文件包含有效 MD5。

本机原生解码和模拟 AudioTrack 写入仍不能代替车机厂商音频驱动及扬声器验证。
