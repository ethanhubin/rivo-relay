# Windows 节点部署验证（2026-11-08）

当前结果：官方 Codex CLI 0.167.0 在“完全访问、不要求审批”会话下的真实编码验证已通过，后台无窗口运行正常。受限 workspace-write 沙箱仍失败。下文保留历史验证过程，最新完全访问结果见文末。

## 环境与系统切换

- 主机：Comfy 服务器 `Ethan_Home_9550`，局域网 `192.168.0.8`，Windows SSH 用户 `darkv`。
- 切换前核对 Linux 主机名 `comfylnx`、根分区 UUID `ad93c507-0072-4f32-848c-ddd6e04ee65d`；统一 AI 管理器 idle，管理器及 ComfyUI 队列均为空。
- 设置 `efibootmgr -n 0000`，再次读取确认 `BootNext: 0000` 对应 Windows Boot Manager，然后执行 `systemctl reboot`。默认 `BootOrder: 0001,0000` 保持不变。
- 第一次尝试未远程确认 Windows 上线，用户随后确认进入 Linux；第二次设置并重启后，Windows SSH 成功连接。
- 系统：Windows 11 Pro，版本 `10.0.26100`；测试 Python `3.12.10`。

## 安装与启动检查

- 使用本地工作区 `server` 源码（HEAD `3ea97e7`）生成独立压缩包；未包含用户配置、凭证或会话。
- 压缩包 SHA-256：`ef2cce32e122d8c43a55e4ded29e2747435524c626355acf0e9a09d8ac01250f`。
- 远端测试目录：`C:\Users\darkv\rivo-validation-20261108`，创建独立 `.venv`。
- `pip install ./server` 成功：rivo-relay 0.1.0、websockets 15.0.1、qrcode 8.2、Pillow 13.0.0、colorama 0.4.6。
- `python -m rivo_relay --help` 和以下服务启动命令均退出 1：

```powershell
.\.venv\Scripts\python.exe -m rivo_relay serve --workspace workspace --host 127.0.0.1 --port 18765
```

失败发生于 `rivo_relay/__main__.py:6`：

```text
import fcntl
ModuleNotFoundError: No module named 'fcntl'
```

远端 `startup-result.txt` 保存服务启动输出；检查未发现 18765 监听。未绕过锁机制或修改业务代码。

## 验证边界与保留状态

本次完成实体 Windows 的系统切换、SSH、包安装和入口启动验证。未运行会话、文件传输、Codex 连接、手机配对、Android 真机或模拟器测试；未运行全量测试、lint 或发布流程。

Windows 保持运行，测试目录和独立环境保留，未注册 Relay 自启动服务。Linux ComfyUI 在 Windows 运行期间不可用。后续需完成 Windows 文件锁、进程管理和原生运行时连接适配，再进行端到端验证。


## 适配后验证与后台部署

- 运行时固定为已注册 Desktop 包的 `codex.exe` 0.160.1；不使用未注册的 staged 包或旧 winget CLI。
- 增加显式 stdio、Windows LockFileEx、内核 PID/创建时间身份校验、Job Object 进程树清理、Windows 路径处理。
- 历史日志指纹统一使用文件句柄 fstat，解决 Windows Python 3.12 stat/fstat 的 ctime 语义差异，保留变更检测。
- 本地相关回归：106 项，103 通过、3 项 Windows 内核测试跳过。Windows 实机平台测试：9 项全部通过。
- 实机 `acceptance-07`：服务启动、独占 workspace 锁、配对、项目创建、Unicode 目录、Git、17 字节及 9 MiB 上传/原生下载、工作树指纹与清理、真实模型回复、SSE、历史、提交去重、Todo CRUD、原生 writer 锁及释放/恢复均通过。
- 第二个模型 turn 的只读 PowerShell 工具失败；追加定向验证在匹配 Desktop 沙箱 feature 与 package family 后仍失败。没有关闭沙箱、自动批准或绕过模型权限。
- Windows 系统事件 26：`codex-command-runner-0.160.1.exe` 初始化失败 `0xc0000142`；模型收到 `timed out after 15000ms connecting runner pipe-in`。与 [OpenAI Codex Session 0 问题 #37722](https://github.com/openai/codex/issues/37722) 一致。节点 API 健康不代表模型工具可用。

部署目录：`C:\Users\darkv\rivo-validation-20261108`；workspace：`workspace\windows-node`。隐藏计划任务 `Rivo Relay` 使用 S4U、pythonw、开机触发及失败重启；实测主进程与 Codex 子进程均为 Session 0、MainWindowHandle=0。配置抑制系统故障对话框，日志写入 workspace。使用计划任务实现后台驻留，未注册 SCM Service。

已验证后台节点健康、配对鉴权、原生运行时连接及手动停止/启动；启动原生运行时后再次停止任务，确认其 PID 已退出，随后启动恢复健康。真实开机触发未通过重启电脑验证；故障自动恢复的追加实测见文末。临时 `Rivo-Windows-Validation` 任务已移除；部署检查创建的连接令牌已撤销。

Tailscale Serve 配置 `https://ethan-home-9550.tail791ea5.ts.net:18443` → `127.0.0.1:18765`。外部 HTTPS 验证结果见下方补记。

发现 Windows 时间未同步、UTC 慢 8 小时；通过已运行的 Windows Time 服务 `w32tm /resync /rediscover` 成功校时。

其他回归中三个失败已在未修改 HEAD 基线上复现：两项 Config 测试仍断言旧 health 响应，另一项历史优化测试期待旧调用次数；未计入本次通过结果。没有执行 Android 真机/模拟器、全量测试、lint、发布、提交或推送。原有 Android 修改保留。

### HTTPS 补记

校时后 Tailscale 证书签发完成；从开发机通过真实 HTTPS 域名和 18443 端口请求 `/health` 返回 200、status=ok、与后台节点相同的 nodeId。未关闭 TLS 校验。


## 追加验证：非交互沙箱（2026-11-08）

为复核剩余失败，使用隔离脚本和临时内核桌面对象进行实验，没有替换已部署运行时、改写全局 Codex 配置或修改现有桌面对象的权限。

| 检查 | 结果 |
| --- | --- |
| 当前已注册 Desktop | 26.930.7945.0，原生 Codex 0.160.1，sandbox service Running |
| 原后台 Session 0 elevated 沙箱 | 既有实测：runner pipe-in 超时，事件 26 / 0xc0000142 |
| 新建非交互 window station + private desktop，经 CreateProcessW 显式启动 | runner 可返回；PowerShell/Git 子命令退出 -1073741502（0xc0000142），未通过 |
| 已安装 staged 0.167.0-alpha.3.2 使用 MXC | 退出 1：native MXC is unavailable on this executor；未切换部署 |
| 现有 Session 0 Winsta0 只读/创建访问检查 | Access denied；没有改写其 ACL |

源代码定位：Codex rust-v0.160.1 的 `windows-sandbox-rs/src/desktop.rs` 中 `LaunchDesktop::open_private` 将命令桌面写为 `Winsta0\{name}`，而私有桌面由当前 window station 创建。这与私有后台 window station 不兼容。实验消除了最初的 runner pipe 超时，但没有消除命令初始化失败，不能据此认定已修复。

追加验证结论：**测试已执行，完整 Windows 编码节点验收失败**。失败位于原生沙箱启动链路，不是 HTTP、会话或文件传输能力。没有将模型命令改为无沙箱执行；隔离实验没有进入产品代码。后台驻留节点仍保留，不能将健康检查通过作为可正常执行编码任务的依据。


### 后台故障恢复验收

故意强制结束实际节点 worker 后，原任务的 RestartCount=3 / RestartInterval=PT1M 未在 76 秒观察窗口内恢复服务，HTTPS 返回 502。因此增加独立 pythonw 守护层：worker 位于独占 Job Object 中；异常退出后先清理原进程树，再延迟 2 秒重启；一分钟内连续三次失败则停止重试并交回调度器。正常退出不重启。未增加交互窗口或用户登录依赖。

实际故障注入结果：worker PID 18544 与原生运行时 PID 18548 被回收，5 秒检查时新 worker PID 5180 已启动、health=ok。配对鉴权及原生运行时状态检查通过，临时连接令牌已撤销。正常 Stop-ScheduledTask 后原有全部后台进程退出；Start-ScheduledTask 后恢复 health=ok，全部四个 pythonw 进程（venv 启动层、守护层、worker 启动层及 worker）均为 Session 0、MainWindowHandle=0。外部 HTTPS 健康检查再次通过。

本轮未修改沙箱策略或运行时版本；新增产品代码仅为后台守护层。前轮相关单元测试结果复用，本轮执行 Windows 真实进程故障/恢复测试、Python 编译检查及 diff 检查，不重跑无关全量测试或 Android 验证。


## 官方 CLI 安装与复验

按用户要求停止自行编译路线，未构建或部署修改版 Codex。使用官方 npm 包安装稳定版：`npm.cmd install -g @openai/codex@0.167.0`；版本输出 `codex-cli 0.167.0`。Rust 下载/安装进程已停止，独立源码与工具链准备目录未加入系统 PATH，也不参与服务运行。

后台节点配置已切换至官方 CLI：`C:\Users\darkv\AppData\Roaming\npm\node_modules\@openai\codex\node_modules\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\bin\codex.exe`。原配置备份 `workspace\windows-node\background.before-official-0167.json`。独立 CLI 不再强行启用仅用于 Desktop 包的 sandbox service feature。

实机完整复验报告：远端 `acceptance-official-0167/report.json`。HTTP、鉴权、文件上传下载、Unicode、Git、工作树、真实模型回复/SSE/历史、去重、Todo、writer 锁释放/恢复、进程清理通过。独立官方 CLI 的 `sandbox -P :workspace` 只读命令仍报 `timed out after 15000ms connecting runner pipe-in`；真实模型工具 turn 也失败。安装最新版未解决此主机 Session 0 沙箱问题，未将其记录为完整验收通过。

部署后的鉴权 `/v1/node` 确认 runtime 0.167.0 且 connected=true，临时验证令牌已撤销。相关回归 32 项：29 通过、3 项 Windows 内核测试在本机跳过。


## 完全访问模式：真实编码验收通过


- 官方 CLI：0.167.0；有效会话设置已从 API 核对：`sandbox.type=dangerFullAccess`，`approvalPolicy=never`。
- 故意构造 `add(a,b)` 返回 `a-b` 的错误实现，两个单元测试初始均失败。
- 模型真实调用 PowerShell 读取源码和测试，通过 apply_patch 修复为 `a+b`，测试文件保持不变。
- 原生 rollout 证实运行了指定 Python 的 unittest，以及 `git diff -- calculator.py` 和 `git status --short`。两个命令事件均退出 0，原生输出包含 `Ran 2 tests` / `OK`。
- 验证器独立再次运行两个测试，全部通过；Git tracked diff 仅有 calculator.py 的一行修复，没有提交。
- SSE 完成事件、相同 submission 去重、writer release/resume、恢复后历史仍只有一个 turn，全部通过。
- 运行中节点及两个官方 codex.exe 进程均为 Session 0、MainWindowHandle=0；无交互窗口。临时配对令牌已撤销，测试项目和会话留存供复查。
- 会话 ID：`01a1b9f6-e931-7ab1-ad23-06a1dac3aba1`；turn ID：`01a1b9f6-e999-7293-957e-beb756096ceb`。

证据目录：[完全访问验收](validation/windows-full-access-2026-11-08/)，包含原始验收报告、有效设置、原生命令输出、独立测试输出和实际 diff。可重复探针：`scripts/verify-windows-full-access.py`（仅显式创建全新完全访问测试会话，不改已有权限）。

结论应区分模式：**Windows 官方 CLI 完全访问编码能力通过；受限沙箱在此主机的非交互 Session 0 中仍不兼容。** 之前将受限沙箱失败概括为整个 Windows 编码节点不可用，范围过大，现已更正。Android 真机/模拟器与开机重启触发未在本轮验证；后台故障恢复沿用前轮实测。
