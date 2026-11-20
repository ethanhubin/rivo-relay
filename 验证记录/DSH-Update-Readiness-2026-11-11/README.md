# 主线同步与 DSH 更新前空闲核验

2026-11-11，工作树 `multi-runtime-adapters`，分支 `codex/multi-runtime-adapters`。

## 范围

- 合入 `origin/main` 的 7 个提交，最新为 `bfe10fe`，合并提交 `1fad0fd`。接管、附件传输、耗时展示和监控的自动合并处已复核。
- 为 DSH 补齐现有 `idle_for_update()`，沿用原更新门禁，不增加通用服务注册或生命周期体系，不修改 APP 交互。
- 新桥 `updateStatus` 从原生列表确认当前 Agent 身份，再被动读取其执行、队列和待回应状态；独立复查原生全局待回应及桥审批。未知字段、错误、变化或断连均不放行。Python 侧核验代际、控制版本和游标，保留本地未决提交的更新阻断。
- 全量读取冷会话的初版真实探测返回不可确认。根据官方 `agentAvailable` 的当前 Agent 注册表语义，改为仅读取现存 Agent；不激活、不迁移冷历史。最终在 93 个会话下确认空闲，单次探测约 0.039 秒。这是一次观察，不保证后续桌面不会开始任务。

## DSH 升级

官方源已由 `0.2.2-rc.1` 更新至 `0.2.2-rc.2`，本机已正常退出后升级。更新前原生列表无运行任务，活跃 Agent 的已知输入队列为空；未强制终止任务。官方 ZIP 长度 `362753775` 字节，SHA-512 与 feed 一致；新旧应用签名团队均为 `NAN929V4UM`，新应用签名校验通过并包含 notarization ticket。旧应用与本轮插件/profile 备份保留在本机 `/tmp/rivo-dsh-rc2`。

新插件通过既有 profile 行的 `config.revision: 4 → 5` 热加载，确认代际变化。后续只重新加载独立 DSH Relay（18785），保留既有配对；主节点、Codex 原生运行时未重启。最终版本和连接检查见 [version.json](version.json)。

## 验证

- 主线同步：服务端受影响测试 222 项通过；Android NodeTransport 12、UpdatePromptNotes 2、NativeMessageTiming 11、NativeHistoricalTiming 4，共 29 项通过，主代码与 androidTest Kotlin 编译通过。
- DSH 修复：新增回归先复现空闲仍取不到许可；修复后 `test_dsh_update` 9、`test_dsh` 20、`test_runtime_state_adapters` 16、原更新 Gate/NativeIdle/Lease 7，共 52 项通过。
- 桥：20 项 Node 测试通过，包括冷历史不参与读取、忙碌、排队、待回应、未知状态、未观察会话的输入变化以及读取期间关闭。
- 真实 rc.2：独立临时 Relay 经 HTTP 配对，空闲时获得更新许可；单次输入触发原生 Bash 审批时被拒绝更新；精确核对测试命令后单次批准，临时文件和回复标记一致，重复提交仍为一个原生输入，旧审批身份被拒绝。完成后再次获得许可。模拟适配器失联标记并等待事件轮询重新同步，结果与输入身份保持一致。详见 [live-http.json](live-http.json)。未调用更新停止端点或发布 Rivo。

本轮未运行 lint、全量测试、真机或模拟器完整闭环；模拟器当时未启动，既有 APP 验证仍对应此前 rc.1。2 秒为协作读取预算与单次 I/O 超时，迟到结果不会授权更新，不表述为严格墙钟取消。
