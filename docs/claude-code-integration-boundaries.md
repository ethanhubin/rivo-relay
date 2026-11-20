# Claude Code 接入边界

核实日期：2026-11-11。依据当日官方公开文档，属于设计调查，尚未接入验证。本机本轮 shell 的 `command -v claude` 未找到可执行文件，因此没有报告本机版本，也未启动 Claude 任务、登录或修改配置。

**存在官方连接已有运行时的机制，但不能把 SDK `resume` 一概解释成附着现有 CLI。** 对 Rivo 的统一聊天界面，需分别评估 Remote Control、后台会话终端附着、Channels 与自管 SDK 进程；它们提供的控制面不同。

## 接入路线

| 路线 | 官方确认 | 对 Rivo 的边界 |
| --- | --- | --- |
| Agent SDK / `claude -p` | SDK 默认启动并管理独立 CLI 子进程，经 stdio 通信；流式输入支持持续对话。 | 适合 Rivo 管理的运行时。调用 `query(resume=...)` 本身不证明连到了用户已经运行的交互进程。[Hosting](https://code.claude.com/docs/en/agent-sdk/hosting)、[Streaming Input](https://code.claude.com/docs/en/agent-sdk/streaming-vs-single-mode) |
| Remote Control | 在已有会话执行 `/remote-control`，可让官方网页、手机与终端同步操作，执行仍在本机。 | 已确认多端产品能力；本轮公开文档未提供供第三方 APP 替换官方客户端的通用连接协议，不能据此宣称 Rivo 已可直接接入。[Remote Control](https://code.claude.com/docs/en/remote-control) |
| Background sessions / `claude attach` | 后台会话可在终端附着、分离；现有交互会话可由用户 `/bg` 转入后台。 | 这是官方终端 UI 接口。运行中的后台会话通过 `--resume` 打开时，管道或 `--output-format json` 等条件会使其拒绝并退出，不是现成的 JSON 附着接口。[Agent view](https://code.claude.com/docs/en/agent-view)、[Running background session](https://code.claude.com/docs/en/sessions) |
| Channels | 自定义本地 MCP 子进程可向已打开的会话推送事件，并提供回复工具。 | 最接近“保留已有 CLI、增加 Rivo 桥”的官方扩展路径；仍为 research preview，不能等同完整会话镜像。自定义通道试验需 `--dangerously-load-development-channels`，不能承诺无准备附着任意既有进程。[Channels](https://code.claude.com/docs/en/channels)、[开发通道要求](https://code.claude.com/docs/en/channels-reference) |

Python SDK 确有自定义 `Transport` 扩展，但官方将它标为可能变化的低层内部 API；它没有为任意已运行终端提供发现地址或接管契约。不能用“可自定义传输”补出尚未确认的 attach 能力。[Python Transport](https://code.claude.com/docs/en/agent-sdk/python)

## 会话发现、恢复与控制权

- 历史发现与消息读取有 `listSessions/getSessionMessages`、Python `list_sessions/get_session_messages`。它们读取保存的会话，不是正在执行或等待审批的证明。[Python 会话 API](https://code.claude.com/docs/en/agent-sdk/python)
- `continue` 选择最近会话；`resume` 指定会话；`fork` 复制历史到新 ID。历史持久化不等于文件系统快照，也不等于仍存活的工具执行。旧实验性 TypeScript V2 `createSession()` 已于 SDK 0.3.142 移除，不应以旧示例设计新接口。[SDK sessions](https://code.claude.com/docs/en/agent-sdk/sessions)
- 活跃状态应优先调查受支持的 `claude agents --json`：区分 `interactive/background`，有条件提供 `pid/status/waitingFor/sessionId`；后台短 ID 与会话 UUID 是不同标识。不要把 `~/.claude/jobs/*/state.json` 当稳定接口。[Agent view JSON](https://code.claude.com/docs/en/agent-view)
- 官方普通会话文档提醒，两终端同时恢复同一会话可能交错写入；后台管理器又会拒绝启动已被其他进程占用的历史。这些是不同入口的行为，不能推导出全局自动互斥保证。Rivo 应有自己的单会话 owner/lease，不能为获取控制权静默启动第二个 `resume`。[会话分支](https://code.claude.com/docs/en/sessions)、[后台占用冲突](https://code.claude.com/docs/en/agent-view)

## 输入接受证据与重复提交

CLI `--replay-user-messages` 明确用于将 stdin 用户消息回显到 stdout 作确认，要求双向 `stream-json`；写管道成功与收到该回显是两级证据。[CLI reference](https://code.claude.com/docs/en/cli-reference)

SDK 输入可携带 `uuid`，回放消息有 UUID，结果还可能带 `user_message_uuid/user_message_uuids`。这些可用于关联正证据；所查文档没有承诺“重复 UUID 自动去重”或回显已持久落盘。结果的会话 ID 也不能独自证明某一条输入已接受。[SDK message types](https://code.claude.com/docs/en/agent-sdk/typescript)

Channels 的限制更明确：notification 没有 ACK，`await` 仅代表写入传输；未启用通道或被组织策略禁止时可能静默丢弃。回复工具可以反馈处理结果，但不是原生入队收据。[Channel delivery](https://code.claude.com/docs/en/channels-reference)

Rivo 设计建议：先持久化自己的 submission ID，再发送；receipt 保留 `runtime/sessionId/submissionId` 与可选原生消息 ID、证据类型。区分桥已接收、原生输入已确认、结果已观察，不虚构 `turnId`。发送后失联保持 `unknown`；只有匹配标识的正证据才能收敛，不因历史暂未出现就自动重发。这是适配策略，不是 Claude 的幂等承诺。

## 审批、用户问答与断线

SDK 的 `canUseTool` 可暂停工具审批，也负责 `AskUserQuestion`。后者应独立呈现问题、选项和自由文本，并返回原始 `questions` 与 `answers`，不能压成允许/拒绝。自动许可规则可能在回调前放行；回调可长期等待，进程不能保留时官方另提供 `PreToolUse` 的 defer 路径。TypeScript 有取消 signal，Python 对应 signal 当前仅预留。[Handle approvals and user input](https://code.claude.com/docs/en/agent-sdk/user-input)

SDK `reinitialize()` 支持传输恢复后重发未决审批，应按 request ID 幂等处理；CLI v2.1.268 起成功 initialize 包装始终提供未决审批数组，更老版本字段缺失不能解释为零审批。该机制恢复的是仍运行进程的控制状态，不保证已退出进程的回调存活。[Query methods](https://code.claude.com/docs/en/agent-sdk/typescript)

Channels 可转发工具审批，终端与远端谁先回答谁生效，迟到答案只对仍开放的 request ID 有效；项目信任、MCP 同意弹窗不在其中。本轮未找到它对 `AskUserQuestion`、审批完成通知或重连后待审批快照的完整契约，不能直接宣告这些能力可用。[Permission relay](https://code.claude.com/docs/en/channels-reference)

输出增量有 SDK `StreamEvent`，完整消息与结果仍另行到达。所查流式文档未定义供第三方断线后按持久 cursor 重放所有增量的接口；会话历史恢复也不是 token 流重放。[Streaming output](https://code.claude.com/docs/en/agent-sdk/streaming-output)

Rivo 应持久化自身事件序号，APP 重连先取权威快照再接续事件；原生进程换代则清空旧的可操作审批，直到重新确认。只有 APP 网络断开时，应让本地 owner 继续运行。Remote Control 自身有自动重连，但这是官方产品能力，不能继承为 Rivo 的恢复保证。[Remote Control connection](https://code.claude.com/docs/en/remote-control)

中断要探测 `interrupt_receipt_v1`：收据的 `still_queued` 列出带 UUID 的待处理输入，不能因此重发；空列表也不证明没有未带 UUID 的输入。清空队列是另一个 `interrupt_cancel_queued_v1` 能力；SDK `interrupt()` 不自动请求清空。不能把“停止当前响应”显示成“所有排队消息均已取消”。[Interrupt receipt](https://code.claude.com/docs/en/agent-sdk/typescript)

## 认证、部署及中立能力模型

Remote Control 要求符合条件的 claude.ai 订阅与登录，API key 不适用；数据经 Anthropic 服务同步，并非本地扫码配对协议。[Remote Control requirements](https://code.claude.com/docs/en/remote-control)

SDK 官方部署路径使用 API key 或受支持的云提供商认证；Quickstart 明确，未经批准，第三方产品不能提供 claude.ai 登录或订阅额度。Channels 自身支持 claude.ai 或 Console API key，但有组织策略与预览限制。Rivo 配对只授予本地桥访问，不能替代上述授权或复制登录凭据。[SDK authentication](https://code.claude.com/docs/en/agent-sdk/quickstart)、[Channels requirements](https://code.claude.com/docs/en/channels)

建议把以下能力拆开，由具体适配器确认后上报；以下名称是设计建议，不是已实现字段：

| 能力 | 应表达的事实 |
| --- | --- |
| `sessionMode` | `managed`、`existing-channel` 或 `terminal-attach`，不能用一个 `resume=true` 混合 |
| `historyRead` / `liveActivity` / `eventReplay` | 历史读取、当前运行状态和可恢复事件流各自有独立证据 |
| `inputAcknowledgement` / `inputDeduplication` | 有确认不等于有幂等；证据缺失保留未知状态 |
| `toolApproval` / `userQuestion` / `approvalReplay` | 工具许可、结构化问答、重连后审批恢复分别探测 |
| `interruptCurrent` / `cancelQueued` | 分别对应当前执行和排队输入；附带原生能力来源 |

已有 CLI 路线可优先做 Channels 的独立验证，但须先验证输入证据、历史补齐、审批消失及断线恢复缺口。自管 SDK 路线更直接控制生命周期，产品中应明确其运行时归 Rivo 管理。本调查没有证明任一 Claude 路线已达到当前 DSH 桥的完整闭环，也没有运行相关端到端测试。
