# VPS 中转与 Tailscale 双通道

同一个桌面 Relay 可以同时接受 Tailscale 直连和 VPS 中转。Android 按节点 ID 保存一个节点，路径切换保留连接令牌、设备 ID、草稿和缓存。旧版 v1 二维码及原直连启动命令保持支持。

完整的新机器安装、后台启动和验收步骤见[跨平台节点部署指南](node-deployment.md)。

## 桌面使用

需要 Python 3.11+，本机已有可用的 Codex 原生运行时。macOS / Linux：

```sh
git clone <仓库地址> Rivo-Relay
cd Rivo-Relay
./scripts/start-node.sh
```

首次只需粘贴一条 `rivo2.…` 节点接入凭证，已包含 VPS IP、端口、独立节点密钥和证书指纹，不再分别填写 IP 与密钥。脚本建立虚拟环境并安装依赖，保存配置、启动 Relay、连接 VPS，生成 `workspace/local-node/artifacts/pairing.png`。终端支持时同时显示二维码。手机使用支持 v2 配对的开发版 App 扫描；二维码有效期为 5 分钟，只能兑换一次。

脚本会复用已有 Tailscale Serve HTTPS 映射，不自动修改 Tailscale 配置。没有映射时仍可仅通过 VPS 连接。已有明确入口可用 `--direct-url http://100.124.150.1:18767` 或目标节点 HTTPS 地址指定。监听本地端口默认 18765；与已有服务共享 workspace 时校验健康接口的节点 ID 后自动接入，不再启动第二个 Relay。

- `--mode direct`：仅直连，需要可用直连地址。
- `--mode relay`：仅 VPS。
- `--mode auto`：两条路径同时提供（默认）。
- `--configure`：重新粘贴节点接入凭证。
- `--workspace PATH --local-port PORT`：其他 workspace / 本地端口。
- `--pair-only`：为已运行节点生成新二维码，不建立第二条控制连接。
- `--update-source`：将此节点声明为 App 更新源。普通节点不覆盖手机已保存的更新源。
- `--no-pair`：后台运行时不生成配对码。

启动命令默认前台常驻；退出会关闭其创建的 Relay 子进程，接入已有 Relay 时只关闭连接器。后台保活应由 launchd/systemd 管理此命令；保存配置后不再需要输入。Windows 使用同一 `rivo_relay.gateway node` 命令，配合无窗口计划任务运行；平台差异仅限进程、信号及后台启动方式。

仅支持 `rivo2.…` 完整接入凭证，其中包含 VPS 地址、端口、独立密钥和证书指纹，节点安装只需粘贴一次，不再接受旧凭证或单独输入 IP。升级旧节点前，管理员须将其凭证迁移为 v2（保留原槽位、secret 与证书指纹）。凭证是封装而非加密，须像密码一样保管。

配置 `gateway-client.json` 包含密钥，权限为 0600；workspace 不进入 Git。每个桌面节点使用一份独立接入密钥。同一密钥同时连接第二个桌面时网关拒绝连接，不会替换在线节点。

## VPS 部署

在 VPS 上将仓库放入服务用户可访问的 `/opt/rivo-relay`，以 root 执行：

```sh
cd /opt/rivo-relay
./scripts/install-gateway-service.sh <公网IP>
```

安装器创建受限用户 `rivo-gateway`、systemd 服务和 `/var/lib/rivo-gateway` 私有状态目录。首次自动生成自签名 TLS 证书与一份包含地址的节点接入凭证。凭证在 `/var/lib/rivo-gateway/access-key.json`，由管理员安全复制给桌面用户。不要公开这个文件或将其放入日志。

默认入口为 `https://公网IP:80`，**80 上运行 TLS，不是明文 HTTP**。本部署的 443 已被 Merge Release 使用，80 已在云防火墙放通；因此采用这一端口。其他部署可通过安装脚本第二参数指定端口，签发凭证时使用相同的 `--port`，桌面无需再输入端口。IP 未指定端口时固定使用 80。需要在云防火墙放通所选 TCP 端口。

每增加一台桌面，签发独立密钥：

```sh
sudo -u rivo-gateway /opt/rivo-relay/.venv/bin/python -m rivo_relay.gateway issue-key \
  --workspace /var/lib/rivo-gateway --ip <公网IP> --port 80
```

该命令更新 `access-key.json` 的 `key` 字段为新的完整凭证，不撤销已有密钥；交互终端也会显示新密钥。证书和密钥库应一并备份。不要删除证书后重新生成；证书改变需要重新分发节点密钥及手机二维码。

撤销某节点：

```sh
sudo -u rivo-gateway /opt/rivo-relay/.venv/bin/python -m rivo_relay.gateway revoke-key \
  --workspace /var/lib/rivo-gateway --slot <节点接入槽位>
systemctl restart rivo-gateway
```

重启使已有长连接立即退出；其他节点自动重连。手机连接令牌的撤销仍使用桌面原有 `rivo-relay revoke` 命令。

## Android 行为

设置 → 节点连接提供自动、仅直连、仅 VPS。自动模式首先尝试直连，并持久保存最近成功路径；两条路径都不可用时显示错误。直连探测总超时 1.8 秒，单一路径/VPS 探测为 6 秒；短期复用探测结果，避免每次请求增加探测开销。

v2 二维码包含节点 ID、两条访问地址、网关证书 SHA-256 指纹及一次性配对码。二维码不包含桌面接入密钥。已配对手机重新扫描同节点 v2 码时，通过绑定配对码、节点身份及访问地址的双向 HMAC 校验，服务端在同一事务里复用未撤销的连接令牌和设备 ID；旧 Bearer 不发送到新扫码地址；旧授权已撤销时创建新授权。

GET 可以在另一条路径上恢复。POST、PUT、PATCH、DELETE 不在传输层重试；发送消息的未知结果继续交给既有 submissionId 核对流程。SSE 由现有订阅逻辑重连，携带事件游标。下载复用已有 Range/ETag 缓存和续传机制；上传中断不会自动重复提交。

更新源独立于活动会话节点，只有扫描带 `--update-source` 标记的二维码才更换。更新请求不携带节点令牌；清单和 APK 沿用大小与 SHA-256 校验。网关转发同一份桌面 `updates/` 文件，不维护第二套发布内容。普通开发不自动发布 APK 或覆盖 latest.json。

## 传输与信任

VPS 是可信中转端：手机到 VPS 为 HTTPS，桌面到 VPS 为固定证书校验的 WebSocket TLS。桌面仍通过 loopback HTTP 调用本地 Relay。当前不是手机到桌面的端到端加密，VPS 可以看到请求内容及手机 Bearer 令牌。

证书按完整 DER 的 SHA-256 固定，不是关闭证书验证。Android 对该网关校验固定证书和有效期；普通 HTTPS 直连继续使用系统信任。Python 连接器使用 aiohttp Fingerprint 固定校验。证书初始有效期 10 年，到期前需计划轮换及重新扫码。

控制通道只下发一次性请求票据，每个请求建立独立数据通道，按 64 KiB 分块并施加背压。单节点最多 48 个进行中请求，网关总计最多 256 个；单请求上传上限 1 GiB。数据连接绑定节点接入密钥和一次性票据，只能访问固定本地端口的 `/health` 与 `/v1/`，不是任意 TCP 代理。取消会关闭数据通道并取消上游请求。控制连接断开会关闭该节点的数据通道，连接器指数退避重连。

## 本机部署位置

- VPS：`134.175.156.219:80`，`rivo-gateway.service`，程序 `/opt/rivo-relay`，状态 `/var/lib/rivo-gateway`。
- 主节点 Relay：原 `local.rivo-relay` 服务及 `workspace/local-node`，使用已发布提交的 `workspace/deployments/<commit>/server` 快照。
- 主节点连接器：`~/Library/LaunchAgents/local.rivo-relay.gateway.plist`，连接器日志在 `workspace/local-node/logs/gateway*.log`。
- 原 Relay 启动配置与数据库部署前备份：`workspace/gateway-deployment/`。此目录包含私有文件，不进入 Git。

验证范围与限制见[本次验证记录](validation-vps-gateway.md)；支持代码、回归测试、模拟器验证和真机验证应分别报告。

### Windows 节点接入验证（2026-11-08）

Windows 节点已使用独立接入凭据连接现有 VPS。`Rivo Relay Gateway` 无窗口计划任务通过共用 `rivo_relay.gateway node --attach --no-pair` 接入既有 Relay，不重启原生运行时。配置位于 Windows workspace 的 `gateway-client.json`；后台包装配置及日志位于 `workspace/windows-gateway/`。使用同一命令加 `--pair-only` 生成包含直连、VPS 和证书指纹的 v2 二维码，不再使用仅直连的旧 `rivo_relay pair` 命令。

验证：真实 VPS 配对及授权节点读取成功，临时手机令牌已撤销；进程 Session 0、窗口句柄为 0。网关原有 17 项测试及新增双通道二维码回归测试通过。用户随后确认 Android 真机扫描双通道二维码配对成功。
