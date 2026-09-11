# OpenList 夸克播放路径调研

核对版本：`f18b4acc76f231dd425acf4e587446b0332f68c4`。调研日期：2026-09-11。

## 结论

OpenList 能参考，但没有在所核对的三个夸克驱动中找到独立的 FLAC 音频流接口。其 Cookie 与 TV 驱动仅针对视频选择转码播放，音频走文件下载地址；获取地址后按需读取并播放，不要求先完整保存文件。

| 驱动 | 认证 | 音频读取 | 视频转码 |
| --- | --- | --- | --- |
| quark_uc | 网页 Cookie | `/file/download` | 可选 `/file/v2/play/project`，仅 category=1 的视频 |
| quark_open | 应用 ID、签名与 Access/Refresh Token | `/open/v1/file/get_download_url` | 此驱动的 Link 中没有转码分支 |
| quark_uc_tv | 独立 TV 扫码与设备绑定 Token | `/file?method=download` | 可选 `/file?method=streaming`，仅 category=1 的视频 |

代码中出现 `audio_info` 响应字段，不代表 TV 驱动已实现音乐 FLAC 的独立播放；实际转码取址循环读取的是 `video_info`。

## 当前应用对照

Cruise Tune 当前的扫码授权来源于之前验证的 CLI/Agent 授权流程。OpenList QuarkOpen 的驱动名称并不能证明其 AppID、授权范围和服务端权益与本应用相同；它要求提供对应的 AppID、SignKey 与 RefreshToken。

针对模拟器中同一个 54,123,966 字节的已授权文件，保留当前应用身份、Token、请求签名和文件标识，只对照以下公开请求格式：

- User-Agent 使用 OpenList 的 `go-resty/3.0.0-beta.1 (https://resty.dev)`。
- Accept 使用 `application/json, text/plain, */*`。
- 下载地址请求省略本应用附加的 `platform`、`device_id` 查询参数，保留 `access_token` 与 `req_id`。
- 仍直接向夸克 Token 接口发请求，不经过第三方续期服务。

结果：HTTP 400、errno 23018、`download file size limit[52428800]`。音频字节未能读取。这证明上述格式调整不能解除当前授权对该文件的大小限制，不证明其他应用身份、TV 授权或 Cookie 路径也存在相同限制。

这是对请求格式的局部对照，不是部署完整 OpenList 服务的端到端测试。模拟器 Token、曲库、队列及播放器生产代码未切换；临时诊断组件已卸载。

## 可行的后续验证

如果继续坚持 Token 登录，下一条值得独立验证的是 TV 授权：使用它自己的扫码流程和设备绑定令牌，测试超过 50 MiB 的 FLAC 的下载地址、HTTP Range、首段解码与跳转。该流程需要用户扫码，当前 CLI/Agent Token 不能被假定为可直接复用。尚未执行 TV 授权，不能宣称此方案已经解决限制。

如果选择验证现有网页登录功能，则应使用本人新的 Cookie，对照 PC 下载接口。该方式是另一条认证路径，不是把 Cookie 作为当前 Token 的隐式备用。

不宜为了尝试 `/streaming` 而把 FLAC 当作视频或改扩展名；源码没有证明这样可用，也不能据此保证原始无损音质。

OpenList 文档说明这些夸克驱动来自历史接口，项目组不主动维护。新接入需要保留实际验证结果，不能仅凭项目名称或 UI“在线播放”字样认定大文件一定可用。

## 依据

- [Cookie 驱动 Link 分流](https://github.com/OpenListTeam/OpenList/blob/f18b4acc76f231dd425acf4e587446b0332f68c4/drivers/quark_uc/driver.go#L63-L71)
- [Cookie 下载与视频转码取址](https://github.com/OpenListTeam/OpenList/blob/f18b4acc76f231dd425acf4e587446b0332f68c4/drivers/quark_uc/util.go#L111-L168)
- [QuarkOpen 取址](https://github.com/OpenListTeam/OpenList/blob/f18b4acc76f231dd425acf4e587446b0332f68c4/drivers/quark_open/driver.go#L69-L90)
- [QuarkOpen 请求头与参数](https://github.com/OpenListTeam/OpenList/blob/f18b4acc76f231dd425acf4e587446b0332f68c4/drivers/quark_open/util.go#L27-L85)
- [QuarkOpen 配置](https://github.com/OpenListTeam/OpenList/blob/f18b4acc76f231dd425acf4e587446b0332f68c4/drivers/quark_open/meta.go#L8-L18)
- [TV 分流](https://github.com/OpenListTeam/OpenList/blob/f18b4acc76f231dd425acf4e587446b0332f68c4/drivers/quark_uc_tv/driver.go#L137-L145)
- [TV 下载与转码取址](https://github.com/OpenListTeam/OpenList/blob/f18b4acc76f231dd425acf4e587446b0332f68c4/drivers/quark_uc_tv/util.go#L225-L279)
- [TV 扫码与令牌流程](https://github.com/OpenListTeam/OpenList/blob/f18b4acc76f231dd425acf4e587446b0332f68c4/drivers/quark_uc_tv/util.go#L100-L210)
- [OpenList 夸克驱动说明](https://doc.oplist.org/guide/drivers/quark)

## 后续网页补测

独立网页登录实测表明：同一目标候选 FLAC 的视频播放接口返回 HTTP 400／14018，没有媒体地址；使用 Cookie PC 下载接口及 PC 客户端 User-Agent 后成功取得地址并读取 64 KiB FLAC 数据（HTTP 206）。应用 0.5.6 据此采用文件地址的按需读取方案。该结果不证明视频转码支持 FLAC，也不适用于此前的 CLI/Agent Token。详情见 [夸克接入文档](quark-integration.md#网页登录补测与-056-接入)。
