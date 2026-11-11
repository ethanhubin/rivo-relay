# E2 会话接管恢复验证（2026-11-11）

目标会话：`01a1ca00-fa5c-7281-a721-018a5a4e7c54`，清理已合并分支和工作区。

## 现场证据

- 接管于北京时间 18:57 成功；新独立 writer 持有目标会话锁。
- writer 的 `thread/read` 为 `idle`，官方 `thread/turns/list` 的末轮为 `interrupted`；rollout 末轮只有 `task_started`，没有终止事件。
- Rivo 将“未结束的日志 + 写锁”用于纠正外部运行时的历史延迟，却也对接管后的空闲 writer 生效。结果把中断轮改回 `inProgress`，显示旧问答且阻塞一条 waiting 队列消息。
- 项目索引 cwd 为 MergeDemo worktree，旧 rollout 列表返回初始 MergeDemoReleaseTool 目录，projectId 为空。最新主线已采用原生索引目录；现场运行 versionCode 125，126 已暂存，未在本次强制升级。

## 现场恢复

通过既有 API 释放目标 session-writer，保留队列。0.159 后台 CLI 的隔离重建因 `thread_turns` 与现场表结构不一致而拒绝发布。使用 e2 已安装的桌面端 `0.167.0-alpha.3.2` 官方 CLI 和既有 NativeHistoryReprojector 完成同一会话的 orphan_turn 重建，保留 5 轮、1713 条日志记录，执行写锁、结构校验和备份流程。

备份位于 e2 `/Users/e2/.codex/rivo-history-repairs/`，前缀 `20261111T110610799491Z-01a1ca00-fa5c-7281-a721-018a5a4e7c54-before-official-reprojection`，包含 JSONL 和 SQLite。

恢复后 API 确认 `idle`、`activeTurnId=null`、`nativeSync.state=ready`、`canAnswerQuestions=false`。随后通过普通 resume 恢复执行，原队列项 `4c6dd937-2be2-4562-a66b-e0426a5196a7` 已成为 `running`，对应新轮次 `01a1ca49-c99d-76d0-bf08-3ca0d02f31d7`，无队列错误。未重发旧轮次，未重启桌面端或其他会话。临时诊断配对已撤销。

## 代码修复与边界

- 统一历史详情、活动列表的中断判定：官方末轮必须为同一轮 `interrupted`。持锁时必须由实际独立 writer 确认空闲；无 writer 时再次检查锁、日志指纹和轮次。元数据连接的空闲不能覆盖外部 writer 的运行证据。
- 已确认的中断可直接读取，不修改历史、不依赖不同 CLI 版本的表结构重建。其他轮次缺失、终态冲突和无法确认的状态仍沿用原有校验，不把它们假定为完成。
- 中断证据只在当前读取作用域、运行时 generation、会话与轮次内有效。旧的 `inProgress` 内存事件不能复活已确认的中断；新轮次和新终态仍可正常合并。
- 接管确认旧进程终止后立即清理受影响会话的旧问答、运行标记与缓存，再允许新 writer 接收请求。终止失败保留仍有效的问答；获取新 writer 失败也不会残留旧运行标记；不清理无关会话或新 writer 的问答。
- 新增实际 CodexService 与 ExecutionQueue 的组合测试，验证原排队指令只派发一次、不隐式继续或重发旧轮次。
- 新增项目回归：projectId 为空、索引 cwd 已移到 worktree 时归入当前项目，不回到初始 rollout 目录对应的项目或重复出现在“最近”。项目索引实现复用最新主线，本次未另改归属算法。

## 本地验证与范围

- 最终受影响回归共 257 项全部通过，无跳过：native_journal、native_convergence、handoff、questions、queue、history_read_optimization、bounded_history_pages、thread_catalog、activity、owned_writer、native_reprojection。
- 早期运行受沙箱禁止 ps 影响，进程测试曾失败或跳过；最终在允许读取测试进程的环境完成验证。中间发现的测试替身方法签名不兼容已解决，最终结果不包含失败项。
- `git diff --check` 通过。

前一轮真实节点验证覆盖 API、写锁、原生历史、恢复与队列派发；本轮扩展边界逻辑仅作本地回归，未部署最终代码、未操作手机或模拟器 UI。修复分支为 `codex/fix-takeover-session-state`；本次代码交付不包含部署或发布，未构建 APK、未运行 lint。
