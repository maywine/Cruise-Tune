# 版本与自动发布

## 版本来源

`android-app/version.properties` 是应用版本的唯一构建来源：

```properties
VERSION_NAME=0.5.2
VERSION_CODE=11
```

Gradle 直接读取这两个值。`VERSION_NAME` 支持 `X.Y.Z`，或 `X.Y.Z-alpha.N`、`X.Y.Z-beta.N`、`X.Y.Z-rc.N`（N 从 1 开始）。`VERSION_CODE` 是正整数，每次正式版或预发布版都必须高于已经存在的所有版本标签。

## 发布说明写作规则

`docs/release-notes/<VERSION_NAME>.md` 是对应版本发布说明的来源，`CHANGELOG.md` 保留简要更新记录。

- 只写该版本新增、调整和修复的内容，说明用户能感知到的行为变化。优先使用简短条目，避免逐文件介绍实现。
- 不写测试数量、通过结果、构建和安装记录、模拟器操作过程、实车验收免责声明或本轮工作进度。
- 不追加通用下载提示、签名覆盖安装要求、客户端配置声明、源码提交号等模板段落。配置与构建要求写在相应开发文档中。
- 只有影响用户选择或操作的兼容性变化、迁移步骤才随具体改动说明；不重复列举没有改变的功能。
- 缺少对应版本说明或内容为空时，发布打包失败；不使用通用功能列表代替版本变更。

测试方法维护在 [测试指南](../android-app/TESTING.md)。排障结论按主题合入架构、功能或测试说明；一次性调查报告与日常执行日志留在构建产物或本机忽略目录。文档提交标准见 [文档维护约定](publishing.md#文档维护)。

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

1. 将 `VERSION_NAME` 改为 `0.5.3`、`VERSION_CODE` 改为 `12`，编写 `docs/release-notes/0.5.3.md` 并更新 `CHANGELOG.md`。
2. 本地检查：`python3 tools/release.py validate --tag v0.5.3`。
3. 提交并推送代码，再创建、推送对应标签：

```sh
git add android-app/version.properties docs/release-notes/0.5.3.md CHANGELOG.md
git commit -s -m "Prepare version 0.5.3"
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
3. 无凭证运行发布工具测试、Android 单元测试及 release Lint。
4. 临时写入签名文件和客户端配置，构建 `assembleRelease`。
5. 检查包名、版本、非 debuggable 状态、签名证书及客户端配置结构；生成 SHA-256。
6. 清理临时签名和配置文件，仅传递成品 APK、校验文件、版本清单和说明。
7. 独立发布 job 再核对提交与文件摘要；上传附件完成后才将 Release 草稿公开。

附件为 `CruiseTune-<版本>.apk`、`SHA256SUMS`、`release-manifest.json`。GitHub 自动提供标签对应源码归档。不会上传 authCheck 包、签名文件、原始日志或测试账号数据。

发布脚本使用对应版本的说明生成 Release 正文，只添加版本标题和用于识别发布归属的隐藏标记。构建版本、源码提交、签名与摘要保存在 `release-manifest.json`，不重复展示在正文中。

同一工作流的同版本、同提交草稿允许失败后重跑完成上传。已经公开的 Release 或其他草稿不会被覆盖，也不会自动移动标签。配置或短暂网络问题修复后可重跑；代码错误应提交新版本并创建新标签。草稿及上传行为参考 [GitHub CLI release create](https://cli.github.com/manual/gh_release_create)。

## 更正已发布的说明

修改 `docs/release-notes/` 并推送到 `main` 后，`release-notes.yml` 将正文同步到已有 Release。也可手动运行该工作流。它核对标签与隐藏的发布归属标记，只修改正文，不构建应用、不替换附件、不移动标签。

有 GitHub CLI 授权的本机可先预览，再执行正文更新：

```sh
python3 tools/update-release-notes.py --repo maywine/Cruise-Tune
python3 tools/update-release-notes.py --repo maywine/Cruise-Tune --write
```

已发布 APK 的身份仍以原标签及 `release-manifest.json` 为准。尚未发布或没有对应说明文件的版本不会创建 Release。

## 本地验证

```sh
python3 -m unittest discover -s tools/tests -v
cd android-app
./gradlew :app:testDebugUnitTest :app:lintRelease
```

本地签名构建可通过 `CRUISE_KEYSTORE_PATH`、`CRUISE_STORE_PASSWORD`、`CRUISE_KEY_ALIAS`、`CRUISE_KEY_PASSWORD` 传入签名配置。未设置时本地 release 构建为未签名产物；自动发布流程必须经过完整签名检查，不会发布它。
