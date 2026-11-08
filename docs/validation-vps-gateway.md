# VPS 双通道开发验证（2026-11-08）

## 完成范围

- VPS `134.175.156.219` 已部署 `rivo-gateway.service`，TLS 入口 `https://134.175.156.219:80`。18768/TCP 实测被云防火墙拦截，改用已放通且空闲的 80/TCP；原 443 服务保留。
- 主节点保留 Tailscale HTTP 入口，并安装 `local.rivo-relay.gateway` 常驻连接器。主 Relay 加载开发快照，workspace / 节点 ID / 原连接数据库保持一致。
- 部署前备份主节点 SQLite 与 LaunchAgent；核对 Codex 原生 PID 前后均为 21622。过程中只重载 Relay，没有重启原生运行时。
- 中断曾发生在 Relay 的 bootout / bootstrap 之间；恢复执行时已重新 bootstrap 并核验健康。后续重载改用 SIGTERM 和现有 KeepAlive 拉起。

## 自动化验证

- 服务端：9 项网关测试、8 项配对测试、43 项 API 回归通过，共 60 项。
- 网关测试覆盖错误密钥、错误证书指纹、跨节点票据隔离、4 MiB 分块上传、下载与响应头、编码查询参数、SSE 与读取并发、空闲流取消、断线恢复、PUT 到达上游后断开不重放，以及仅直连配置的配对绑定。
- Android：7 项 NodeTransport、4 项 PairingParser、4 项 NodeEndpoints、2 项 NodeAppUpdates 测试通过，共 17 项；未受后续变更影响的既有检查结果复用。
- JVM 固定证书测试使用 `src/test/resources/gateway-test.p12`，密码 `test-only`，仅供测试，未打入应用。验证自签名证书的正确指纹成功、错误指纹失败。
- Debug APK 和 AndroidTest APK 构建通过。`git diff --check` 通过。未运行 lint 或无关全量测试。

## 公网与模拟器联调

使用 Android 16 / API 37 的 `medium_phone` 模拟器，运行显式提供私有 fixture 的 `GatewayConnectionTest`，1 项端到端测试通过，耗时 14.466 秒，包含：

1. v2 链接解析并兑换一次性配对码。
2. 仅 VPS 访问节点身份与待办 API。
3. 仅 Tailscale 直连读取同一节点身份。
4. 再次配对同节点，确认 deviceId、storageBase 保持一致，保存节点列表只有一个节点。
5. 将直连测试入口改为不可达地址，自动回退 VPS 成功。
6. 强制 VPS 更新源，下载已发布的 0.2.42 APK（18,987,564 字节）并通过大小与 SHA-256 校验。

测试使用真实公网 IP 和固定证书，没有通过 SSH 或 Tailscale 转发代替 VPS 连接。临时手机授权及诊断授权均撤销；测试下载文件已删除，专用模拟器已停止。

另经真实 VPS 中转读取 21 个项目，耗时 21.06 秒。初次模拟器验证曾因项目目录读取超时失败；同问题也在本机 loopback 读取复现，诊断定位到既有原生历史活动扫描。后续将配对/路径测试与项目目录耗时分开验证。此轮没有修改项目目录扫描算法，不宣称解决冷读取性能问题。

桌面实际执行 `scripts/start-node.sh --pair-only` 成功生成包含双地址的 v2 二维码，配对 JSON 权限为 0600。模拟器使用二维码承载的链接进入真实配对方法，未验证相机对实体二维码的识别。

## 开发安装包

- 路径：`workspace/artifacts/rivo-relay-vps-development.apk`。
- 版本仍为 0.2.42 / code 54，仅为开发交付，没有执行发布流程。
- 大小：20,199,197 字节。
- SHA-256：`2e249b2618ea3a1c7808d170f0e552fcbd51db82537269e2ae2516d932a545eb`。
- APK 签名验证通过，签名证书与当前更新源版本一致。

未修改 `latest.json`、未覆盖更新源 APK、未创建 Git tag / GitHub Release，未提交或推送 Git。

## 未覆盖范围

未进行实体手机测试、相机扫码测试、长期休眠/弱网耐久测试或真实 1 GiB 传输测试。当前桌面启动器支持 macOS / Linux，未实现 Windows 启动器。网关为可信 TLS 终止端，不提供手机到桌面的端到端加密。
