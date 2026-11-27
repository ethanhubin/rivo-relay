# 在其他电脑部署节点

支持 VPS 自动更新的新部署及现有节点迁移见[统一更新](central-updates.md)。下方描述原有手工启动方式；迁移到代理后不要同时启动这些旧服务。

Windows、macOS 和 Linux 使用同一份 Python 服务端、VPS 连接器和 v2 配对协议。只有后台进程管理方式不同。每台机器保留自己的 Codex 登录、workspace、节点身份和独立 VPS 接入凭据；不要复制其他机器的数据库或 `.codex` 登录文件。

## 准备

- 使用包含本指南的 0.2.56 或更新源码。Android 从 [VPS HTTPS 地址下载 APK，再扫节点配对二合一码](vps-gateway.md#手机配对二合一码)。
- 安装 Python 3.11+。Windows 已验证 Python 3.12.10、官方 Codex CLI 0.167.0。
- 在实际运行节点的用户下安装、登录官方 Codex，并确认 `codex --version`、`codex login status` 正常。Windows 后台执行使用实际 `codex.exe`，不要传 npm 的 `codex.cmd`。
- 由 VPS 管理员为每台电脑签发独立凭据，安全交给该电脑用户。不要把凭据放进 Git、截图或发布说明。
- 为节点准备受手机信任的直连 HTTPS 入口，并将其保存为环境变量 `RIVO_NODE_DIRECT_HTTPS`，供首次配置使用。VPS 中转使用独立节点凭据。手机扫码一次保存两条路径。

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

默认创建 `Rivo Relay` 计划任务、`workspace\windows-node`。服务只监听 loopback。首次配置时保存直连 HTTPS 地址与 VPS 中转凭据。

```powershell
.\.venv\Scripts\python.exe -m rivo_relay.gateway node --workspace workspace\windows-node --attach --mode auto --direct-url "$env:RIVO_NODE_DIRECT_HTTPS" --pair-only
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

看到 `gateway_connected` 后生成正式配对二合一码：

```powershell
.\.venv\Scripts\python.exe -m rivo_relay.gateway node --workspace workspace\windows-node --attach --pair-only
```

图片为 `workspace\windows-node\artifacts\pairing.png`。刷新时沿用此命令，并检查[两条路径及证书指纹](vps-gateway.md#手机配对二合一码)。

这两项任务分别管理 Relay 和连接器；共用业务代码，没有 Windows 专用功能分支。运行时无控制台窗口，日志分别在两个 workspace 子目录的 `node.log`。任务已存在时先检查，不要盲目覆盖；服务升级只在节点空闲时安排。

## macOS / Linux

已有确认可用的原生 socket 时，沿用原 Relay 及 workspace，使用连接器 `--attach`。新节点也可明确使用官方 CLI stdio；这不会自动取得 Codex Desktop UI 的共享运行时。

```sh
# 首次交互配置，输入本机独立接入凭据。
./scripts/start-node.sh --mode auto --direct-url "$RIVO_NODE_DIRECT_HTTPS" --no-pair \
  --codex-runtime stdio --codex-executable /实际绝对路径/codex
```

默认 workspace 是 `workspace/local-node`。首次运行确认连接后 Ctrl+C，随后把同一命令交给系统后台管理器。已有 Relay 时使用其 workspace 和 `--attach`，以复用正在运行的监听端口。

macOS LaunchAgent 使用以下配置字段（路径均替换为绝对路径，不能用 `~`）：

- `Label`: 自定义唯一名称，如 `local.rivo-relay.node`。
- `ProgramArguments`: `[/源码/.venv/bin/python, -m, rivo_relay.gateway, node, --workspace, /源码/workspace/local-node, --no-pair]`。
- `WorkingDirectory`: 源码绝对路径；`RunAtLoad` 和 `KeepAlive`: `true`。
- `StandardOutPath` / `StandardErrorPath`: 已创建且当前用户可写的日志路径。

保存为 `~/Library/LaunchAgents/local.rivo-relay.node.plist`，用 `plutil -lint` 检查 plist，再执行 `launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/local.rivo-relay.node.plist`。这是登录用户服务；用户未登录时运行不在此部署范围内。首次保存的 runtime、入口和密钥配置会被复用。

Linux 可使用用户 systemd 服务，`ExecStart` 使用上述绝对路径参数，设置 `WorkingDirectory`、`Restart=on-failure`、`RestartSec=5`，安装到 `~/.config/systemd/user/rivo-relay.service` 后执行 `systemctl --user daemon-reload`、`systemctl --user enable --now rivo-relay`。需要注销后保持运行时，由管理员配置该用户的 linger。不要以 root 运行用户的 Codex。

后台就绪后生成配对二合一码：

```sh
.venv/bin/python -m rivo_relay.gateway node --workspace workspace/local-node --attach --pair-only
```

## 手机验收与维护

1. 用 VPS HTTPS 直链下载安装 APK，再扫描当前机器的二合一码。码 5 分钟内有效、只能使用一次。两条路径共用节点身份，App 自动选择可用通道。普通节点使用默认更新源配置。
2. 查看项目，发起小型实际任务，检查结果和文件读取。Windows 完全访问编码已实机通过；同一主机 Session 0 的受限 workspace-write 沙箱仍存在已记录的启动失败。按实际需求显式选择会话权限，不自动放宽其他会话或全局配置。
3. Windows 检查 `Get-Process pythonw | Select-Object Id,SessionId,MainWindowHandle`：本部署预期 Session 0、窗口句柄 0。计划任务启动和故障恢复已验证，整机重启后自启动需在新机器单独验收。
4. `connection closed` 先检查二维码是否含 VPS 路由、连接器是否记录 `gateway_connected`。`node_offline` 检查电脑服务和连接器；配对码过期则重新生成。浏览器直接访问自签名 VPS 不等价于 App 的指纹校验测试，不要关闭 TLS 校验。
5. 备份同一机器的 workspace（包含数据库及私有配置）。升级保留 workspace 和接入凭据，停止本机对应服务、更新源码及虚拟环境依赖，再启动并复验；不要删除原生 Codex 数据或顺手重启其他节点。

未迁移的旧节点仍需单独复制源码升级；迁移到[统一更新代理](central-updates.md)后，日常发布会由 VPS 分发节点程序和 APK。当前发布约定不推送 GitHub；首次迁移将本版源码安全复制到目标机器，再按统一更新指南安装。源码中不应包含 workspace、凭据或配对图。

验证依据：[历史验证归档说明](validation.md)、[VPS 通道说明](vps-gateway.md)。2026-11-08 用户已确认 Windows 双通道二维码手机配对成功；不把此结果扩大为其他机器或全部功能已验收。

### macOS 无 GUI 开机服务

`install-managed-node.py` 支持 `--system-service --service-user <普通用户名>`，将代理注册到 `/Library/LaunchDaemons` 的 system 域，进程仍以指定普通用户运行。必须显式传入 `--codex-runtime stdio --codex-executable <绝对路径> --codex-home <绝对路径>`，不能使用桌面 socket。

先以服务用户运行 `--prepare-only`，再以 sudo 使用相同参数运行 `--activate`。切换已有节点前等待任务结束，停止并备份移走对应旧 LaunchAgent；保留 workspace、凭证及节点身份。切换 stdio 前须备份 gateway-client.json 并移除其中的 native_socket，否则安装器会拒绝继续。已有 bootstrap 应沿用原安装环境，核对 prepared.json 与运行时参数后再迁移，不可覆盖安装。此模式不会迁移桌面运行时正在执行的任务，也不会自动转换其他服务（例如语音后端）。

验收应检查本地 health、VPS 节点连接和实际任务执行，并在退出 GUI 后及重启后复验。启用 FileVault 的 Mac 断电重启后仍可能需要先解锁磁盘。
