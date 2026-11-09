# VPS 统一更新

统一发布入口使用现有 VPS `https://134.175.156.219:80`，节点程序包、APK 和版本清单实际保存到 VPS，不再依赖主节点在线。普通发布仍不推送 Git，不创建 tag 或 GitHub Release。

## 行为

- App 从当前配对节点的 VPS 路由提取网关地址，沿用二维码里已信任的证书指纹；检查和下载不携带手机配对令牌，也不访问节点 slot。已有 VPS 配对在安装新版 App 后直接生效，无需扫描主发布节点二维码。仅直连节点仍保留原更新源回退。
- 手机在“设置 → 检查更新”下载安装；系统安装确认仍由用户完成，不做静默安装。
- `managed-node` 每分钟检查一次统一版本。程序包验证大小和 SHA-256，在独立虚拟环境安装依赖，之后才尝试切换。断网或依赖安装失败不停止旧节点。依赖首次安装需要访问 Python 包源。
- 切换前原子关闭请求准入，检查队列、待确认提交、自有会话 writer、原生活动任务和目标；忙碌或状态不明就延后。不会中止任务来强制更新。
- 停止自己管理的 Relay 和连接器，备份 SQLite，启动新版本；新版本在本地节点身份、版本、原生连接和 VPS 连接检查通过前不接收业务请求。失败恢复数据库和旧版；中途断电留有 pending 状态，下次启动先恢复。失败包不循环重试，后续发布更高版本再升级。
- 保留 workspace 路径、数据库、登录与网关凭据，节点 ID 不变。不会停止外部 Codex Desktop/原生 daemon；Relay 自有的空闲 CLI 子进程随 Relay 生命周期退出。
- 代理自身为稳定 bootstrap 协议 1，安装后不会原地覆盖。将来若需要改变代理协议，必须显式迁移；不宣称代理能够无限自升级。

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

沿用 `scripts/release.py prepare/run/resume` 的版本递增、相关测试、APK 签名和主节点发布流程。最后新增 VPS 发布及 HTTP 验收，只有两者都成功才记录 complete。VPS 清单采用单次原子替换，同一清单同时指定 App 与节点程序包；相同 versionCode 不得替换不同内容，不得降级。

底层 `scripts/publish-gateway-update.py --commit <发布提交>` 仅导出已锁定提交的 server 代码，并复用已通过签名验证的本地主节点 APK；不能把它当成跳过构建/签名检查的通用发布入口。上传只通过管理员 SSH，节点接入密钥没有发布权限。上传后经固定证书验证的 HTTPS 完整下载 APK 和节点包核验大小与 SHA-256。中断可复用同一提交/产物重跑，仍不创建 GitHub 发布。

首次使用前必须先完成 VPS 服务代码迁移；旧网关没有统一发布模块。部署时让 `/opt/rivo-relay/current` 指向已验证的服务端快照，发布器通过该路径调用同一版发布模块；systemd 也使用该快照，避免服务与 SSH 发布命令读取不同源码。源码变化需要创建新版本，不可覆盖同 versionCode 的 APK/节点包。

## 空间与清理

- VPS 更新目录 `/var/lib/rivo-gateway/releases`：默认最多 512 MiB 文件，保留最新 3 个版本，另保留最近 24 小时内淘汰的版本，允许已获得旧清单的手机下载完成。高频发布可能超过 3 版；达到上限时拒绝新发布，不提前打破下载缓冲。
- 上传前计算暂存及落盘空间，至少预留 1 GiB；最终发布再次检查剩余空间和目录限额。只回收不再引用的哈希文件与超过 24 小时的本发布器暂存目录，当前版本始终受保护。
- 节点保留当前、上一版回滚环境及待切换环境；其他版本目录按需清理。安装前预留包解压空间和至少 1 GiB；pip 不保留下载缓存。
- 代理日志 2 MiB × 3；节点运行日志约 5 MiB，另保留一次重启轮换日志。bootstrap、当前/上一版依赖和 SQLite 回滚备份不按文件大小盲删。
- 不清理其他服务的日志、目录、证书、登录或设备数据。磁盘实时检查记录见 `validation-central-updates.md`。

## 设备维护入口

先读 `/Users/ethan/Documents/系统管理/家庭局域网设备清单.md`，再读对应设备详情。VPS SSH 使用 `root@134.175.156.219` 和 `/Users/ethan/Documents/QiGuangBot.pem`，加 `-o IdentitiesOnly=yes`。不要输出私钥或接入凭据，不要重复向用户询问清单中已有信息。
