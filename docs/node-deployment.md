# 在其他电脑部署节点

支持 VPS 自动更新的新部署及现有节点迁移见[统一更新](central-updates.md)。下方描述原有手工启动方式；迁移到代理后不要同时启动这些旧服务。

Windows、macOS 和 Linux 使用同一份 Python 服务端、VPS 连接器和 v2 配对协议。只有后台进程管理方式不同。每台机器保留自己的 Codex 登录、workspace、节点身份和独立 VPS 接入凭据；不要复制其他机器的数据库或 `.codex` 登录文件。

## 准备

- 使用包含本指南的 0.2.56 或更新源码。Android 安装当前发布版。
- 安装 Python 3.11+。Windows 已验证 Python 3.12.10、官方 Codex CLI 0.167.0。
- 在实际运行节点的用户下安装、登录官方 Codex，并确认 `codex --version`、`codex login status` 正常。Windows 后台执行使用实际 `codex.exe`，不要传 npm 的 `codex.cmd`。
- 由 VPS 管理员为每台电脑签发独立凭据，安全交给该电脑用户。不要把凭据放进 Git、截图或发布说明。

管理员在现有 VPS 执行：

```sh
cd /opt/rivo-relay
sudo -u rivo-gateway .venv/bin/python -m rivo_relay.gateway issue-key \
  --workspace /var/lib/rivo-gateway --ip 134.175.156.219 --port 80
```

从 `/var/lib/rivo-gateway/access-key.json` 安全读取凭证，并确认以 `rivo2.` 开头；若不是，先更新 VPS 签发程序，不能继续分发旧格式。此命令不撤销已有节点。凭证已包含地址与端口，80 端口运行 TLS，保留证书指纹验证。

## Windows：无窗口后台服务

以下在源码根目录执行。使用节点用户的管理员 PowerShell 安装计划任务；后台任务以该用户的 Limited/S4U 身份运行，不使用交互桌面。先准备官方 CLI 的实际路径，例如 npm 安装位置：

```powershell
$codex = "$env:APPDATA\npm\node_modules\@openai\codex\node_modules\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\bin\codex.exe"
Test-Path $codex
# 必须返回 True；其他安装方式请使用实际路径。
.\scripts\start-node.ps1 -CodexExecutable $codex
.\.venv\Scripts\python.exe -m pip install -e './server[gateway]'
```

默认创建 `Rivo Relay` 计划任务、`workspace\windows-node`。服务只监听 loopback，不需要开放局域网端口。若有已验证的 Tailscale Serve 入口，首次配置时把下方 `--mode relay` 换成 `--mode auto --direct-url https://你的节点.ts.net:端口`；没有 Tailscale 直接使用 VPS 即可。

```powershell
.\.venv\Scripts\python.exe -m rivo_relay.gateway node --workspace workspace\windows-node --attach --mode relay --pair-only
```

按提示粘贴该电脑的独立接入凭据。这一步保存 `gateway-client.json`，暂时生成的二维码要等后台连接器启动后重新生成。不要将这里的初始化终端当作常驻服务。

安装后台连接器（复用同一个无窗口包装器）：

```powershell
$root = (Get-Location).Path
$dir = Join-Path $root 'workspace\windows-gateway'
New-Item -ItemType Directory -Force $dir | Out-Null
@{repository=$root; module='rivo_relay.gateway'; arguments=@('node','--workspace',
  (Join-Path $root 'workspace\windows-node'),'--attach','--no-pair')} |
  ConvertTo-Json -Depth 5 | Set-Content "$dir\background.json" -Encoding UTF8
$action = New-ScheduledTaskAction -Execute "$root\.venv\Scripts\pythonw.exe" `
  -Argument "`"$root\scripts\windows-node-background.py`" `"$dir\background.json`"" -WorkingDirectory $root
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) -LogonType S4U -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -Hidden -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) `
  -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew `
  -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName 'Rivo Relay Gateway' -Action $action -Principal $principal `
  -Settings $settings -Trigger (New-ScheduledTaskTrigger -AtStartup)
Start-ScheduledTask -TaskName 'Rivo Relay Gateway'
Get-Content workspace\windows-gateway\node.log -Tail 10
```

看到 `gateway_connected` 后生成正式二维码：

```powershell
.\.venv\Scripts\python.exe -m rivo_relay.gateway node --workspace workspace\windows-node --attach --pair-only
```

图片为 `workspace\windows-node\artifacts\pairing.png`。后续重生成仍用此命令，**不要改用旧版 `rivo_relay pair`**，后者不包含 VPS 路由及证书指纹。

这两项任务分别管理 Relay 和连接器；共用业务代码，没有 Windows 专用功能分支。运行时无控制台窗口，日志分别在两个 workspace 子目录的 `node.log`。任务已存在时先检查，不要盲目覆盖；服务升级只在节点空闲时安排。

## macOS / Linux

已有确认可用的原生 socket 时，沿用原 Relay 及 workspace，使用连接器 `--attach`。新节点也可明确使用官方 CLI stdio；这不会自动取得 Codex Desktop UI 的共享运行时。

```sh
# 首次交互配置，输入本机独立接入凭据。
./scripts/start-node.sh --mode relay --codex-runtime stdio --codex-executable /实际绝对路径/codex
```

已有 Tailscale HTTPS 入口时改用 `--mode auto --direct-url https://你的节点.ts.net:端口`。默认 workspace 是 `workspace/local-node`。首次运行确认连接后 Ctrl+C，随后把同一命令交给系统后台管理器，并增加 `--no-pair`。已有 Relay 时使用其 workspace 和 `--attach`，不要重复监听端口。

macOS LaunchAgent 使用以下配置字段（路径均替换为绝对路径，不能用 `~`）：

- `Label`: 自定义唯一名称，如 `local.rivo-relay.node`。
- `ProgramArguments`: `[/源码/.venv/bin/python, -m, rivo_relay.gateway, node, --workspace, /源码/workspace/local-node, --no-pair]`。
- `WorkingDirectory`: 源码绝对路径；`RunAtLoad` 和 `KeepAlive`: `true`。
- `StandardOutPath` / `StandardErrorPath`: 已创建且当前用户可写的日志路径。

保存为 `~/Library/LaunchAgents/local.rivo-relay.node.plist`，用 `plutil -lint` 检查 plist，再执行 `launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/local.rivo-relay.node.plist`。这是登录用户服务；用户未登录时运行不在此部署范围内。首次保存的 runtime、入口和密钥配置会被复用。

Linux 可使用用户 systemd 服务，`ExecStart` 使用上述绝对路径参数，设置 `WorkingDirectory`、`Restart=on-failure`、`RestartSec=5`，安装到 `~/.config/systemd/user/rivo-relay.service` 后执行 `systemctl --user daemon-reload`、`systemctl --user enable --now rivo-relay`。需要注销后保持运行时，由管理员配置该用户的 linger。不要以 root 运行用户的 Codex。

后台就绪后生成二维码：

```sh
.venv/bin/python -m rivo_relay.gateway node --workspace workspace/local-node --attach --pair-only
```

## 手机验收与维护

1. 扫描当前机器生成的 v2 二维码，5 分钟内有效、只能使用一次。双通道共用一个码和节点身份；没有 Tailscale 时自动走 VPS。普通节点不要加 `--update-source`，避免覆盖主节点的 App 更新源。
2. 查看项目，发起小型实际任务，检查结果和文件读取。Windows 完全访问编码已实机通过；同一主机 Session 0 的受限 workspace-write 沙箱仍存在已记录的启动失败。按实际需求显式选择会话权限，不自动放宽其他会话或全局配置。
3. Windows 检查 `Get-Process pythonw | Select-Object Id,SessionId,MainWindowHandle`：本部署预期 Session 0、窗口句柄 0。计划任务启动和故障恢复已验证，整机重启后自启动需在新机器单独验收。
4. `connection closed` 先检查二维码是否含 VPS 路由、连接器是否记录 `gateway_connected`。`node_offline` 检查电脑服务和连接器；配对码过期则重新生成。浏览器直接访问自签名 VPS 不等价于 App 的指纹校验测试，不要关闭 TLS 校验。
5. 备份同一机器的 workspace（包含数据库及私有配置）。升级保留 workspace 和接入凭据，停止本机对应服务、更新源码及虚拟环境依赖，再启动并复验；不要删除原生 Codex 数据或顺手重启其他节点。

未迁移的旧节点仍需单独复制源码升级；迁移到[统一更新代理](central-updates.md)后，日常发布会由 VPS 分发节点程序和 APK。当前发布约定不推送 GitHub；首次迁移将本版源码安全复制到目标机器，再按统一更新指南安装。源码中不应包含 workspace、凭据或配对图。

验证依据：[Windows 实机记录](validation-windows-2026-11-08.md)、[VPS 通道说明](vps-gateway.md)。2026-11-08 用户已确认 Windows 双通道二维码手机配对成功；不把此结果扩大为其他机器或全部功能已验收。
