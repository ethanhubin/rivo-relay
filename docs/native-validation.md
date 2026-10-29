# Codex 原生接入验证

记录日期：2026-10-29。以下是**原生协议验证**，不是 Desktop UI 验收。两个原生客户端共享同一 daemon，不能替代用户在 Desktop 中打开同一会话并确认双向行为。

## 环境与连接证据

| 项目 | 实际结果 |
| --- | --- |
| 当前 Desktop | ChatGPT/Codex Desktop `26.1023.50603`，build `22391` |
| 当前电脑 CLI | `0.159.2` |
| 当前电脑 daemon | 原有 PID `906`；Unix WebSocket 可连接；探测时没有已加载线程 |
| 当前电脑 Desktop 本地 app-server | PID `40268`，独立 stdio；不能把上述本地 daemon 当作它 |
| 远端验证节点 | 已存在的 `e2-mac-mini` SSH 节点；Codex `0.159.0` |
| 远端原有 daemon | `server/diagnostics.process.id = 92543`，与远端进程列表一致 |
| 原生 socket | `/Users/e2/.codex/app-server-control/app-server-control.sock` |
| Relay 使用的临时转发 | `/tmp/rivo-e2-native.sock` → 上述原生 socket |

Desktop 当前已有至 `e2-mac-mini` 的 SSH `app-server proxy` 连接。只读检查当前安装包显示该 transport 使用同一默认 daemon socket。协议探测连接到 PID 92543，未启动另一个执行 runtime。原有 Desktop、daemon、SSH proxy 均未重启、停止或修改。

本次仅另建一个可撤销的 SSH socket 转发，供开发服务和模拟器访问；开发结束时关闭这个转发即可。不能停止原有 daemon 或 Desktop proxy。

```sh
ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -N \
  -o StreamLocalBindUnlink=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=4 \
  -L /tmp/rivo-e2-native.sock:/Users/e2/.codex/app-server-control/app-server-control.sock \
  e2-mac-mini
```

QiGuang bridge 目录不存在，当前 Desktop bundle 也未发现 QiGuang bridge 宿主。Desktop 自身 `~/.codex/ipc/ipc.sock` 的只读 initialize 成功，源码包含 thread follower 协调接口；没有将其作为已完成的产品入口，也没有用它绕过 Desktop UI 自动化限制。

当前安装包确实包含 `CODEX_APP_SERVER_USE_LOCAL_DAEMON=1` 条件：只在非 Windows、本地 host、没有额外启动参数、未强制 CLI、未配置 CLI override/custom command，且 daemon version 检查成功时选择本地 daemon WebSocket。该条件影响连接创建；本次未设置该变量、未重启当前 Desktop。

## 隔离范围

- 项目路径：`/Users/e2/Documents/Rivo-Relay-Validation-20261029`
- 原生 project ID：`01a18395-9695-7313-bebc-12a2516c8126`
- 主要验证 thread ID：`01a1839a-1d27-7801-871d-d9c764bae4c6`
- 不读取、续接、回答或修改用户其他会话；不修改全局配置或生产权限。
- 测试历史与项目保留供 Desktop 人工确认。待确认后可由用户决定清理。

## 实际通过的原生验证

| 检查 | 证据与结果 |
| --- | --- |
| 项目与无会话项目 | `project/create` 返回原生 project ID；新项目 `threadCount=0`；`project/list` 返回 7 个远端项目 |
| 项目范围 | `thread/list` 的 `projectId` 与 `cwd` 筛选实际支持；隔离项目只返回同 cwd/project 的测试线程 |
| 模型目录 | `model/list` 返回动态目录，包含 `gpt-6.1-sol` 等；未使用 App 硬编码模型清单 |
| 创建与首轮 | native `thread/start` → `turn/start`；首轮 `01a1839a-2b05-7ee3-b9c6-f30e5cc448e7` 最终回复 `READY` |
| 消息与历史 | 实际收到 `turn/started`、`item/agentMessage/delta`、`item/completed`、`turn/completed`；分页历史返回相同 turn/item 身份 |
| 双客户端订阅 | A、B 独立 WebSocket 连接均加入同一原生 thread；B 收到 A 发起的 `turn/started`，没有另启 runtime |
| 引导与停止 | B 对 A 的 turn `01a1839d-1107-7091-adb6-652d8c3e1d0d` 成功 `turn/steer` 和 `turn/interrupt`；原生历史终态为 `interrupted` |
| 原生审批 | 隔离线程临时使用 `untrusted/readOnly` 触发 `item/commandExecution/requestApproval`，A/B 均收到；A 明确拒绝后 B 收到 `serverRequest/resolved`；B 再次回答得到 `interaction_expired` |
| 原生提问 | 隔离线程临时 Plan mode 触发 `item/tool/requestUserInput`；question ID 为 `validation_result`；B 回答 `PASS`，A 历史读取最终 `PASS` |
| 重连与设置 | 关闭测试连接后重新 resume，原生 thread/turn ID 与模型、effort、cwd、approval、sandbox 保持一致 |
| 交互身份 | 请求 key 绑定 runtime 连接 generation 和原生 request ID；断连、另一客户端回答或对应 turn 结束后不再次发送旧响应 |
| 资源释放 | 定向 transport 测试确认 close 后 native reader、notification dispatcher 与测试 WebSocket server worker 均结束；close 不调用 daemon shutdown 或 turn interrupt |

首轮直接继承的原生设置为：`model=gpt-6-astra`、`reasoningEffort=medium`、`approvalPolicy=on-request`、`sandbox.type=dangerFullAccess`，cwd 为隔离目录。产品 adapter 的 start/resume/send **没有传入权限、sandbox 或审批覆盖**；这里的 full access 是上游返回的事实，不是 Relay 的默认策略。

审批测试后恢复了隔离线程原 approval/sandbox 并读回核对。提问测试后恢复了 `mode=default`、原模型与 effort；原生 `thread/settings/update` 将 `developer_instructions=null` 展开为内建 Default 说明，因此该对象不是逐字相等。模型、effort、cwd、审批和 sandbox 读回一致。没有改动其他线程或全局设置。

## 已处理的协议边界

- 新建但未执行首轮的 thread 若先 resume，会返回 `missing source rollout`。Adapter 保留当前创建连接及 `thread/start` 原生元数据，空会话 GET 返回该元数据和空 turns；首轮发送后改走原生历史。没有伪造消息。若首轮回执丢失，不继续将它当作空会话。
- 原生明确 JSON-RPC 拒绝标记 `definite=True`；发送中断、超时和缺少原生 turn ID 标记为结果不确定，交由提交记录核对，不自动重放。
- 所有写操作只返回身份回执，不向提交记录写入整段 turn、消息或设置。
- 临时增量只在内存，已完成历史由原生分页读取。账户与 remote-control 个人元数据不转发到聊天 SSE。
- 同 cwd 的线程若已具有其他原生 projectId，不归入当前项目；只有未归属项目的旧线程才使用精确根 cwd 补充。回归测试同时覆盖项目计数一致与原生 projectId 绑定的工作树线程，不猜测或改写工作树归属。

## 可重复测试与样例

```sh
PYTHONPATH=server .venv/bin/python -m unittest discover -s server/tests -p test_codex.py -v
```

测试位于 `server/tests/test_codex.py`。样例 `server/tests/fixtures/native-0.159.0.json` 包含隔离线程真实 `thread/turns/list` 响应、原生 ID 与设置摘要。`observedRequestShapes` 仅记录实测审批/提问字段形状与回答结构，不伪称完整原始通知抓包。

历史适配器迁自 `QiGuang-Next/qiguang/server/qiguang_codex_native_history.py`；源仓库 HEAD 为 `f31333c248b862827a0179260ce5d9c571f441be`，采用文件 blob 为 `6b8df7f3d2b0b7f4f0b1ce278d854befd707eaa9`。迁移保留严格分页与 ID 校验，未迁 sidecar、队列或 writer 接管。

## 尚未完成的验收

Desktop UI 自动化工具明确禁止操作当前应用，因此没有自动点击、深链、AppleScript 或注入来绕过该限制。仍需用户在 Desktop 的 `e2-mac-mini` 节点打开隔离项目/线程，确认可见上述原生消息，并完成 Desktop 发起 → App 接续、App 发起 → Desktop 接续的人工检查。

当前 `status.desktopShared=null`、`desktopVerification=requires_desktop_confirmation` 是刻意保留的真实状态。不得仅凭相同 CODEX_HOME、daemon 可连接、双原生客户端通过或构建成功，把 Desktop 完整延续或阶段 1 UI 验收标为完成。

## 结构化输入与节点文件补充验证

同一个既有远端 daemon（PID 92543）已实测：

- `fs/getMetadata({path})`、`fs/readFile({path})` 与 `fs/readDirectory({path})`。目录接口名称是 `readDirectory`，不是 `listDirectory`。
- 在隔离目录通过授权测试 shell 创建 `preview.txt`、`red-square.png` 和 `skills/native-wire-check/SKILL.md`；没有操作用户文件或安装全局 skill。
- 原生文件读取返回 53 字节 UTF-8 测试文本和 168 字节 PNG。服务只经原生 RPC 读取，没有把远端路径交给 Relay 的本地文件读取函数。
- 单轮同时提交 `text`、`image.url=data:image/png;base64,...`、`localImage.path`、`mention{name,path}`、`skill{name,path}`；turn `01a183aa-326b-7372-8e2a-8264cb9c8cc4` 完成并回复 `RED`。原生历史保留这五种输入类型；模型、effort、cwd、approval、sandbox 前后相同。

捕获样例为 `server/tests/fixtures/native-structured-input-0.159.0.json`。它包含本次实际输入数组、原生 turn 和测试文件读取结果。`image.fileId`、audio/localAudio 只核对了当前 schema，尚未做实际执行验证，不在上述通过项中。

文件预览返回 `{path,size,mimeType,encoding:"utf-8",text}` 或 `{path,size,mimeType,encoding:"base64",dataBase64}`，限制解码后 4 MiB。0.159.x 原生 metadata 没有文件大小，readFile 也没有 offset/limit，因此当前是响应大小限制，不是原生范围读取；超限明确返回 `file_too_large`/413。WebSocket 另有消息上限。目录返回有界直接子项列表，不递归扫描。

这些 FS 接口是 Desktop 的节点级文件操作，schema 中没有 threadId；它们使用同一 daemon 的主机访问权限，不能描述成受某个 turn 的 sandbox 限制。Relay 未增加项目白名单，也未改变原生权限。上游不支持时返回 `native_fs_unsupported`/501；实测版本 parser 的 unknown-method `-32600` 和标准 `-32601` 均会明确识别，不能假装读到了文件。

HTTP 层建议在现有带 submission ID 的发送/引导请求中传原生 `input` 数组；有数组时正文是其中独立的 `text` 项，不再拼接附件包装文字。图片可直接传 data URL；`localImage`、`mention` 和 `skill` 路径都是节点上的路径，Android 的 `content://` URI 不能当作节点路径发送。图片请求的 HTTP 大小限制需与 base64 膨胀后的大小一致，不能只保留文字消息的限额。

## MCP 交互协议复核

在既有远端节点运行 `/opt/homebrew/bin/codex app-server generate-ts --experimental --out /tmp/rivo-codex-schema-01590`，仅生成协议文件，未启动 app-server。该 binary 实报 `codex-cli 0.159.0`。以下关键文件与本机 0.159.2 生成文件逐字一致：

| 原生生成文件 | 0.159.0 SHA-256 |
| --- | --- |
| `v2/McpServerElicitationRequestParams.ts` | `16ab3fb70ebd4b68522432f718cc49a6e43cd78e03ee7734e2bdb7c2a101df4c` |
| `v2/McpServerElicitationRequestResponse.ts` | `391007144a7dac5cdc57a065de5aaab130d4f7a45b5c9d7fae801c674498a3ee` |
| `v2/UserVerificationProof.ts` | `b775e7879621e77dbcd55e125b5959c7f074bdd1cb827fef89407d9e0ce4a967` |
| `ClientRequest.ts` | `f21e6307c8c2c7f2b5a32d434cdca238e3ec19b71a1f40b60f8c305289860436` |

`mcpServer/elicitation/request` 的公共字段为 `threadId`、可空 `turnId`、`serverName` 和可空 `_meta`。`turnId=null` 仍是有效的原生请求，不得用自行生成的 turn ID 替代。具体分支：

| mode | 其余 params | 成功回答的 content |
| --- | --- | --- |
| `form` | `message`, `requestedSchema: McpElicitationSchema` | 按 schema 收集的对象 |
| `openai/form` / `openaiForm` | `message`, `requestedSchema: JsonValue` | 依扩展 schema；不是固定的普通表单类型 |
| `url` | `message`, `url`, `elicitationId` | 可空；打开链接本身不能证明验证已完成 |
| `openai/userVerification` | `title`, `description`, `challenge` | 原生 `UserVerificationProof`，不能用确认布尔值代替 |

统一响应为 `{action:"accept"|"decline"|"cancel",content:JsonValue|null,_meta:JsonValue|null}`。拒绝/取消使用空 content；收到 `serverRequest/resolved` 后清除请求。[OpenAI 官方 app-server 文档](https://learn.chatgpt.com/docs/app-server)说明扩展表单需要客户端能力声明；0.159.0 schema 进一步注明新客户端使用 `capabilities.extensions["openai/form"]`，`mcpServerOpenaiFormElicitation` 是旧声明。当前 Relay 没有声明该扩展能力，不把未知扩展表单记为已支持。

普通 `form` 的根为扁平 object，支持字符串及长度/日期等格式、数字或整数及上下界、布尔、字符串单选和字符串多选。单选可用 `enum`/`enumNames` 或 `oneOf:[{const,title}]`；多选使用 `items.enum` 或 `items.anyOf:[{const,title}]`，可带 `minItems/maxItems`。这组类型可用 Compose 原生控件可靠呈现。扩展表单的 schema 是任意 JSON，不能只检查 field.type 就宣称支持：嵌套、引用、条件或未知校验约束应明确交回 Desktop，除非实现了相应语义。本次只核对协议和实现，不伪称实际触发了 MCP 扩展表单。

身份验证的原生 signing RPC 是 `userVerification/verify({challenge,title,description}) -> {proof:{credentialId,signature}}`。challenge 是不带填充的 base64url，解码后 1–4096 bytes；signature 是针对准确 challenge 的 P-256/SHA-256 DER ECDSA 签名，再以不带填充的 base64url 编码。Desktop 的只读源码中，最终 elicitation `accept.content` 为此 proof；签名 provider 的 `isAvailable` 限于本地 stdio，远端请求与本地 provider 的协作还核对相同用户主体及请求是否仍有效。

本次只读 `userVerification/status({})` 返回 `credentialMissing`、`credentialPresent=false`。没有调用 enroll、verify、delete 或执行认证。Android 本机生物识别不自动拥有原生注册凭据，也不能生成可替代的 proof。当前可靠行为是展示原生 title/description、允许明确拒绝/取消，并保留请求等待 Desktop 完成；**手机完成该类身份验证仍未实现**，不添加假的“已验证”入口。

## Desktop 核心能力差异

此表区分已经保留的原生数据、手机当前操作入口和未完成验证；不把通用 JSON 展示或协议字段存在计为完整产品能力。

| 能力 | 当前实际支持 | 精确缺口 |
| --- | --- | --- |
| 项目与工作树 | 原生项目目录含空项目；按原生 projectId 或精确根 cwd 查会话，继续既有会话不覆盖 cwd | 原生 0.159.0 `ClientRequest` 没有专用 worktree 管理方法；新建当前选 roots[0]，没有新建/切换 worktree UI，也没有迁入旧工作树额外路径归属。具有原生 projectId 的既有工作树会话可被查到，但未实测独立工作树 |
| 文件与差异 | 原生 fileChange 的 path/diff 保留并可展开文本；文件引用可经节点 FS 预览 | `turn/diff/updated` 已转发但没有聚合差异快照/专门 UI；`gitDiffToRemote({cwd})->{sha,diff}` 仅核对 schema，未接入或执行；未实现文件回退/应用差异操作 |
| 工具结果 | commandExecution 输出、fileChange、imageView/imageGeneration 有基础呈现；MCP/dynamic/function 输出按原生 item ID 保留 | mcpToolCall、dynamicToolCall、functionCallOutput 仍用通用折叠 JSON；嵌入的 MCP App UI、富媒体和可交互工具结果未专门呈现，不能称所有工具结果完整等价 |
| 运行设置 | 继承原生模型、effort、cwd、审批、sandbox；手机可选择模型/effort并显示权限摘要 | 原生 `thread/settings/update` 还有 collaborationMode、serviceTier、cwd、permissions 等字段。手机未提供 Plan 模式、权限 profile 等编辑入口；未展示全部设置，不把继承等同于全部可编辑 |
| 原生交互 | 提问、命令/文件审批和首个回答者生效已有实测；普通表单/URL/权限请求已有实现及 schema 对照 | MCP 扩展表单和身份验证未实际闭环；未知请求明确保留，不能默认接受；仍需 Desktop UI 双向验收 |

## 临时转发恢复记录

模拟器验证期间原临时 SSH session 消失，远端既有 daemon 仍为 PID 92543。仅重新建立本次自己的 socket 转发，增加 SSH keepalive；此次恢复没有重启 Relay、Desktop、daemon 或其他用户进程。恢复后新原生连接的 diagnostics 仍为同 PID，项目读取返回 7 项，`preview.txt` 再读成功并含固定短语 `RIVO_NATIVE_FILE_PREVIEW`。服务读取入口会重新连接；原生断线期间不重放写操作。

全部模拟器验证结束、开发 Relay 停止后，仅对本次临时转发进程 PID 79183 发出 TERM；对应 session 正常退出，并删除残留的 `/tmp/rivo-e2-native.sock`。清理后复核 Desktop PID 36477、它原有的 `e2-mac-mini` SSH proxy PID 11369 均在运行；远端原有 daemon 仍为 PID 92543，原生 socket 仍存在。没有关闭原有连接或 daemon。隔离原生项目、线程、测试文件继续保留，供尚未完成的 Desktop 人工验收使用；当前临时转发已关闭，重新运行开发验证前需按上述命令建立它。

## 真机首轮状态提示的后续诊断

真机出现已收到回复但仍显示状态错误的报告后，只读核对对应 create/turn 提交记录均为 accepted；原生 metadata、分页历史和 resume 当前均成功。没有向用户会话发送测试内容。在隔离项目分别对有标题和无标题的新会话执行最小回复，共 194 次首轮期间快照读取，未复现原生 409。因此不声称已经证明首轮落盘是报错原因，也未加入将原生历史错误替换为空历史的兜底。

确认了一个可重复的服务端刷新循环：旧 get_session 每次调用 thread/resume，同一空闲线程连续 3 次读取收到 3 条 thread/goal/cleared。客户端根据通知再次读取，产生读取自身触发的刷新。修正为每个 connection generation 首次 resume 建立订阅，其后 `thread/read({threadId,includeTurns:false})` 读取元数据；新建未首轮会话继续使用准确 thread/start 元数据，已发送后历史读取失败仍明确返回错误。断连、generation 变化和线程关闭/卸载使订阅缓存失效。

原生设置事件的实际形状为 `thread/settings/updated({threadId,threadSettings})`，其中设置字段使用 `effort` 和 `sandboxPolicy`；resume 则使用 `reasoningEffort` 和 `sandbox`。适配器现按真实事件更新设置，避免为了读取设置反复 resume。隔离 thread `01a184c3-0dcc-71c3-a9fb-3780a230f74b` 验证结果为 3 次快照读取只有 1 次 resume、2 次 metadata read、1 条 goal/cleared。另一原生连接调整该隔离线程 effort，通知后快照正确更新且 resume 次数仍为 1；随后恢复原 effort，model/cwd/approval/sandbox 前后保持一致。

对应 15 项原生单测通过，新增覆盖重复快照不自激刷新、原生设置通知映射、重连/卸载重新订阅，以及首轮历史失败不伪装为空。此次适配器验证没有重启真机 Relay、SSH 转发或既有 daemon；正式服务由主流程在检查后统一载入。

另已核对分页协议：`backwardsCursor` 用于反转 sortDirection 并重新包含 anchor，不是“更早一页”的后备游标。0.159.0 对单轮 backwardsCursor 配 desc 的实际结果是重复返回该轮，并非 409。客户端应只使用 nextCursor 加载更早历史，不能把此实测外推成已复现原生冲突。
