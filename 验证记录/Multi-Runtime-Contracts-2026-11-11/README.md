# 第一批多运行时契约验证

日期：2026-11-11（Asia/Shanghai）。分支：`codex/dsh-runtime-integration`，延续此前 DSH 接入验证，未提交、未发布。目标是统一输入回执与能力声明，保留 APP 的现有聊天和审批组件。

## 实现范围

- 新输入回执区分 `input-accepted` 与 `execution-started`，绑定 submissionId 和 threadId；只有真实执行存在才提供 executionId。Codex 保留 turnId，DSH 保留旧兼容字段。别名冲突、会话不匹配和未知 kind 均拒绝，旧持久回执保留兼容解码。
- 提交存储调用 adapter 的 `recover_submission(record)`，不判断产品、不扫描原生消息结构。Codex clientId 分页查证与 DSH requestId 查证归适配器；未知结果不自动重发。
- 节点与会话统一声明 supported、available、reasonCode。APP 和服务端按同一份操作声明收口，未知协议/操作拒绝；旧节点没有声明时保留兼容。未接受的新请求受当前能力约束；已接受的重复提交始终返回原回执。
- 本批没有重写时间线、原生历史或所有路由内部依赖。Claude Code 仅完成[官方边界调查](../../docs/claude-code-integration-boundaries.md)，没有接入。

## 自动化回归

受影响 Python 回归累计 214 项通过，按代码变化增量复测，没有运行全量服务端测试。

| 范围 | 数量 | 结果 |
| --- | ---: | --- |
| test_receipts | 9 | 通过；含无 turn 第三后端、重启核对、未知不重发 |
| test_submissions / test_submission_recovery | 18 + 37 | 通过 |
| test_codex / test_queue | 45 + 11 | 通过 |
| test_dsh | 20 | 通过 |
| test_api | 64 | 通过 |
| test_runtime_capabilities | 10 | 最终代码通过；含未映射操作、组合操作、旧回执重放、实时审批与持久历史收敛 |

Android 定向单测累计 52 项通过：SubmissionCoordinatorTest 18、ConversationStoreTest 11、NativeFormsTest 9、RuntimeContractTest 5、NodeActivityTest 5、NodeActivitySyncTest 4。协议、身份和活动状态规则修正后仅重跑受影响类。debug / androidTest APK 构建成功，未运行 lint。

## 真实运行时与模拟器

使用本机 `emulator-5554`，没有清除已有 APP 配对。Codex 独立 Relay 为 `127.0.0.1:18786`、workspace `workspace/multi-runtime-validation/codex-relay`；DSH 为 `127.0.0.1:18785`、workspace `workspace/dsh-validation/relay`。主节点 `18765` 未替换，原生运行时未重启。

DSH 已安装版本与官方更新源均为 `0.2.2-rc.1`，见 [连接检查](dsh-connectivity.json)。本轮继续连接既有 DSH Host。

| 验证 | 结果与证据 |
| --- | --- |
| Codex HTTP 配对、创建、输入、回执、重复提交、历史读回 | [通过](codex-http-validation.json)，同 ID 只有一条原生输入 |
| DSH HTTP 中立输入回执、重复提交、历史读回 | [通过](dsh-http-validation.json)，不要求编造 turnId |
| Codex APP 新增节点 | `RuntimeLiveTest.addNodeFromSettings` 通过，16.407 秒，沿用手动配对界面 |
| Codex APP 聊天与断网恢复 | 最终代码通过，22.186 秒；关闭 Wi-Fi/移动网络进入 stale，恢复后消息身份与正文一致且活动状态 idle；[截图](codex-chat-recovered.png) |
| DSH APP 聊天与断网恢复 | 最终 APK 与最终节点代码通过，16.793 秒；恢复后消息身份与正文一致且活动状态 idle；[截图](dsh-chat-recovered.png) |
| DSH APP 真实原生审批 | 通过，14.086 秒；单次允许后文件内容为 `RIVO_DSH_APPROVED`，最终回复收到 |
| Codex APP 真实原生审批 | 修复后通过，19.401 秒；单次允许后文件内容为 `RIVO_CODEX_APPROVED`，最终 idle、无待审批；[报告](codex-approval-fixed-validation.json) |
| 保留 DSH 配对并切回 | `RuntimeLiveTest.selectSavedNode` 通过，7.032 秒 |

聊天和审批复用了 `DshLiveTest` 的交互步骤，因此 Codex 截图和测试回复中也出现 `RIVO_DSH_*` 标记；它只是精确比对字符串，不表示连接了 DSH。两种运行时由独立节点、会话 ID 和原生回执确认。本批 DSH 复用上一轮真实摄像头扫码所得配对，未重复摄像头测试；[原始扫码证据](../DSH-Integration-2026-11-11/README.md)仍适用。

DSH 归档截图由最终仪器测试直接采集；Codex 截图在测试通过后通过现有通知入口重新打开同一会话、关闭更新提示后采集，不增加原生输入。断网、恢复、消息一致性和 idle 状态由仪器测试断言；截图用于人工检查最终界面。没有安装更新提示中的正式版本，避免覆盖验证 APK。

首次 Codex 审批发现新能力逻辑把 `converging / historyReady:true` 误判为完全不可操作。仅精确中断该测试执行后修复并重启独立 Relay；新会话重新验证通过。原始失败与取消记录见 [缺陷记录](codex-approval-validation.json)。现在区分持久历史收敛与实时控制：有效审批仍可回答；历史 unavailable、未核实控制或失效请求仍拒绝。

最终截图复核还发现聊天恢复早于活动状态确认，原实现可能一直忽略同版本活动快照。修复限定于经过失效代际校验的新 `/v1/activity` 轮询：同版本可以重新确认，旧版本和未授权同版详情回包仍拒绝。新增固定版本断线恢复回归，模拟器测试也改为等待 `activityStatus == idle` 后截图。DSH 显示名称由适配器提供，APP 保持无产品名称分支。

加强后的 Codex 恢复测试还复现了旧 GET 被 `resetTransport` 取消时，活动监听把它误认为协程结束而永久停止的问题。阻塞活动 GET 的定向测试先失败，随后按会话读取已有模式修复：只有协程本身被取消才退出；路由切换取消旧请求后保持未核验并继续读取。

## 边界与保留状态

- 未做物理手机、公网 VPS、所有 Codex 功能或 Claude Code 端到端验证。未发布、未创建 PR。
- 保留两个独立验证节点、测试会话和 APP 中的配对供复核。HTTP 验证凭据只存放在忽略的 workspace，权限 0600；归档 JSON 不含凭据、配对码或审批 key。
- 未支持的 DSH 功能继续关闭。恢复草稿里的旧模型/计划等选择保留，由服务端拒绝不支持的参数；尚未新增这类草稿的专门提示。
- [架构方案](../../docs/multi-runtime-architecture.md)明确本批已实现部分与后续时间线、可选服务迁移边界。
