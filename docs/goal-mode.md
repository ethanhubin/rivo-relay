# 目标模式会话

2026-11-07：Android 会话页支持原生持续目标。进入已创建的会话后可设置目标；已有目标显示正文、状态、Token 用量/预算和累计耗时，支持编辑并启用、暂停、继续及清除。目标独立于 Plan 模式；暂停/继续使用原生目标接口，停止按钮仍调用当前轮次的中断接口。清除目标不删除会话历史。

## 协议与同步

- 协议按本机 `codex-cli 0.159.2` 的 `app-server generate-json-schema --experimental` 输出核对：`thread/goal/get`、`thread/goal/set`、`thread/goal/clear`。
- 会话快照增加 `goal` 和 `goalSupported`。每次快照读取原生目标，不从聊天文字推测目标，也不把目标持久化为 Relay 自己的执行状态。Android 原有快照缓存保留展示数据，离线时禁用操作。
- `POST /v1/threads/{id}/goal` 使用现有鉴权和 submissionId 去重。operation 为 set/pause/resume/clear；set 接收 objective 和可选正整数 tokenBudget，省略预算不修改已有预算。暂停/恢复只设置 paused/active，不发送额外提示词、不建立手机端续跑循环。
- 未实现目标 RPC 的节点（JSON-RPC -32601）返回 `goalSupported=false`，保留普通聊天。其他原生错误仍报告，避免把读取故障显示为“没有目标”。旧 Relay 未返回字段时 Android 不显示控制入口。
- 原生 goal/updated、goal/cleared 事件沿现有 SSE 触发权威快照刷新。带 turnId 的目标事件不创建虚假的运行中轮次。
- 目标会话中间轮次完成不触发任务完成通知；目标完成、受阻或达到预算/额度限制才通知。原生提问与真实轮次失败仍保留通知。
- 使用外部 writer 的只读会话可以查看目标，不能从手机绕过持有者修改。继续或启用目标是否立即执行以及预算限制由原生运行时决定。

## 验证

- Python 完整测试：324 项通过，覆盖目标状态读取与清除、旧节点兼容、真实错误不吞掉、外部 writer 拒绝、事件不制造轮次、预算校验、HTTP 鉴权和 submission 去重。
- Android：assembleDebug、testDebugUnitTest、assembleDebugAndroidTest 通过。新增用例覆盖目标缓存解析与清除、64 位 Token 计数、旧服务兼容、各终止原因及跨轮次完成通知。
- 未对用户正在运行的目标会话执行设置、暂停或清除；未部署服务、安装到手机或发布版本。真实节点目标操作和设备 UI 尚需验收，测试替身不等同于真实原生运行时验证。

目标的产品语义参考 [OpenAI：Using Goals in Codex](https://developers.openai.com/cookbook/examples/codex/using_goals_in_codex)。
