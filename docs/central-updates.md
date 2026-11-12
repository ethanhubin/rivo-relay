# VPS 统一更新

统一发布入口使用现有 VPS `https://134.175.156.219:80`，节点程序包、APK 和版本清单实际保存到 VPS，不再依赖主节点在线。普通发布仍不推送 Git，不创建 tag 或 GitHub Release。

新增管理节点使用[一次性初始化命令](node-updates.md#将普通节点设为管理节点)，该节点本机完成构建、验签和 VPS 发布，无须另设“主节点”。每次新发布锁定最新 `origin/main` 的独立工作区；恢复复用原提交。原发布机器的流程继续保留，以下涉及本机服务切换和主节点 HTTP 前置验收的描述仅适用于原流程。管理节点保留相同 ASR、签名和 VPS 下载验收，旧 HTTP 地址改为发布后的兼容镜像，暂时离线不阻止 VPS 发布。

## 行为

App 与节点独立升级，发布成功不表示所有绑定节点已升级。新版清单从实际 APK 提取 `compatibility.apiVersions`，App 按 [兼容协议](api-compatibility.md) 检查绑定节点后才进入安装流程；历史清单/节点缺少声明时显示未知，不按软件版本号推断兼容。

- App 从当前配对节点的 VPS 路由提取网关地址，沿用二维码里已信任的证书指纹；检查和下载不携带手机配对令牌，也不访问节点 slot。已有 VPS 配对在安装新版 App 后直接生效，无需扫描主发布节点二维码。仅直连节点仍保留原更新源回退。
- 手机在“设置 → 检查更新”下载安装；系统安装确认仍由用户完成，不做静默安装。
- `managed-node` 每分钟检查一次统一版本。程序包验证大小和 SHA-256，在独立虚拟环境安装依赖，之后才尝试切换。断网或依赖安装失败不停止旧节点。依赖首次安装需要访问 Python 包源。
- 切换前原子关闭请求准入，检查队列、待确认提交、自有会话 writer、原生活动任务和目标；忙碌或状态不明就延后。不会中止任务来强制更新。
- 停止自己管理的 Relay 和连接器，备份 SQLite，启动新版本；新版本在本地节点身份、版本、原生连接和 VPS 连接检查通过前不接收业务请求。失败恢复数据库和旧版；中途断电留有 pending 状态，下次启动先恢复。启动崩溃、损坏包隔离；临时网络/就绪超时采用有上限的退避重试，详见下方诊断与恢复。
- 保留 workspace 路径、数据库、登录与网关凭据，节点 ID 不变。不会停止外部 Codex Desktop/原生 daemon；Relay 自有的空闲 CLI 子进程随 Relay 生命周期退出。
- 分发协议保持 1；代理修订号独立记录。节点包不会自行替换 bootstrap；本次提供显式停机升级与回滚命令，不能把节点升级成功当作代理已升级。

## 一次性部署

这是协议迁移，旧节点不会凭空获得更新代理。仓库实现并通过本地检查不代表生产网关、其他电脑或手机已经迁移。

1. VPS：更新 `/opt/rivo-relay` 的服务端代码和必要依赖，保留 `/var/lib/rivo-gateway` 的证书、接入密钥和服务用户，安排重启 `rivo-gateway.service`。此次重启会短暂断开现有中转连接，连接器会重连。原 443 Merge Release 与 Tailscale 服务不变。
2. 其他电脑：复制本版源码，等待任务完成，停止并禁用原 Relay 和独立连接器的自动启动服务。不要停止 Codex Desktop 或共享 daemon。
3. 推荐先在旧服务仍运行时用 `--prepare-only` 安装独立 bootstrap，检查依赖准备成功。确认空闲、停止旧服务后，用相同参数将 `--prepare-only` 换成 `--activate` 注册后台服务，缩短停机时间。始终使用**原 workspace 绝对路径**；激活拒绝接管仍占用端口的服务。省略这两个选项会一次完成安装和注册。

macOS / Linux 示例：

```sh
.venv/bin/python scripts/install-managed-node.py --workspace /原节点/workspace/local-node
```

已保存到 `gateway-client.json` 的 runtime、Codex 路径会继续使用。原服务通过命令行指定的参数若未保存，需要补上 `--codex-runtime`、`--codex-home`、`--codex-executable` 或 `--native-socket`。原生 socket 必须使用该节点实际路径，不能复制其他机器的路径。

Windows 示例（使用节点用户的管理员 PowerShell）：

```powershell
.\.venv\Scripts\python.exe scripts\install-managed-node.py --workspace C:\Rivo\workspace\windows-node --codex-runtime stdio --codex-executable C:\实际路径\codex.exe --codex-home C:\Users\实际用户\.codex
```

Windows 创建 Limited/S4U 无窗口计划任务；macOS 创建登录用户 LaunchAgent；Linux 创建用户 systemd 服务，需要注销后运行时另按原部署规范配置 linger。准备成功只表示依赖就绪，激活命令成功只表示服务已注册，须检查 `workspace/auto-update/agent.log`、`node.log`、本地 `/health` 和经 VPS 的手机连接。若安装中断，保留现场检查 bootstrap，安装器不会覆盖已有 bootstrap。

4. 手机覆盖安装一次包含此功能的 APK。之后连接任一已配对 VPS 节点即可检查统一更新；无需额外配置更新源。
5. 本机主发布节点继续由原发布 CLI 管理，无需同时再装代理，避免两个管理器争用同一节点。

## 日常发布

沿用 `scripts/release.py prepare/run/resume` 的版本递增、相关测试、APK 签名和主节点发布流程。发布主节点/VPS 更新清单之前，必须完成 e2mac 独立 ASR 同步与验收；随后完成 VPS 发布及 HTTP 验收，全部成功才记录 complete。VPS 清单采用单次原子替换，同一清单同时指定 App 与节点程序包；相同 versionCode 不得替换不同内容，不得降级。

底层 `scripts/publish-gateway-update.py --commit <发布提交>` 仅导出已锁定提交的 server 代码，并复用已通过签名验证的本地主节点 APK；不能把它当成跳过构建/签名检查的通用发布入口。上传只通过管理员 SSH，节点接入密钥没有发布权限。上传后经固定证书验证的 HTTPS 完整下载 APK 和节点包核验大小与 SHA-256。中断可复用同一提交/产物重跑，仍不创建 GitHub 发布。

首次使用前必须先完成 VPS 服务代码迁移；旧网关没有统一发布模块。部署时让 `/opt/rivo-relay/current` 指向已验证的服务端快照，发布器通过该路径调用同一版发布模块；systemd 也使用该快照，避免服务与 SSH 发布命令读取不同源码。源码变化需要创建新版本，不可覆盖同 versionCode 的 APK/节点包。

## 空间与清理

- VPS 更新目录 `/var/lib/rivo-gateway/releases`：默认最多 512 MiB 文件，保留最新 3 个版本，另保留最近 24 小时内淘汰的版本，允许已获得旧清单的手机下载完成。高频发布可能超过 3 版；达到上限时拒绝新发布，不提前打破下载缓冲。
- 上传前计算暂存及落盘空间，至少预留 1 GiB；最终发布再次检查剩余空间和目录限额。只回收不再引用的哈希文件与超过 24 小时的本发布器暂存目录，当前版本始终受保护。
- 节点保留当前、上一版回滚环境及待切换环境；其他版本目录按需清理。安装前预留包解压空间和至少 1 GiB；pip 不保留下载缓存。
- 代理日志 2 MiB × 3；节点运行日志约 5 MiB，另保留一次重启轮换日志。bootstrap、当前/上一版依赖和 SQLite 回滚备份不按文件大小盲删。
- 不清理其他服务的日志、目录、证书、登录或设备数据。历史磁盘检查记录的追溯方式见[历史验证归档说明](validation.md)；新的实时检查记录写入本项目验证记录。

## 下载传输与诊断

更新文件接口在 Linux 上尝试为当前 TCP 连接选择 BBR，降低丢包链路下 CUBIC 发送窗口收缩造成的吞吐下降。仅影响处理 APK/节点包的连接（包括该连接之后的 keep-alive 请求），不修改主机默认拥塞算法、队列规则或其他已有连接。是否改善具体手机链路必须实测，不能用服务器回环速度代替手机结果。

部署这版服务端前，管理员可加载发行版自带模块，并确认普通服务用户有权限选择它：

```sh
modprobe tcp_bbr
sysctl net.ipv4.tcp_available_congestion_control net.ipv4.tcp_allowed_congestion_control
runuser -u rivo-gateway -- python3 -c 'import socket; s=socket.socket(); s.setsockopt(socket.IPPROTO_TCP, socket.TCP_CONGESTION, b"bbr"); print(s.getsockopt(socket.IPPROTO_TCP, socket.TCP_CONGESTION, 16))'
```

确认支持且决定启用后，可将一行 `tcp_bbr` 写入 `/etc/modules-load.d/rivo-update-bbr.conf`，让重启后仍加载模块；无需修改 `net.ipv4.tcp_congestion_control`。模块不存在、无权限或非 Linux 时保留原算法，下载仍正常执行，日志标明回退原因。设置网关服务环境变量 `RIVO_UPDATE_CONGESTION=system` 并按正常部署流程重启即可停止为新下载连接选择 BBR。

`journalctl -u rivo-gateway.service` 中的 `update_download_started` / `update_download_finished` 用 `download_id` 关联；响应也返回 `X-Rivo-Download-Id`。记录请求是否续传、实际算法、状态、发送耗时、响应体字节数、Linux TCP RTT/发送窗口和本次重传段数，不记录令牌、URL、文件路径、异常正文或客户端地址。连接取消/异常记录 `interrupted`，不冒充完成。`sent` 仅表示服务端完成交给传输层，不代表手机已收到全部字节或通过 SHA-256 校验；中断字节数未知时记为 `null`。

本次排查及验证范围见 [历史验证归档说明](validation.md)。

## 设备维护入口

先读 `/Users/ethan/Documents/系统管理/家庭局域网设备清单.md`，再读对应设备详情。VPS SSH 使用 `root@134.175.156.219` 和 `/Users/ethan/Documents/QiGuangBot.pem`，加 `-o IdentitiesOnly=yes`。不要输出私钥或接入凭据，不要重复向用户询问清单中已有信息。


## 更新诊断与故障恢复（代理修订 2）

- 连接器在连接/断开时更新 `auto-update/connected.json`，并刷新时间戳；候选验收要求当前进程 token 匹配、连接在线、时间戳未超过 15 秒。首次 15 秒建链等待超时后仍可恢复，不再漏写标记。已有旧节点启动兼容旧标记，升级到新节点后使用新就绪协议。
- 更新先请求短期空闲许可，再携带许可提交停止。空闲判断只读取原生元数据和目标状态，不 resume 会话或修复历史；原生检查不持有准入锁，5 秒预约过期后迟到结果不能授权停止。停止响应丢失时先观察自己管理的子进程，避免重复启动。
- 保留队列、待确认提交、目标和运行任务的保护。`unknown` 不自动改成成功或失败；状态会明确报告 `submission_pending_or_unknown` 等原因，需要通过原有提交核对流程确认，不能用升级命令重放用户任务。
- 网络/就绪/安装等临时失败累计最多 3 次尝试，第一次失败后等待 60 秒、第二次后等待 300 秒；第三次暂停该包并报告 `retry_exhausted`。崩溃和包校验失败隔离。失败状态仅使用 `retries` 和 `quarantine`，不再维护旧 `failed` 列表或执行旧失败状态转换。更高版本的不同包独立计算。
- 代理写入不含令牌、路径和异常正文的 `auto-update/status.json`：阶段、当前/目标版本、原因、尝试次数、下次重试时间、代理修订号及更新时间。阶段覆盖检查、下载、安装、等待空闲、候选验收、回滚、重试等待/耗尽和隔离。检查间隔是每轮结束后 60 秒，下载安装时间另计。
- 已配对客户端可鉴权读取 `GET /v1/node-updates/status`；本地可运行 `python scripts/node-update-status.py --workspace /原workspace`。旧代理没有状态文件时返回 unknown，不推断“已启动自动更新”。`agent.log`、候选 `install.log` 与 `node.log` 仍用于本地详细诊断。

### 当前节点更新协议

发布前开发阶段统一使用当前协议，不再接受旧版单步停止与 token-only 就绪标记：

- `/internal/update-stop` 必须携带 `/internal/update-lease` 签发的有效 `lease`；缺失或类型错误返回 400，过期租约仍返回 409。
- 网关就绪标记必须包含当前 token、`connected: true` 和最近 15 秒内的 `updatedAt`，不再按 `readyProtocol` 降级判断。
- 旧实验节点需先更新 bootstrap 与节点代码；仅写入 token 的旧节点不能作为要求网关就绪的候选版本通过验收。现有离线启动与回滚路径仍保留 `require_gateway=False` 的明确行为。

### 显式升级已有 bootstrap

此流程需要该节点的管理权限，不能仅通过 VPS 普通中转接口代办。先确认用户任务结束，**停止已有 managed 服务**，不要停止外部 Codex Desktop/daemon。使用更新后的源码和它的 Python 环境运行：

```sh
python scripts/install-managed-node.py --workspace /原workspace --upgrade-bootstrap
```

命令拒绝仍占用节点端口/agent 或 serve 锁的环境；先下载构建 wheels、在隔离环境验证，再备份并离线更新原 bootstrap venv。原服务注册、参数、workspace 和节点身份保持不变。安装失败自动恢复原环境；成功后保留 `bootstrap/venv.before-upgrade`。用原服务管理器重新启动，检查诊断 `agentRevision=2`、健康与 VPS 通道。Windows 同样使用原用户并停止计划任务后运行；本轮未做 Windows 真机维护演练。

若维护过程中断电/进程被强制结束，保持服务停止，使用：

```sh
python scripts/install-managed-node.py --workspace /原workspace --restore-bootstrap
```

仅恢复明确事务中的完整备份。完成验收前保留备份；再次升级遇到旧备份时命令拒绝覆盖，先检查并归档旧备份。此命令不恢复用户数据库，也不撤销已完成的节点升级。

确认故障原因已处理后，可在代理停止时显式重置某一个包的隔离/重试计数：

```sh
python scripts/node-update-status.py --workspace /原workspace --retry-sha256 <完整64位包摘要>
```

只重置该包的更新状态；存在 pending 候选时拒绝重置，先完成回滚恢复。不会清除业务队列或不确定提交。

## 提交阶段与历史 unknown 恢复

`thread.turns` / `thread.continue` 在实际 `turn/start` 边界同步保存阶段：

- `preparing`：尚未发送任务；恢复会话、读取状态、准备输入失败时，确认为 `turn_not_dispatched`。独占启动恢复也可将中断在此阶段的记录确认为失败。
- `dispatching`：发送前已落盘；超时、断连或崩溃后仍是 `unknown`，不自动重试。
- `received`：原生 thread/turn 回执已落盘；后续派生状态处理失败或重启时，恢复为 accepted。
- 空阶段：旧记录或未接入阶段跟踪的其他操作，继续保留原有不确定性；不能根据记录年龄、空历史或节点空闲断言未执行。

新字段为兼容性增列，不清空旧数据。手机普通 reconcile 仍不能将正在准备中的请求确认为失败。更新准入继续拒绝真正未核实的 pending/unknown，不通过放宽检查绕过它们。

历史 turn 提交由本地管理员使用 `scripts/recover-submission.py` 核对；工具不会重发任务。先只读查看记录和指纹：

```sh
python scripts/recover-submission.py --workspace /节点/workspace --submission <提交ID>
```

核对原生请求/进程日志与回执，准备私有证据 JSON，例如：

```json
{
  "expectedFingerprint": "上一步返回的 fingerprint",
  "decision": "not_dispatched",
  "reviewedBy": "实际核对人",
  "reason": "说明提交与原生进程的关联，以及确认未发送 turn/start 的依据",
  "dispatchNotSentVerified": true,
  "files": [{"path": "/绝对路径/脱敏证据摘录.txt", "sha256": "摘录的 SHA-256"}]
}
```

只有核实原生未收到任务发送，才能使用 `not_dispatched`。仅找不到历史或没有回执不够；工具检查文件完整性、记录一致性和声明，不代替操作者判断证据语义。确认已经接收时改用 `decision: accepted`，并提供 `receipt: {"threadId": "…", "turnId": "…"}` 与可关联该提交的原生回执证据。

停止该 workspace 的 Relay 和 managed agent（不停止 Codex 原生运行时）后先预演，再应用：

```sh
python scripts/recover-submission.py --workspace /节点/workspace --submission <提交ID> --evidence /私有路径/evidence.json
python scripts/recover-submission.py --workspace /节点/workspace --submission <提交ID> --evidence /私有路径/evidence.json --apply
```

恢复要求两把服务锁均空闲，拒绝已确认结果、指纹变化、证据文件变化及与已有 dispatch/receipt 矛盾的失败声明。应用前生成 SQLite 一致性备份、保存证据摘录和原记录；恢复结果与审计信息在同一数据库事务提交，同时在 `workspace/artifacts/submission-recovery-*` 保存记录。原提交 ID 与去重记录保留。完成后启动原服务并检查更新状态。证据与数据库备份含私有信息，不提交 Git。

### 发布时同步共享 ASR

发布 CLI 固定通过 SSH 别名 `e2-mac-mini` 同步 `/Users/e2/Services/rivo-asr`，使用目标独立虚拟环境和 `local.rivo-asr` LaunchAgent。发布电脑须事先配置非交互 SSH 登录；E2 不可达时停止发布，不跳过语音规则更新。

- 从本次构建对应的 Git commit 提取 `scripts/asr/mac_asr.py` 和 `server.py`，不读取工作区副本；权重、token、gateway.json 和连接器不变。
- 校验目标 `requirements.lock` 与发布提交一致；依赖变化需先单独升级环境和锁文件，本流程不自动安装依赖。
- 保存持久化恢复记录，再替换代码并仅重启 `local.rivo-asr`；健康检查与源码 SHA-256 验收通过后记录 `steps.asr` 和 `asr-verification.json`。ASR 代码未变且已验收时不重启，仍检查健康。
- 服务不健康则恢复旧代码并重启验收；恢复失败保留 `release-pending.json`。`resume` 会先恢复未完成的切换，再重试，不将 SSH 中断视为成功。最近一次切换备份在 E2 的 `release-backup.json`。
- 拒绝 ASR 版本降级或同 versionCode 替换不同内容。ASR 同步失败不会开始发布本次 App/节点清单；此时主节点可能已切换到新代码，修复后继续原发布计划。

此步骤只随使用新版 CLI 的发布执行；合并 PR 本身不会同步线上 ASR。首次纳管会重启 ASR 以确认加载版本，可能中断当时的录音；后续仅 ASR 代码变化时重启。依赖锁一致性和健康检查不代替真实录音识别验证。
