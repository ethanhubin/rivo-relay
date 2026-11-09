# Git 与 PR

- 日常需求从最新 `origin/main` 创建 `codex/<需求名>` 分支，PR 目标为 `main`；不直接在 `main` 提交日常改动。
- 提交、推送、创建/更新 PR、审核和合并的授权及流程统一见 [GitHub PR 提交规范](docs/github-pr-workflow.md)，正文使用 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md)。
- 创建 PR 与发布是独立操作；PR 合并不自动发布，普通 VPS 发布仍不推送远端、不创建 Git tag 或 GitHub Release。

# 发布约定

- 用户说“发布”“发布新版”时，默认通过主节点发布 CLI 发布到 VPS 统一更新中心 `https://134.175.156.219:80`，同时保留主节点 Tailscale HTTP 更新源 `http://100.124.150.1:18767` 兼容入口；不创建 GitHub Release、不推送远端或 Git tag。统一更新迁移及磁盘策略见 `docs/central-updates.md`。
- 仅当用户明确要求“发布 Release / GitHub Release”时，才同步到 GitHub。用户单独明确要求提交或推送时，按其具体指令执行。
- 默认使用 `scripts/release.py`，底层 `scripts/publish-update.py` 发布本地 APK，`scripts/publish-gateway-update.py` 将同一发布提交的节点包及 APK 上传 VPS；先发布以 SHA-256 命名的文件，再原子更新清单。不覆盖同 versionCode 的不同内容，不降级。VPS 保留最新 3 版及 24 小时下载缓冲，更新文件限额 512 MiB，预留至少 1 GiB。
- 发布前递增 Android versionName / versionCode，按下方验证约定运行相关回归测试并构建发布 APK，确认签名与上一版一致。需要服务端改动时更新主节点并验证 HTTP 清单与 APK；不重启 Codex 原生运行时。
- GitHub 发布只在显式授权时使用 `--github-release`。发布说明标明真机/模拟器验证范围。

# 验证与效率约定

- 按本次改动选择最小且有效的验证范围。普通修改或提交不自动执行发布流程、全量测试或全量 lint。
- Android 逻辑改动运行相关单元测试及必要编译；服务端改动运行受影响的回归测试；仅文档改动检查内容和 diff 即可。发布时构建最终 APK，并保留签名、版本、更新清单及 APK 完整性校验。
- 默认开发、提交和发布流程不运行 lint；版本号递增也不触发 lint。仅当用户明确要求，或已发现需要 lint 定位的具体静态检查/兼容性问题时，按需运行适用模块/变体，并说明原因。不因修改 Manifest、资源、依赖或构建文件就自动执行全量 lint。
- 同一份代码已通过的检查复用结果；仅在相关代码、依赖或配置变化，或发现新问题时重跑。扩大验证范围应有具体原因，不为等待期间填充工作而增加检查。
- 启动耗时检查前确认是否已有可复用的同类检查，避免同一工作区重复并行构建。检查明显超出本次需要时停止自己启动的任务，不影响其他任务。
- 提交或交付时简要注明实际完成的检查、未完成的检查及真机/模拟器验证范围，不将未运行或中断的检查表述为通过。

# 设备管理入口

- 远程操作前先读取 `/Users/ethan/Documents/系统管理/家庭局域网设备清单.md` 和对应设备详情；不要重复向用户询问已有连接信息。
- 当前 VPS：`root@134.175.156.219`，SSH 密钥 `/Users/ethan/Documents/QiGuangBot.pem`，使用 `-o IdentitiesOnly=yes`。详情见 `/Users/ethan/Documents/系统管理/设备详情/网络与路由.md`。
- 实时磁盘用量写入本项目验证记录，不写入设备身份清单；只清理 Rivo 自己的可回收更新文件，不清理其他服务。
