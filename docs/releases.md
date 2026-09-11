# 版本与自动发布

## 版本来源

`android-app/version.properties` 是应用版本的唯一构建来源：

```properties
VERSION_NAME=0.5.2
VERSION_CODE=11
```

Gradle 直接读取这两个值。`VERSION_NAME` 支持 `X.Y.Z`，或 `X.Y.Z-alpha.N`、`X.Y.Z-beta.N`、`X.Y.Z-rc.N`（N 从 1 开始）。`VERSION_CODE` 是正整数，每次正式版或预发布版都必须高于已经存在的所有版本标签。

仓库首次配置保持已有版本号；增加发布流程本身不会自动创建新 tag 或发布一个版本。

## 一次性配置 Secrets

在仓库 **Settings → Secrets and variables → Actions → Repository secrets** 配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 用于更新现有应用的同一签名文件，Base64 编码 |
| `ANDROID_KEYSTORE_PASSWORD` | 签名文件密码 |
| `ANDROID_KEY_ALIAS` | 签名别名 |
| `ANDROID_KEY_PASSWORD` | 该别名的密钥密码 |
| `QUARK_CLIENT_CONFIG_JSON` | 只含非空 `clientId`、`signKey` 的客户端 JSON |

[本仓库 Secrets 设置](https://github.com/maywine/Cruise-Tune/settings/secrets/actions)。GitHub 的 `GITHUB_TOKEN` 由 Actions 自动提供，不需要额外保存发布 PAT。工作流只给发布 job `contents: write` 权限，构建 job 为只读。

当前 `android-app/release-signing.properties` 记录已有测试包的**公开证书指纹**，用于阻止误换签名导致覆盖安装失败。流程构建非 debuggable 的 release 变体，但继续使用同一证书。如果确实改用新证书，需要明确处理与旧安装的签名冲突后再更新指纹；不能只改这个文件来掩盖错误配置。

签名私钥不会进入 Git 或 Release 附件。夸克客户端参数会编入公开 APK，可被提取，因此只填写允许随客户端分发的客户端参数；不得填写用户访问令牌、刷新令牌、Cookie、账号数据，或必须留在服务端的密钥。原有 APK 已采用客户端参数随包模式，本流程不将用户会话编入包。缺少任一 Secret 时流程提前失败，不发布缺失扫码配置的成品。

已登录 GitHub CLI 时，可以通过标准输入传输文件，避免把真实值放进命令参数或历史：

```sh
base64 < "$KEYSTORE_PATH" | gh secret set ANDROID_KEYSTORE_BASE64 --repo maywine/Cruise-Tune

gh secret set QUARK_CLIENT_CONFIG_JSON --repo maywine/Cruise-Tune \
  < android-app/app/src/main/assets/quark_cli_client.json

# 以下命令交互输入对应值。
gh secret set ANDROID_KEYSTORE_PASSWORD --repo maywine/Cruise-Tune
gh secret set ANDROID_KEY_ALIAS --repo maywine/Cruise-Tune
gh secret set ANDROID_KEY_PASSWORD --repo maywine/Cruise-Tune
```

Secret 设置需要 GitHub API 授权，SSH push 权限不能替代它。配置入口与保密机制参考 [GitHub Secrets 文档](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。签名与覆盖更新的关系见 [Android 应用签名](https://developer.android.com/studio/publish/app-signing)。

## 发布步骤

以未来的 `0.5.3` 为例（如果已经有更高内部版本号，应继续递增）：

1. 将 `VERSION_NAME` 改为 `0.5.3`、`VERSION_CODE` 改为 `12`，同时提交该版本的代码。
2. 本地检查：`python3 tools/release.py validate --tag v0.5.3`。
3. 提交并推送代码，再创建、推送对应标签：

```sh
git add android-app/version.properties
git commit -m "Prepare version 0.5.3"
git push origin main
git tag -a v0.5.3 -m "Cruise Tune 0.5.3"
git push origin v0.5.3
```

只在本机创建 tag 不会触发 GitHub。工作流监听新 `v*` 标签的 push；普通分支提交、标签删除和强制移动不会发布。标签必须精确匹配代码版本，标签指向的提交必须与构建一致。触发方式参考 [GitHub 标签过滤](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)。

预发布示例：`VERSION_NAME=0.5.3-rc.1`、标签 `v0.5.3-rc.1`，会自动标为 GitHub prerelease。其后正式 `0.5.3` 仍须使用更大的 `VERSION_CODE`。

## 流程与产物

`.github/workflows/release.yml` 执行：

1. 核对标签、代码版本、历史标签内部版本号和所需 Secrets。
2. 使用固定提交版本的 Actions、JDK 17、Android Platform 36／Build Tools 35.0.0。
3. 无凭证运行发布工具测试、连接服务测试、Android 单元测试及 release Lint。
4. 临时写入签名文件和客户端配置，构建 `assembleRelease`。
5. 检查包名、版本、非 debuggable 状态、签名证书及客户端配置结构；生成 SHA-256。
6. 清理临时签名和配置文件，仅传递成品 APK、校验文件、版本清单和说明。
7. 独立发布 job 再核对提交与文件摘要；上传附件完成后才将 Release 草稿公开。

附件为 `CruiseTune-<版本>.apk`、`SHA256SUMS`、`release-manifest.json`。GitHub 自动提供标签对应源码归档。不会上传 authCheck 包、签名文件、原始日志或测试账号数据。

每版可在 `docs/release-notes/<VERSION_NAME>.md` 编写更新内容；发布脚本将对应版本的说明与包版本、源码提交一起写入 Release。没有对应文件时使用通用功能说明。

同一工作流的同版本、同提交草稿允许失败后重跑完成上传。已经公开的 Release 或其他草稿不会被覆盖，也不会自动移动标签。配置或短暂网络问题修复后可重跑；代码错误应提交新版本并创建新标签。草稿及上传行为参考 [GitHub CLI release create](https://cli.github.com/manual/gh_release_create)。

## 本地验证

```sh
python3 -m unittest discover -s tools/tests -v
python3 -m unittest discover -s open-service -v
cd android-app
./gradlew :app:testDebugUnitTest :app:lintRelease
```

本地签名构建可通过 `CRUISE_KEYSTORE_PATH`、`CRUISE_STORE_PASSWORD`、`CRUISE_KEY_ALIAS`、`CRUISE_KEY_PASSWORD` 传入签名配置。未设置时本地 release 构建为未签名产物；自动发布流程必须经过完整签名检查，不会发布它。

## 实施验证记录

2026-09-10：工作流通过 actionlint 与 shell 语法检查；14 项发布工具测试通过，覆盖版本与标签不一致、内部版本号未递增、密钥缺失、误换证书、成品篡改、草稿恢复及拒绝覆盖已公开／非本流程附件。隔离副本完成 81 项 Android 测试、release Lint、使用既有证书的非 debuggable 签名构建，以及 APK／版本清单／校验文件复核。临时签名文件和客户端 JSON 已清理。

仓库所需的 5 项发布 Secrets 已配置，GitHub Actions 已启用。2026-09-10 推送 `v0.5.2` 后，首次云端流程全部通过，已正式发布 [v0.5.2](https://github.com/maywine/Cruise-Tune/releases/tag/v0.5.2)。[运行记录](https://github.com/maywine/Cruise-Tune/actions/runs/34463506510)。下载后的 APK 已再次核对标签提交、版本号、SHA-256 和既有签名证书，确认为非 debuggable 构建。私钥及客户端参数通过 Secrets 保存，没有进入源码提交。
