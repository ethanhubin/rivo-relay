# 给另一台节点 Codex 的迁移提示词

请将这台机器现有的 Rivo Relay 节点迁移到 VPS 自动更新代理，并在完成后直接生成手机 APK 下载二维码。请实际执行、验证，不要只给操作建议。

仓库：https://github.com/ethanhubin/rivo-relay
从最新 origin/main 获取迁移源码，确认包含系统服务安装支持及当前自动更新、空闲判定修复；检查已有 bootstrap 的兼容性，不覆盖安装。
先阅读该版本的 AGENTS.md、docs/central-updates.md、docs/node-deployment.md 和 scripts/install-managed-node.py。仓库中的主发布机器路径、VPS 管理密钥路径不是这台机器的配置，不要照搬，也不需要登录或重新部署 VPS。

1. 识别本机操作系统、现有 Rivo 安装目录、实际 workspace、服务/计划任务、端口、运行用户及 Codex runtime/executable/home/native-socket 参数。备份服务配置和 SQLite，记录并保留服务所需的环境变量（包括语音后端配置，不输出密钥），保留 VPS 凭据、配对授权、节点身份及 .codex。不要输出凭据正文，不重新创建 workspace，不卸载手机 App。
2. 获取明确包含修复的源码，放到独立迁移目录或隔离 checkout；不要覆盖本机未提交改动。先检查 Python 3.11+。普通 VPS 发布没有 Git tag 或 GitHub Release，不要假定它们存在。
3. 确定运行模式：macOS 默认迁移为无需 GUI 登录的 LaunchDaemon，以原普通用户运行，显式使用 `--system-service --service-user <原用户> --codex-runtime stdio --codex-executable <本机可执行文件绝对路径> --codex-home <原用户目录绝对路径>`。不要复用桌面 `app_server` 或 `--native-socket`；独立 stdio 不接管桌面运行时的在途任务。若用户明确要求保留桌面运行时，沿用 LaunchAgent，并说明它仍依赖 GUI 登录。Windows/Linux 沿用适用的部署流程。
4. 区分首次安装和已有 bootstrap：首次安装且配置无冲突时，以原用户执行 `--prepare-only`，保留旧服务运行并检查依赖。已有 `auto-update/bootstrap` 时不重复 prepare、不删除重建；备份 `prepared.json`、`gateway-client.json` 和服务文件，检查是否需要按维护流程 `--upgrade-bootstrap`。macOS 原配置含 `native_socket` 时，不在旧服务运行期间改写；先完成下一步停服，再备份并移除该字段，显式设置 stdio 及本机路径。已有 bootstrap 的 `prepared.json` 须在停服后调整为此次实际运行的参数列表（从 `managed-node` 开始，包含原 workspace、local-port、`--no-pair` 及显式运行时参数，不含安装器专用选项），并与激活参数逐项核对。
5. 确认没有运行中任务、活跃目标或待确认提交/队列。忙碌就等待，不中断任务，除非用户明确授权强制切换。确认空闲后停止旧 Relay 和独立 VPS 连接器，并禁用重复自动启动；macOS 必须 bootout 旧 LaunchAgent 并备份移走原 plist。不要重启 Codex Desktop 或外部 Codex 原生 daemon，不改 VPS、主发布节点或其他机器。完成上一步尚未执行的配置转换、bootstrap 准备或维护后，以完全相同的运行时参数执行 `--activate`。macOS 系统模式使用 sudo 激活，服务注册到 `/Library/LaunchDaemons` 的 system 域，UserName 必须是原普通用户，plist 属主 root、权限 0644。新安装器不会自动复制旧服务环境变量，激活前须安全补齐所需环境配置，避免语音等功能丢失。管理员密码由用户在本机输入，不索取或记录密码。Windows 使用原节点用户的管理员 PowerShell 注册 Limited/S4U 无窗口任务；Linux 使用用户 systemd，需要注销后持续运行时配置 linger。
6. 检查系统服务实际运行状态、workspace/auto-update/agent.log、node.log、state.json、本地 /health、经 VPS 的手机连接及一次实际任务执行，确认节点 ID、原配对授权保持、已到 VPS 最新发布版本且没有 pending/failed 状态。确认没有旧服务重复监听，原外部 Codex daemon 未被重启。失败时停止并卸载新服务，恢复备份的 gateway-client.json、prepared.json 和旧服务注册，再验收旧服务健康。不要把“服务已注册”当成成功。macOS 还需在用户安排的注销、重启后验证未登录 GUI 时可连接并执行任务；未实际执行则明确列为未验证，不擅自重启机器。FileVault 断电重启后可能仍需解锁磁盘。另查语音后端等依赖是否仍为 LaunchAgent，不将节点启动成功扩大为全部功能无需 GUI。
7. 如需 APK，从统一更新中心读取当前清单，验证其中的下载链接、版本、大小与 SHA-256，再生成下载二维码；不要拼接不存在的 GitHub Release 地址。覆盖安装保留数据，Android 系统安装确认由用户完成。
8. 最后简要列出实际安装版本、后台服务名、自动更新状态、检查结果和未验证范围。之后这台节点会从同一 VPS 自动获取更新，不需要本机构建 APK，也不用每次手动 git pull。
