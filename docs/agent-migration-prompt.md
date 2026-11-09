# 给另一台节点 Codex 的迁移提示词

请将这台机器现有的 Rivo Relay 节点迁移到 VPS 自动更新代理，并在完成后直接生成手机 APK 下载二维码。请实际执行、验证，不要只给操作建议。

仓库：https://github.com/ethanhubin/rivo-relay
迁移源码须包含当前自动更新和空闲判定修复；不要使用旧版 bootstrap。
先阅读该版本的 AGENTS.md、docs/central-updates.md、docs/node-deployment.md 和 scripts/install-managed-node.py。仓库中的主发布机器路径、VPS 管理密钥路径不是这台机器的配置，不要照搬，也不需要登录或重新部署 VPS。

1. 识别本机操作系统、现有 Rivo 安装目录、实际 workspace、服务/计划任务、端口、运行用户及 Codex runtime/executable/home/native-socket 参数。备份服务配置和 SQLite，保留 VPS 凭据、配对授权、节点身份及 .codex。不要输出凭据正文，不重新创建 workspace，不卸载手机 App。
2. 获取明确包含修复的源码，放到独立迁移目录或隔离 checkout；不要覆盖本机未提交改动。先检查 Python 3.11+。普通 VPS 发布没有 Git tag 或 GitHub Release，不要假定它们存在。
3. 使用该机器原节点的运行用户和真实参数，执行 scripts/install-managed-node.py --workspace <原workspace绝对路径> --prepare-only，并携带原服务必须的 --codex-runtime/--codex-executable/--codex-home/--native-socket 参数。此时保留旧服务运行，确认依赖和 bootstrap 准备成功。
4. 确认没有运行中任务、活跃目标或待确认提交/队列。忙碌就等待，不中断任务。确认空闲后停止旧 Relay 和独立 VPS 连接器，并禁用它们的重复自动启动；不要重启 Codex Desktop 或外部 Codex 原生 daemon，不改 VPS、主发布节点或其他机器。
5. 用与准备阶段完全相同的参数，改用 --activate 启动代理。Windows 使用原节点用户的管理员 PowerShell 注册 Limited/S4U 无窗口任务；macOS 使用该用户的 LaunchAgent；Linux 使用用户 systemd。保留原配置用于失败恢复，勿让新旧服务同时监听。
6. 检查 workspace/auto-update/agent.log、node.log、state.json、本地 /health 和经 VPS 的连通性，确认节点 ID、原配对授权保持、已到 VPS 最新发布版本且没有 pending/failed 状态。验证原外部 Codex daemon 身份未变化。不要把“服务已注册”当成更新成功；若失败，停止新代理并恢复备份的旧服务，报告具体原因。
7. 如需 APK，从统一更新中心读取当前清单，验证其中的下载链接、版本、大小与 SHA-256，再生成下载二维码；不要拼接不存在的 GitHub Release 地址。覆盖安装保留数据，Android 系统安装确认由用户完成。
8. 最后简要列出实际安装版本、后台服务名、自动更新状态、检查结果和未验证范围。之后这台节点会从同一 VPS 自动获取更新，不需要本机构建 APK，也不用每次手动 git pull。
