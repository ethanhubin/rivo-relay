# Sites VPS 中转验证（2026-11-10）

## 结果

VPS 已安装 Mihomo v1.19.33，官方压缩包 SHA-256 为 `386508bfb1c72c43b6a0eda73708ecdce82546581b0ebcb16366c53757c622b6`，与 GitHub 发布资产摘要一致。复用旁路由 OpenClash 的有效订阅，已验证主动刷新返回 204；服务已设置开机启动，代理及管理端口均仅监听 loopback。

网关 Sites 隧道已部署。缺失或无效设备令牌返回 401；有效临时设备令牌成功建立 WebSocket；完成验证后已撤销临时设备，复测返回 401。未修改桌面 Codex 原生运行时。VPS 根分区实测 40G、使用 5.1G、剩余 35G（13%），未清理其他服务文件。

## 代码与模拟器

- 网关及新隧道相关 31 项测试通过；补充组播拒绝后，4 项隧道测试再次通过。覆盖鉴权前拒绝、公共目标解析与私网/组播阻断、SOCKS 目标 IP 固定、双向 WebSocket 字节传输及凭据不进入网站流量。
- Android `SitesWebProxyTest` 通过，`assembleDebug` 和 `assembleDebugAndroidTest` 成功；没有运行 lint。
- Android 36 模拟器、WebView 155：系统 `http_proxy` 为 null；真实 `GH · 1–80级内容与奖励轴` Sites 页面在关闭 WebView 缓存后成功加载，计数 858638 字节经过 VPS 隧道，页面截图已人工检查。测试 `SitesRelayLiveTest` 通过，截图位于忽略目录 `workspace/sites-relay-verification/`。
- 首轮实时测试错误地用顶层 body 文本长度判断 iframe 页面，随后一轮命中了 WebView 缓存；这两轮未计为通过。最终测试检查 iframe 加载并禁用缓存，同时断言真实中转字节数。
- VPS curl 默认 TLS 握手经所选代理失败，限制 TLS 1.2 可成功；Mac TLS 1.3 和 Android WebView 经同一代理成功。未修改或弱化网站 TLS 校验。

## 交付边界

未进行真机验证；尚未在用户手机安装。测试 APK 位于 `client/android/app/build/outputs/apk/debug/app-debug.apk`，未递增发布版本、未发布更新清单。该需求保存在独立 `codex/sites-relay` 工作区，原工作区的其他未提交改动保持原状。

Sites 需要含 VPS 路由及证书指纹的配对信息；仅直连旧配对会提示重新扫码。只支持公网 IPv4 的 HTTPS 443。WebView 进程级代理覆盖及 5 分钟隧道期限见 [VPS 部署说明](vps-gateway.md)。

## PR 审查补充

审查修复了 CONNECT 握手后沿用 30 秒读超时、导致仅下载中的长响应被截断的问题。握手仍限时，建立后由网关的 5 分钟上限约束连接；新增真实 TLS/WebSocket 回归在浏览器 31 秒不发送数据后验证仍可接收。该修复后的 Android 单元测试及 APK 构建结果见本次 PR；此前的真实 Sites 模拟器验证针对修复前版本，本轮未重跑真机或模拟器。
