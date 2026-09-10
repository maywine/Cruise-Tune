# Quark Open 配套连接服务

> 早期连接服务原型，仅作为研发参考。当前播放器没有该服务的配置导入或回调入口；当前直接扫码流程见 [夸克接入说明](../docs/quark-integration.md)，不需要部署此服务。

这是 Cruise Tune 0.2.0 的配套授权与签名服务。它使用播放器专用的开放平台客户端配置，保存 clientSecret 和 signKey；Android 端只保存加密的连接凭证、用户访问令牌和刷新令牌。

当前公开 Skill 说明列出的运行环境是桌面 Agent。独立 Android 应用的客户端准入、回调地址及刷新路由仍需由平台确认。本仓库没有内置 Skill 的默认客户端密钥，也没有把 Agent 身份伪装为播放器身份。

## 配置条件

准备一个由平台允许用于此 Android 播放器的客户端，并确认：

- clientId、clientSecret、signKey 与 deviceId 的实际值。
- 回调地址 `cruisetune://quark-open/callback` 可以使用。
- 目录列表、文件下载和用户信息的读取权限。
- 是否允许本实现采用的 `/agent/v1/oauth/access_token/rotate` 路由。只有确认后才将 `refreshRouteConfirmed` 设为 true。

`approvedForAndroid` 是部署者对已经确认准入的配置声明，不代表服务会自动取得或验证平台审批。未取得配置时，Android 端的原网页接入仍可使用。

复制 `server.example.json` 为 `server.local.json`，填入以上配置。`serviceToken` 使用独立的随机连接凭证，至少 24 个可打印 ASCII 字符。`externalServiceUrl` 是车机能访问的 HTTPS 服务地址。

不要把 `server.local.json` 复制到车机；它包含应用密钥。通过下方导出命令生成车机连接文件。

## 本地启动与 HTTPS

Python 3.9 及以上，无第三方 Python 依赖。

```sh
python3 service.py --config server.local.json
```

默认只监听 `127.0.0.1:8787`，可在其前面配置 HTTPS 反向代理。服务访问日志关闭，令牌和授权码不写入日志。

如果直接提供 HTTPS：

```sh
python3 service.py --config server.local.json \
  --host 0.0.0.0 --port 8787 \
  --tls-cert fullchain.pem --tls-key privkey.pem
```

车机必须信任该 HTTPS 证书。服务拒绝没有 TLS 的非回环地址监听，也不会跟随上游重定向。

## 导出车机配置

```sh
python3 service.py --config server.local.json \
  --export-connection connection.json
```

导出的 JSON 只有 `clientId`、`serviceUrl`、`serviceToken`，不包含 clientSecret 或 signKey。文件以仅当前用户可读写的权限创建；它包含连接凭证，应妥善保管。

当前播放器没有该配置导入入口；导出文件仅用于后续原型联调，不是启用当前直接扫码登录的步骤。

## 路由与边界

所有路由均要求 `Authorization: Bearer <serviceToken>`，响应带 `Cache-Control: no-store`。

| 路由 | 职责 |
| --- | --- |
| POST /v1/authorize | 生成固定客户端、固定回调及给定 state 的夸克授权地址 |
| POST /v1/exchange | 交换授权码，并向夸克查询真实 user_id |
| POST /v1/rotate | 在确认该路由可用时，轮转同一设备的令牌对 |
| POST /v1/sign | 仅为读取目录、获取下载地址、读取用户信息生成签名 |

服务不提供文件上传、删除、移动或重命名签名。它也不代替播放器下载音乐，音频仍由车机从网盘读取。服务端不持久化用户令牌；Android 将新的访问／刷新令牌作为一份加密记录提交后再使用。

接口字段按所审阅的 Skill 1.0.18 实现。专用客户端如果要求其他回调字段、PKCE 或不同刷新机制，应根据平台文档适配后启用，不能由 Skill 的桌面运行能力推导为 Android 授权已经通过。

## 验证

```sh
python3 -m unittest discover -s . -v
```

测试使用假的上游响应和本机回环 HTTP，覆盖准入开关、只读签名白名单、授权绑定、用户身份、令牌有效期、设备绑定和连接鉴权。没有使用真实用户令牌，也没有向生产开放接口发送客户端密钥。
