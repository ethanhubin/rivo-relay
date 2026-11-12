# GitHub PR 监听

Rivo 的 Android App 可在“设置 → GitHub PR 监听”为当前配对节点添加 `owner/repo` 和 PR **目标分支**（GitHub 的 base branch）。节点约每 5 分钟查询一次该分支的开放 PR，首次检查只建立基线；之后对新出现且已可审核的 PR、草稿转为可审核的 PR、重新打开的 PR 生成提醒。这里的“需审核”指开放且非草稿的 PR，不筛选 GitHub 的指定 Reviewer。App 中可查看待审核列表、打开 GitHub，或选择本地项目后新建带 PR 链接的 Codex 审核草稿。Codex 审核只在 Rivo 会话中反馈，不自动向 GitHub 提交 Review。私有 PR 的 Codex 审核还要求该节点上的 Codex/GitHub CLI 对仓库有读取权限；监听用的 GitHub App 令牌不会转交给 Codex。

App 进入前台后向节点登记前台状态，之后每约 2 分钟续期；退出前台时取消。只有该配对连接已配置仓库分支监听、已完成 GitHub 授权、前台状态有效且 App 的 SSE 连接在线时，节点才会立即安排检查并继续约每 30 秒检查。SSE 断线会暂停加速检查，重连后立即恢复；App 意外退出而未能取消时，前台登记最多保留 5 分钟。节点写入新提醒后在现有 SSE 连接上发送无仓库信息的唤醒事件，App 随即按原有授权接口领取提醒。GitHub 到节点仍是受控轮询；未授权的公开仓库以及 App 离线时维持约 5 分钟常规检查，以便离线补收。事件流中断时，重新连接会补读持久提醒。

## 私有仓库授权

节点管理员先创建一个 GitHub App，启用 **Device Flow** 和 **expiring user access tokens**，授予 Repository permissions → **Pull requests: Read-only** 与 Metadata: Read-only，并将 GitHub App 安装到要监听的私有仓库。将该 GitHub App 的 Client ID 设为节点服务环境变量 `RIVO_GITHUB_CLIENT_ID`，重启节点服务。无需在 App 中输入 PAT 或 GitHub App 私钥。每个配对连接在 App 点“连接 GitHub”，浏览器中输入设备码并批准，授权结果和可刷新的 user access token 只保存在该节点的 `workspace/data/github-watch.sqlite3`，文件权限为当前节点用户独占。GitHub App 安装范围和授权用户的仓库权限共同决定可读取哪些私有仓库。公开仓库可以不授权。

节点服务若由 launchd、systemd 或 Windows 计划任务管理，应把 `RIVO_GITHUB_CLIENT_ID` 配在该服务的环境中，而不是仅在交互终端设置。配置后，App 里的“连接 GitHub”入口才会出现。移除授权会删除节点保存的令牌；GitHub 侧还可在授权应用设置中撤销此 GitHub App 的访问。删除单条监听会删除其历史状态与未领取提醒。

## 离线提醒与时效

节点把提醒持久保存 30 天。Android App 使用系统持久化后台任务，最短约每 15 分钟拉取一次；App 启动或返回前台时还会立即检查。App 进程退出、手机短时离线后，在系统允许的下次后台任务中可以补收提醒；从系统设置中“强制停止”App 后，须重新启动 App 才会恢复后台任务。Android 13 及以上需要授予通知权限。省电策略、网络、设备重启和系统调度均可能延后提醒，**本实现不保证实时推送**。若产品要求几秒级、可靠唤醒关闭的 App，需要另行配置 Firebase Cloud Messaging 或同类推送通道，并在节点/VPS 侧添加推送发送链路。

后台任务使用已保存的节点配对凭据，逐节点读取 `/v1/github/alerts`；游标仅在本机通知创建后推进。节点必须仍能从手机网络访问，否则保持未领取提醒，待连接恢复后补收。节点停止运行期间不会扫描 GitHub；恢复后继续扫描。超过 30 天的未领取提醒会清理。

## 节点接口

这些接口均要求现有节点配对令牌；数据按 `connection_id` 隔离：

| 接口 | 用途 |
| --- | --- |
| `GET /v1/github/auth` | 查询当前连接的 GitHub 授权状态 |
| `POST /v1/github/auth/start`、`GET /v1/github/auth/flows/{id}` | 启动、轮询 GitHub App 设备授权 |
| `DELETE /v1/github/auth` | 移除节点保存的授权 |
| `GET/POST /v1/github/watches`、`DELETE /v1/github/watches/{id}` | 管理仓库分支监听，每个连接最多 20 条 |
| `GET /v1/github/alerts?after={id}` | 按 ID 增量获取提醒，每页最多 100 条 |
| `POST /v1/github/foreground` | App 报告前台状态并定期续期；节点自行安排加速检查 |
| `POST /v1/github/scan` | 兼容旧版 App 的限流提前检查 |

GitHub API 请求由节点发起；VPS 中转仅承担现有手机到节点的通道，不保存 GitHub 令牌，也不负责扫描。GitHub 授权流程参考 [GitHub App user access token 文档](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app) 与 [refresh token 文档](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens)。
