# Rivo Relay HTTP API

更新：2026-10-29；对应 0.2.0 的 `server/rivo_relay/api.py`。运行、配对 CLI 与构建方式见[README](../README.md)，功能和边界见[功能补齐记录](feature-parity.md)。这是一层通向选定原生 Codex 节点的 API，不建立独立执行权限体系。

## 通用约定

- 正式连接使用 HTTPS。`GET /health`、`POST /v1/pair` 与持有临时文件票据的 `GET /v1/download-links/{ticket}` 无需 Bearer；其余接口必须携带一个 `Authorization: Bearer <token>`。
- POST/PATCH 使用 JSON object 和 `Content-Type: application/json`，提供单个 `Content-Length`；不支持 chunked request，JSON 请求上限 **64 MiB**，不接受 NaN/Infinity。文件上传另支持 `application/octet-stream`，见文件接口。
- 正常 JSON 响应为 HTTP 200，不使用 `ok=true` 外层包装；错误为 `{"error":{"code":"…","message":"…"}}`。客户端根据 code/status 判断，不依赖中文 message。
- URL 中的 ID、路径与 cursor 应正确编码。时间字段来自原生接口时保持原生值；Rivo 自有记录时间使用 Unix 秒，待办 `details.reminderAt` 明确使用 Unix **毫秒**。
- token、完整配对链接、正文和秘密回答不得写日志。连接令牌撤销后读写返回 401，已有 SSE 发 `revoked` 后退出。

## 健康、登录和目录

| 方法与路径 | 请求 | HTTP 200 响应 |
| --- | --- | --- |
| `GET /health` | 无 | `{"status":"ok"}`；仅说明 HTTP 活着，不代表原生已连通 |
| `POST /v1/pair` | `{code, deviceName}` | `{token, deviceId, node:{id,name}}` |
| `GET /v1/node` | Bearer | `{id,name,connected,capabilities,runtime}` |
| `GET /v1/projects` | Bearer | `{projects:[{id,name,path,roots,threadCount,outstandingWorkCount}]}`；包含无会话项目 |
| `POST /v1/projects` | `{submissionId,path,folderName?}`，path 是节点绝对路径 | 提交记录；接受时 receipt 含 `projectId` |
| `GET /v1/project-directories?path=…` | path 可省略，起点为原生节点当前目录 | `{path,parentPath,entries}`；条目结构同 `/v1/directories` |
| `GET /v1/projects/{projectId}/git` | Bearer | `{git:{available,head?,branch?,dirty?,refs:[{id,commit,name}],reason?}}` |
| `GET /v1/models` | Bearer | `{models:[…],catalog:{source,stale,updatedAt}}`，原生模型目录及最近成功缓存；不使用固定模型清单 |
| `GET /v1/goals/capabilities` | Bearer；只读，不创建会话 | `{goalSupported:boolean}`；能力读取故障返回错误，不等同于不支持 |
| `GET /v1/usage` | Bearer | `{usage:{…}}`；原生 `account/rateLimits/read` 响应 |
| `GET /v1/threads?projectId=…&archived=false` | 必填 projectId；archived 仅接受 `true` / `false`，默认 false | `{threads:[{id,name,preview,status,updatedAt,cwd,projectId,pinned}]}` |

配对码只由电脑本地 `pair` CLI 生成，没有远程生成码路由。默认 5 分钟且只兑换一次；大小写和分组连字符可归一化，过期/已消费/无效码均返回 401，限流返回 429。节点连接 token 与 OpenAI/Codex 登录 token 不同。

`runtime` 包含连接方式、runtimeId、generation、runtimeMode、preferredWriter 与 bridge 就绪/验证状态。只有已验证的 Desktop 桥接连接可报告 `desktopShared=true`；普通 daemon 或未知状态不能推断为 Desktop 共享。`capabilities.media/speech` 明确广告可用媒体/语音能力，缺省字段不能推断支持。

添加项目时，省略 `folderName` 表示添加已有目录；提供它则在 path 下新建单层文件夹并添加项目。名称不能是 `.`、`..` 或包含路径分隔符、NUL。Git 信息中的 refs 最多包含当前 HEAD 和 200 条本地/远程分支；`worktreeRef` 使用其 commit ID，不传用户拼接的工作树路径。`outstandingWorkCount` 按会话去重统计原生执行中、持续目标 active 或存在 Relay 队列的会话；目标在轮次间隔或外部运行时持有时仍计入。`runningTurnCount` 仅统计当前原生活跃线程，`activeGoalCount` 统计持续目标 active 的会话。

额度对象保留原生 `rateLimits` / `rateLimitsByLimitId`、窗口 `usedPercent`、`windowDurationMins`、`resetsAt` 等字段。缺失值表示尚无数据，不能推断剩余 100%。账户额度与下述单会话 `tokenUsage` 是不同数据。

`requireGoalSupport=true` 用于带持续目标的新建流程：服务端在首轮发送前检查原生目标接口，不支持则拒绝发送。客户端在首轮 accepted 后单独提交目标设置；失败只恢复目标设置，不重复首轮。

## 会话、历史和输入

| 方法与路径 | 请求 | HTTP 200 响应 |
| --- | --- | --- |
| `POST /v1/threads` | `{submissionId,projectId,worktreeRef?}` | 提交记录；接受时 `{threadId}` |
| `POST /v1/threads/{id}/fork` | `{submissionId,lastTurnId,worktreeRef?}` | 分叉到指定已结束轮次（包含），返回提交记录及 `{threadId}`；不指定工作树时沿用来源 cwd |
| `GET /v1/threads/{id}/fork-context` | — | 来源 `projectId`、`cwd` 和 Git 来源选项 |
| `GET /v1/threads/{threadId}` | 可选 `cursor`、`limit`（默认 30，钳制到 1–100） | `{thread,requests,settings,activeTurnId,status,readOnly,nextCursor,backwardsCursor,queue,plan,tokenUsage,changes,turnSettings}`；`thread.turns` 是本页原生 turns |
| `GET /v1/threads/{threadId}/history` | 同上 | `{turns,nextCursor,backwardsCursor}` |
| `POST /v1/threads/{threadId}/turns` | `{submissionId,text,model?,effort?,mode?,serviceTier?,requireGoalSupport?}`，或用 `input` 数组 | 提交记录；接受时 `{threadId,turnId}` |
| `POST /v1/threads/{threadId}/settings` | `{submissionId,model?,effort?,serviceTier?}` | 更新原生 thread settings；至少一个设置；`serviceTier` 为 `default` / `priority`；不改审批和权限 |
| `POST /v1/threads/{threadId}/steer` | `{submissionId,expectedTurnId,text}`，或用 `input` 数组 | 提交记录；接受时 `{threadId,turnId}` |
| `POST /v1/threads/{threadId}/interrupt` | `{submissionId,turnId}` | 提交记录；接受时 `{threadId,turnId}` |
| `POST /v1/threads/{threadId}/archive` | `{submissionId,archived:boolean}` | 提交记录；接受时 `{threadId}`；false 为恢复 |
| `POST /v1/threads/{threadId}/answers` | `{submissionId,expectedTurnId,answers:[{questionItemId,answer}]}` | 回答原生异步问题；接受时 `{threadId,turnId}`，不接管 writer |
| `POST /v1/requests/respond` | `{submissionId,key,response:{…}}` | 提交记录；接受时 `{requestId,threadId}` |

`GET /v1/submissions/{submissionId}` 仅查询当前连接的回执。返回 `submission_not_found` 时，客户端可调用 `POST /v1/submissions/{submissionId}/reconcile`（空 JSON 对象）：已有记录原样返回；不存在时在与提交 claim 相同的事务锁内写入 `failed / submission_not_received` 记录，阻止同 ID 的迟到请求执行。不能仅凭 GET 404 就允许重新发送。旧节点不支持该接口时继续保留未知状态，不重放原操作。

`requests` 项含 `{key,id,method,params,connectionId}`。使用原样 key 和原生 method 对应的 response；不自行生成审批默认回答。请求失效或另一端已回答时不能再次响应。`settings` 仅包含该原生版本返回的模型、effort、cwd、审批、sandbox 等设置；GET 会 resume/join 原生会话以取得实时状态，不能当作纯数据库读取。

原生历史适配协议 v2 保留未知 item 类型的完整字段，客户端以未知活动展示，不因新增展示类型拒绝整页。turn 的 ID、status、完整 items、重复身份及分页游标仍严格校验；显式 `itemsView` 必须为 `full`，未知 turn status 不推断执行权限。`thread.turns[].status/error` 是轮次结果来源；失败或中断的空轮次同样展示诊断，不生成助手消息或自动重试。

历史 `limit` 为返回轮数上限；原生 full-items 请求每页最多 10 轮，继续使用原生游标，不为填满请求数量拼接大页。单轮图片较多时，原生连接允许最多 128 MiB 的单条消息，并限制接收队列；超过上限会返回明确大小错误，不自动重放请求。

完成的图片生成项在服务端媒体登记成功后，可返回 `mediaRef: true` 和媒体内容 URL 形式的 `result`，替代内嵌图片数据；原生记录保持完整。媒体尚未 ready 时客户端继续查询媒体库，登记失败则保留原内嵌数据。

流式投影支持助手正文、命令输出、文件输出、计划和按索引分段的 reasoning 增量。item 完成时保留原位置；迟到 start/delta 不重新打开已完成项或轮次，较短实时缓存不覆盖较长原生输出。助手正文和命令输出事件的 `params.relayOffset` 按 Unicode code point 计数，客户端仅在已有基线匹配时直接追加，否则重新读取快照。其他观察事件不会凭空创建进行中的轮次。

纯文本限制 200,000 字符。传 `input` 时它必须包含 1–64 个原生项，文本也作为数组项传入；外层 `text` 不会再拼进 input。当前 HTTP 校验支持：

```json
{
  "submissionId": "caller-generated-stable-id",
  "input": [
    {"type":"text","text":"Describe this image","text_elements":[]},
    {"type":"image","url":"data:image/png;base64,..."},
    {"type":"localImage","path":"/node/path/image.png"},
    {"type":"mention","name":"notes","path":"/node/path/notes.txt"},
    {"type":"skill","name":"review","path":"/node/path/SKILL.md"}
  ]
}
```

`image` 也接受原生 `fileId` 字段，但该形式尚未真实执行验收；audio/localAudio 不在原生 turn HTTP 输入列表；语音输入由独立 speech 流式接口转成文本。节点路径不是 Android `content://` URI；系统选图由 App 转为图片 data URL，普通文件先上传取得 mention。输入保留原生结构，由原生运行时进一步校验。不传 model/effort 时沿用该 thread 的原生设置；不接受调用方任意替换审批策略、sandbox 或整套运行配置。

`mode` 可省略，或为 `plan` / `default`。Plan 通过原生 collaboration mode 运行，并将当前 turn sandbox 收紧为 `readOnly`；切回 default 仅在当前配置仍匹配 Relay 保存的 Plan 设置时恢复原先 sandbox，避免覆盖其他端修改。已有原生只读配置不会因此提升权限。`readOnly=true` 表示另一运行时占用了 thread writer，仍可读取持久历史。

异步问题来自 `agentMessage.questions:[{title,options?:string[]}]`，问题身份使用 `JSON.stringify(["request_user_input_async", item.id, index])`。快照 `canAnswerQuestions=true` 表示当前问题的原 writer 可达，独立于 `readOnly` 和 `control.canOperate`；客户端不能据此放开普通任务、停止或设置操作。回答接口重新核验问题、来源回合和已接受回答，服务端生成原生 `send_user_message_question_reply` 消息。运行时使用带 `expectedTurnId` 的 `turn/steer`，空闲且问题仍属于最新回合时通过原 writer 的 `turn/start` 继续；均不调用 `thread/resume`、不创建 writer、不加入普通任务队列。桌面进程不在节点所连接 daemon 中且没有可用共享连接时能力为 false，不会隐式接管。接口沿用提交 ID 去重与结果不确定时不重发规则。

问答恢复：快照的 `questionAnswerStates` 按原生 questionItemId 提供 pending/unknown/accepted。服务端在发送前持久化问题级提交记录；即使换 submissionId、重启节点或原生历史暂未更新，也不重复发送。明确未发送的失败允许重试；结果不确定时保持待核对，禁止自动重放。APP 按节点/会话/来源回合保存独立回答草稿，离线可编辑、不可提交，恢复新快照后重新校验。

归档前检查运行中的 turn、待回答请求和执行队列；存在未处理工作时拒绝，最终操作仍由原生节点校验。批量归档、恢复由客户端逐项提交独立 submissionId，没有全有或全无的批量事务。未读标记是 Android 按节点和 thread 的已读 updatedAt 本地记录，没有服务端未读字段。没有已读记录的历史会话首次出现在列表（包括本地缓存）时，以当前 updatedAt 持久保存已读基线；后续更晚的已完成更新才显示未读，已有已读记录继续保留。

历史按原生 thread/turn/item ID 合并，不按文字去重。新建空会话可能还没有持久 rollout，首轮前返回创建元数据和空 turns；完成历史以原生分页为准。`nextCursor` 为空表示没有下一页，cursor 不应跨 runtime/重置继续使用。

## 执行计划、上下文和改动观察

会话快照附带 Relay 已接收到的原生观察，不另设 observations 路由：

- `plan`：当前或最新 turn 的 `{turnId,plan,explanation,status}`，来自 `turn/plan/updated`。
- `tokenUsage`：最近一次 `thread/tokenUsage/updated` 中的原生 tokenUsage 对象，包括原生提供的上下文窗口数据；未收到时为 null。
- `changes`：`[{turnId,files:[{path,added,removed,binary}]}]`，来自 `turn/diff/updated` 的摘要。
- `turnSettings`：`[{turnId,model,effort?,rerouted?}]`，记录 Relay 已接受发送的模型/effort，以及原生 `model/rerouted` 通知中的目标模型。当前原生 Turn 本身不提供 model；旧轮次没有对应观察时不使用当前模型反推历史模型。

这些观察有界持久化，单项最多 2 MiB、全库最多 1,000 项；缺失不等于没有执行计划或改动。它们不代替原生历史，实时工作区差异使用 `/diff`。

## 工作树、差异和 Review

| 方法与路径 | 请求 | HTTP 200 响应 |
| --- | --- | --- |
| `GET /v1/threads/{threadId}/workspace` | Bearer | `{workspace:null}`，或 `{workspace:{id,path,sourcePath,commit,status,dirty,revision,archivePath}}`；外部工作树另含 `externalWorkspace:{path,projectRoot,projectId,commit,status,managed:false}` |
| `POST /v1/threads/{threadId}/workspace/cleanup` | `{submissionId,revision,discard?:boolean}`，默认 false | 提交记录；接受时 `{threadId}` |
| `GET /v1/threads/{threadId}/diff` | Bearer | `{diff:{patch,revision,files:[{path,added,removed,binary}],truncated}}` |
| `GET /v1/threads/{threadId}/review-context` | Bearer | `{branch,branches:[…],commits:[{id,subject}]}`，最近 30 个提交 |
| `POST /v1/threads/{threadId}/review` | `{submissionId,target:{…}}` | 提交记录；接受时 `{itemId}`，表示审查任务已入队 |

历史轮次分叉使用原生 `thread/fork` 的 `lastTurnId`，只包含选中轮次及之前的历史，不恢复历史文件状态。新建工作树从所选 Git 提交建立，不复制未提交改动；会保留来源 cwd 在仓库内的相对子目录，目标目录缺失或解析后越出工作树时拒绝创建。独立 writer 会检查实际 Codex 二进制的参数 schema；不支持的版本或尚不能核实此能力的 Desktop 桥接返回 `fork_unsupported`，不会退化为完整历史分叉。


创建会话时指定 `worktreeRef`，Relay 在节点上建立独立 Git worktree，再以该 cwd 启动原生会话。只允许清理 Relay 创建并关联的工作树。被分叉会话共享或被派生工作树依赖的托管工作树禁止从单个会话清理；共享保护跨重启保留，分叉结果未知时也保留。清理必须使用最新 `revision`，会拒绝活跃、被占用或仍有队列的会话；有改动时必须显式 `discard=true`。清理先归档会话，再把改动目录打包到节点上的 `archivePath`，复核内容指纹后才移除 worktree。调用结果不确定时保留状态供核对，不自动重试删除；已移除或待核对的工作树不能回退到来源目录继续执行。

`diff` 对比 HEAD，包含已跟踪改动及最多 100 个未跟踪文件，patch 上限 2 MiB。`truncated=true` 表示不能据此视为完整差异。`revision` 是该响应 patch 的摘要，与工作树清理的内容指纹 revision 不可互换。

2026-11-07 起，差异响应增加 `schemaVersion:1`、`structured:true`、文件身份与结构化行。文件字段为 `id/path/oldPath/newPath/status/binary/added/removed/truncated/malformed`。`lines` 每项含 `fileId/path/kind/text/oldLine/newLine`；kind 为 file、hunk、addition、deletion、context 或 notice。首屏保留旧客户端需要的 patch，结构化客户端不解析 patch。

差异分页传 `revision`、可选 `fileId`、`offset` 和 `limit`（默认 400，最大 1000）。返回 `offset/nextOffset/totalLines`；`nextOffset:null` 表示结束。后续页不重复传 patch。revision、文件身份或分页位置失效返回 409，客户端重新读取快照并按路径恢复选择；不能拼接不同 revision 的内容。

Review 使用原生 `review/start`，通过会话执行队列等到可执行时启动。target 只接受以下四种范围，不执行 Git commit/push/merge：

```json
{"type":"uncommittedChanges"}
{"type":"baseBranch","branch":"main"}
{"type":"commit","sha":"<commit-id>"}
{"type":"custom","instructions":"检查取消请求后是否仍会写文件"}
```

branch / sha 最长 500 字符，自定义说明最长 20,000 字符；服务端为 commit 范围补原生 `title:null`。Git、目录与文件命令均在选定原生节点执行，并沿用该节点权限。

## 提交状态与去重

项目和会话写操作、执行队列及待办交付返回：

```json
{
  "submissionId": "caller-generated-stable-id",
  "status": "accepted",
  "receipt": {"threadId":"native-thread-id","turnId":"native-turn-id"},
  "errorCode": null
}
```

| status | 含义 |
| --- | --- |
| `pending` | claim 已持久化，调用尚未确认 |
| `accepted` | 原生已接受并返回身份；不等于 turn/待办已完成 |
| `failed` | 已确认的拒绝/失败，详情为稳定 `errorCode` |
| `unknown` | 调用中断、超时、缺少回执或服务重启，结果待核对；不能自动再执行 |

`GET /v1/submissions/{submissionId}` 查询同一连接自己的记录，响应结构同上；其他连接看不到该查询结果。ID 全局唯一且绑定连接、操作、固定目标和请求摘要。同 ID 的相同请求返回既有记录，所有状态都不再次执行；改变正文/目标/归属复用 ID 返回 409。Android 不自动重发原生写操作；Relay 自有待办 CRUD 的离线同步规则见待办部分。

原生写操作进入 claim 后，即使原生明确拒绝，也可能以 HTTP 200 返回 `status=failed`；必须检查提交状态。请求格式、认证或 claim 冲突等错误仍使用 4xx/5xx。`GET submissions` 当前只查已知持久状态，**不会自动对比原生历史并把 unknown 改成 accepted**；unknown 需结合原生身份/历史人工核对，不提供强制重试或一键丢弃未知执行的接口。去重记录无按时间删除策略。App 的“结束核对”是用户明确确认后的本地等待解除，不调用取消/重发接口，不修改服务记录或把 unknown 改成成功；提交 404 也不能自动推断原请求从未发生。

会话快照另含 `reviewDiagnostics:[{turnId,status,readOnlyViolation,message}]`。`status` 为 `verified`、`changed` 或 `unavailable`；Review 执行前后核对 HEAD、index、工作区与状态指纹，完成后结果持久保存。变化提示不意味着修改一定来自审查，不会自动回滚。诊断独立于原生正文和 item。

外部工作树仅在原生节点的 Git 注册与 common-dir 验证后归属项目。已验证路径被删除时，只在原项目根未变且旧路径确实缺失时保留关联；`externalWorkspace` 不提供清理授权。新旧路径都无法验证时，不猜测项目归属。

## 文件、目录和附件

| 方法与路径 | 请求 | HTTP 200 响应 |
| --- | --- | --- |
| `GET /v1/directories?path=…` | 节点绝对路径 | `{path,entries:[{fileName,isDirectory,isFile,…}]}`；保留原生条目字段，最多 2,000 项 |
| `GET /v1/files?path=…` | 节点绝对路径 | 下述预览 JSON，文件最大 4 MiB |
| `GET /v1/files/download?path=…` | Bearer 和节点绝对路径 | `application/octet-stream`，单文件最大 1 GiB |
| `POST /v1/threads/{threadId}/attachments?name=…` | `application/octet-stream` 与明确 Content-Length，最大 1 GiB | `{type:"mention",name,path,size}` |
| `POST /v1/threads/{threadId}/attachments` | `{name,dataBase64}`；仍受 64 MiB JSON 请求限制 | 同上；兼容小文件调用 |
| `POST /v1/projects/{projectId}/attachments?name=…` | 同上，按已选项目根暂存首条消息附件 | 同一 mention 回执；不会创建或恢复原生 thread |
| `POST /v1/projects/{projectId}/attachments` | `{name,dataBase64}`，64 MiB JSON 限制 | 项目级小文件兼容入口 |
| `POST /v1/download-links` | `{path}` | `{url,expiresAt}`；expiresAt 为 Unix 秒 |
| `GET /v1/download-links/{ticket}` | 临时 URL，无需 Bearer | 与 `/v1/files/download` 相同的文件流 |

`GET /v1/files?path=<encoded-node-absolute-path>` 经同一原生 daemon 读取节点文件：

```json
{"path":"/node/path/notes.txt","size":53,"mimeType":"text/plain","encoding":"utf-8","text":"…"}
```

二进制响应将 `encoding` 改为 `base64`，返回 `dataBase64`。仅读取普通文件，超限 413，原生不支持 FS RPC 时 501。服务端先核对节点文件大小，再通过原生命令按最多 4 MiB 块读取，下载不会在 Relay 内存中拼接整份 1 GiB 文件。

两个下载端点返回强 `ETag` 和 `Accept-Ranges: bytes`，支持单个 `bytes=start-end`、`start-` 或 `-suffix` 范围（206 与 `Content-Range`）。无效、多段或不可满足范围返回 416，并带 `Content-Range: bytes */size` 与 ETag。`If-Range` 与当前强 ETag 不同则返回完整 200，客户端必须重新写入；每个原生块核对文件版本，中途变化会中止连接。Android 的部分缓存按节点地址和凭据隔离，只有同一强 ETag 可以追加，完成缓存也先验证；公开 URL 使用独立客户端，不携带节点令牌。已发布缓存使用不可变路径，避免播放或系统分享期间重试下载使路径失效。

文件上传写入会话 cwd 下新建的 `.rivo-attachments/<随机目录>/`，返回节点文件引用；不会直接发送给模型，调用方在后续 turns/queue 中加入该 mention。上传不使用 submissionId；丢失响应后重传会产生另一份文件。上传和下载共享有界传输槽，繁忙返回 429。

项目级附件使用已认证节点的 `project/read` 获取准确项目根，再复用同一原生 FS 上传与分块清理流程。待发送工作树尚未创建时，附件先保存在来源项目的随机附件目录，最终以绝对节点路径 mention 引用；不会复制到不存在的工作树或提前创建空会话。

实际发送/引导时，mention 的可见引用保持原样；文本附件由同一原生节点读取，并通过 `additionalContext` 的 `untrusted` 项提供内容（单个 UTF-8 文本 4 MiB，总上下文 8 MiB）。视频提供文件路径与明确的检查/抽帧处理说明；其他二进制附件提供路径供原生工具读取。编码错误、二进制伪装文本和超限在发送原生 turn 前明确失败。

临时下载链接限定一条节点文件路径，有效期 1 小时，服务重启失效；最多保留 128 条未过期票据。它允许系统下载器无需携带连接令牌访问该文件，持有链接即可读取，请勿记录或公开。路径固定但文件内容并非快照，连接令牌撤销不会单独撤销已经生成的票据。

这些接口使用原生节点级文件访问能力，不受某个 turn 的 sandbox 参数约束，也不附加 Rivo 项目白名单。Linux/macOS 依赖节点已有 shell、Git 等工具；跨平台实际验证范围见验证记录，不能仅由路径校验支持 Windows 推断全部命令已验收。

## 待办

| 方法与路径 | 请求 | HTTP 200 响应 |
| --- | --- | --- |
| `GET /v1/todos` | 无筛选参数 | `{todos:[…]}`，未完成在前，创建时间倒序 |
| `POST /v1/todos` | `{id?,title,notes?,details?}` | `{todo:{…}}` |
| `GET /v1/todos/{id}` | 无 | `{todo:{…}}` |
| `PATCH /v1/todos/{id}` | `title`、`notes`、`completed`、`details` 至少一个字段；不接受其他字段 | `{todo:{…}}` |
| `DELETE /v1/todos/{id}` | 无 | `{"deleted":true}` |
| `POST /v1/todos/{id}/dispatch` | `{submissionId,projectId,threadId?}` | 提交记录；接受时含 `todoId,threadId,turnId` |

Todo 当前字段：`id,title,notes,completed,projectId,threadId,createdAt,updatedAt,completed_at,details`；completed_at 保留当前实际 snake_case 名称。标题去首尾空格后 1–200 字符，notes 最多 4,000 字符，客户端 id 最多 128 字符。省略 id 时服务生成；已有 id 的 title、notes、标准化 details 相同则返回原待办，不同内容 409。待办基础 CRUD 不使用 submissionId；原生交付使用。

`details` 是整体替换对象，不是深度合并；未提供的字段使用以下默认值，其他字段或 null 被拒绝：

```json
{
  "dueDate": "",
  "reminderAt": 0,
  "repeatRule": "none",
  "subtasks": [],
  "attachments": []
}
```

- dueDate 为有效 `YYYY-MM-DD` 或空字符串；reminderAt 为非负 Unix 毫秒整数，0 表示不提醒。
- repeatRule 为 `none`、`daily`、`weekly`、`monthly`、`yearly`；重复需要 dueDate。
- subtasks 最多 64 项，每项 `{id,title,completed}`；id 唯一且最多 128 字符，title 最多 500 字符，completed 默认 false。
- attachments 最多 16 项，每项 `{name,path,type}`；name/path/type 最多 300/4096/50 字符，type 默认 `file`。它们是节点文件引用，保存待办不会上传文件。

用户把未完成待办改为 completed=true 时，服务在同一事务创建下一次重复项；每日/每周按日期推进，每月/每年在不存在该日时取月末。子任务重置为未完成，附件保留，Codex 会话关联不继承。下一项使用固定派生 ID，重放完成 PATCH 或重新打开后再次完成不会产生重复下一项。提醒按日期差平移毫秒，不保存时区规则；跨夏令时后需留意本地钟点。Codex turn 完成不自动完成待办或生成重复项。

Android 先把离线 CRUD 和固定 Todo ID 持久写入按节点隔离的 outbox，再串行同步。POST 响应丢失沿用相同 id；PATCH 使用明确字段值；删除 404 视为已删除。同步失败保留本地内容和待同步队列。派发必须在线并先同步成功，不进入离线重放队列。提醒由 Android 本地权限及系统调度触发，HTTP 服务不代发提醒。

省略 threadId 会在选定项目新建；提供已有 threadId 时验证其属于 projectId。这是请求目标一致性检查，不是独立权限限制。新建 thread 后先持久 checkpoint，再关联和发送。已完成待办不能发起新的派发；已有关联或已有 pending/unknown/accepted 交付时，新 submissionId 被拒绝，用户通过关联会话继续。明确失败且没有创建 thread 的交付可用新 ID 显式重试。已有同 ID 回执仍可读取，不重复创建 thread。

派发摘要绑定当时 title、notes、details 及目标；正文包含标题、备注和子任务，附件转换为原生 `mention` 输入。升级前不含 details 的派发摘要仅在现有 details 为空/全默认值且原摘要完全匹配时兼容读取；修改正文、附件或目标后复用旧 ID 仍返回 409。

## SSE

`GET /v1/events` 使用 Bearer，返回 `text/event-stream`。可发 `Last-Event-ID: <epoch>:<sequence>`；首次连接、无效/过期 cursor、缓冲缺口或重启发：

```text
id: opaque-epoch:123
event: reset
data: {}
```

普通事件为 `event: native`、`data: {"method":"turn/started","params":{…}}`；其中也会包含 `relay/submission`、`relay/todos` 等 Relay 通知。撤销发 `event: revoked` 后关闭，空闲约 15 秒发注释 heartbeat。cursor 可能只附在一个批次的最后事件；重连允许重复到达，客户端按原生 ID 合并。

当前事件流是该认证节点的全局通知，`threadId` query 不做服务端过滤。它不是历史或持久消息队列：遇到 reset 必须重读当前原生快照，再合并后续通知。Android 在当前连接状态中保留可信 SSE cursor 并携带 Last-Event-ID 重连；进程重建或节点切换仍先读取快照，不从显示缓存恢复权威 cursor。默认最多 4 条 SSE，超额 429；普通 HTTP worker 耗尽可能直接关闭新连接，不能假定所有容量拒绝均有 JSON 响应。

## 常见错误

| HTTP | 示例 code / 含义 |
| --- | --- |
| 400 | `invalid_request`、`invalid_json`、`invalid_input`、`invalid_path` |
| 401 | `unauthorized`、`invalid_pairing_code`；连接或配对失效 |
| 404 | `submission_not_found`、`todo_not_found`、原生 thread 不存在 |
| 409 | `submission_conflict`、`todo_conflict`、`interaction_expired`、原生状态冲突 |
| 411 / 415 | 缺少唯一 Content-Length / 请求格式或文件类型不支持 |
| 413 | `request_too_large`、`file_too_large`、`upload_too_large`、`directory_too_large` |
| 429 | `pairing_rate_limited`、`stream_limit`、`transfer_busy`、`too_many_downloads` |
| 501 | `native_method_unsupported`、`native_fs_unsupported` |
| 503 | `native_unavailable`、`native_timeout`、`native_protocol` 等 |

网络中断和 503 不表示原生写操作一定未发生。先查 submission 和原生会话，不能由传输层换 ID 自动重试。

## 置顶与执行队列

所有写操作继续使用认证及稳定 `submissionId`，返回原有提交状态回执。

- `POST /v1/threads/{id}/pin`：`{submissionId,pinned:boolean}`；操作原生 Pinned 分组。
- 会话列表含 `pinned`；会话快照含 `queue` 数组（id/text/status/revision/input/model/effort/mode/review/turnId/error）。
- `POST /v1/threads/{id}/queue`：`operation=enqueue`，携带原生 `input` 和可选 `model/effort/mode`，回执含 `itemId`。accepted 表示已持久入队，执行状态另看 queue/native turn。Review 项由 `/review` 创建。
- `operation=edit`：`itemId,revision,text`；只替换正文，保留非文本输入和设置。Review 项需撤回后重新选择范围，不能修改正文。
- `operation=remove`：`itemId,revision`；撤回待发项或移除已核对的不确定记录，不中断已运行 turn。
- `operation=reorder`：`ids,expectedIds` 均为完整 waiting ID 列表；预期顺序过时则拒绝。
- `operation=steer`：`itemId,revision,expectedTurnId`；只把 waiting 项引导到该原生 turn，成功后不再排队执行。Review 和 Plan 项不能转为中途引导。
- `operation=recover`：`itemId,revision`；为未确认或已中断的恢复项建立新的安全续接请求，保留设置，不复制原指令、附件或 Review；回执含新 `itemId` 和 `recoveredItemId`。
- `operation=dismiss`：`itemId,revision`；明确忽略恢复项，仅解除队列阻塞。

状态：waiting → dispatching → running → done。明确失败为 failed；不确定写结果或重启时的 dispatching 为 unknown，禁止自动重放。完成/撤回项不在快照中显示。`relay/queueChanged` 触发快照刷新。unknown 和已经启动过的失败任务不允许编辑后重发，只能明确续接、忽略或核对后移除。

unknown 状态会定期只读核对原生历史：仅在唯一 `clientId` 或已有 turn ID 精确匹配时采用该 turn 的实际状态；历史尚在收敛或身份有歧义则保持未知。不会通过正文匹配、新建 turn 或重放输入来消除未知状态。

会话增加 `nativeExecution/deliveryState/displayState/writerOwner/queueDepth/recoveryRequired/recoveryTask`。这些状态不由历史缓存或本地乐观气泡推断。历史增加 `nativeRevision` 与 `nativeSync.state`；更早页请求携带首页 revision，不一致返回 409；`converging` 时保留上一份成功内容并暂缓拼页。

0.2.20 增加 `nativeHeadRevision`，表示最新页的实时内容水位。`nativeRevision` 继续作为分页拼接依据：活动 item 的纯正文增量不使旧页失效；轮次/item 结构、终态、已完成正文、原生连接代数及历史修复变化仍使其失效。客户端不得用 `nativeHeadRevision` 替代写入权限或审批代数。reasoning、plan、fileChange 文本增量同样可提供追加前 Unicode code point 位置 `relayOffset`；仅在身份、字段和偏移都可核对时本地合并，否则重读权威快照。

ownership 预览可能含 `workspaceRebind:{path,missingPath}`。仅当旧目录确认不存在且原项目目录可唯一确认时提供。手机展示准确目录后，提交同票据、`confirmed:true` 与 `rebindWorkspace:path`；服务端再次验证，原会话恢复到明确确认的目录，不重放原任务。


### 0.2.2 SSE 文本位置扩展

`native` 事件中，`item/agentMessage/delta` 与 `item/commandExecution/outputDelta` 可携带 `params.relayOffset`，表示追加该 `delta` 之前的 Unicode code point 数量（不是 UTF-8 字节数，也不是 UTF-16 code unit 数）。原生 `delta` 与 thread/turn/item ID 保留。Android 对可信快照中相同 item 核对位置与重叠文本；重复片段忽略，缺失基线或不一致时重新读取快照。旧客户端可忽略此字段；新客户端连接缺少此字段的旧服务时回退到快照读取。事件仍是短期同步数据，原生历史仍为最终依据。

#### Session handoff (0.2.7)

`GET /v1/threads/{id}/ownership?operation=takeover|release` previews the actual
OS lock holder and affected threads. Returns `ticket`, `requiresConfirmation`,
`affectedThreads` (IDs/titles), `affectedThreadIds`, `processIds`, `childProcessCount`.
Tickets expire after 120 seconds, are one-use and bound to the authenticated
connection, action and thread. Commands/environment/credentials are not exposed.

`POST /v1/threads/{id}/ownership` takes `submissionId`, `operation`, `ticket`,
and `confirmed: true` when more than one thread is affected. Standard durable
submission receipts contain threadId only. It revalidates process identity, locks
and descendants, pauses affected Relay subscriptions/queues, sends SIGTERM and
escalates to SIGKILL after two seconds if the same process remains. Verified
surviving descendants are terminated too. OS lock release must be observed.
For takeover, thread/resume must succeed and the new runtime PID must hold the lock.
Release leaves the thread unowned by Relay. If the current native daemon was killed,
its verified binary/arguments restore the existing endpoint; this starts no turn.
No automatic original-instruction replay. A shared-process confirmation is required
for release as well as takeover. Changed/expired previews must be fetched again.


### 未持久化空会话的释放保护

独立 writer 的释放预览和执行可能返回 `409 thread_unmaterialized`：原生首条消息尚未持久化，关闭进程会使该原生 ID 无法恢复。此错误保留 writer、会话和队列状态；在真实首条消息持久化后可再次释放。不能把此结果显示为“已释放”。

会话快照 `nativeRuntime.releaseAvailable=false` 与 `releaseUnavailableReason` 提前告知该限制，客户端显示原因、禁用释放并保留草稿。字段缺失时兼容旧节点，服务端执行前核验仍为最终依据。

所有权核验失败不等于确认有外部持锁者：此时 `writerOwner=unknown`、`readOnly=true`、`control.state=ownership_unverified`、`control.canOperate=false`、`control.action=null`，并提供 `unavailableReason`；`blockReason` 同样为 `ownership_unverified`。客户端提供只读重新核验，不自动接管或恢复。网络未连接仍使用原有 `control.state=unknown`。

`relay/writerDisconnected.params.threadIds` 是确切影响范围（空数组表示没有会话 writer 受影响）；客户端应立即使目标会话权限和审批失效，丢弃断线前在途快照与分页，不能等待下一次轮询才禁用操作。

### App 独立 writer

`auto`/`app_server` 下，App 会话使用独立 writer。其 release 预览返回 `releaseScope=session-writer`，影响范围仅目标会话；执行会暂停该会话队列、中断任务、关闭 writer 并核验 OS 锁。断线必须明确恢复，旧审批不会路由到新 writer。空闲会话的 GET 不取得写锁，`writerOwner=none` 且 `control.canOperate=true` 表示允许首次执行，`control.action=null` 表示尚无 Relay writer 可释放。外部 writer 与旧共享 daemon 仍使用上文影响范围确认。

## Desktop 与原生历史能力（2026-11-07）

运行模式：`--codex-runtime auto|desktop|app_server`，桥接目录参数 `--desktop-bridge-dir`。`/v1/node.runtime` 提供所选 writer、桥接可用/就绪、验证状态；会话快照 `nativeRuntime` 绑定该 thread 的实际 writer。原生历史修复通过实际节点执行，`nativeSync.state` 为 ready/converging/unavailable；收敛期间暂停分页，修复成功发送 `relay/historyInvalidated` 并改变 nativeRevision。旧游标不得继续使用。所有原生写入仍受稳定 submission ID 与 writer 边界约束。

图片 input：最多10张，单张20MiB、合计40MiB；仅允许 JPEG/PNG/WebP data URI。服务端校验后分块上传到实际原生节点，改为 localImage 再发原生RPC。普通原生URL/fileId引用仍由原生服务校验。

## 认证媒体与语音

以下接口均需当前配对连接的 Bearer token，不能向源站转发该 token。

| 接口 | 行为 |
| --- | --- |
| GET `/v1/media/capabilities` | `{media,speech}`；未配置ASR时speech能力为false并返回原因。 |
| GET `/v1/media/content?source=...` | 原生绝对路径或公网图片/音视频；有大小、DNS/重定向及内容类型限制；支持Range、If-Range、ETag。 |
| GET `/v1/media/thumbnail?source=...` | 图片1536px以内WebP q82；按源版本缓存，ETag校验。 |
| GET `/v1/media/library?threadId=...&favorite=true&source=...` | 最多500条，`{items:[...]}`；source为精确只读查询，不自动导入。 |
| POST `/v1/media/library` | `{source,threadId?}` 显式导入副本，返回record。 |
| PATCH `/v1/media/library/{id}` | `{favorite:boolean}`，返回record。 |
| DELETE `/v1/media/library/{id}` | 删除副本并保留tombstone，不修改原生会话和源文件。 |
| GET `/v1/media/library/{id}/content`、`thumbnail` | 已保存副本/缩略图，认证Range/ETag。 |
| GET `/v1/media/library/{id}/provenance` | record含原生threadId/turnId/itemId及provenance.facts、promptKind（original/revised/unknown）。 |
| GET `/v1/media/generated?threadId=...` 及 `/{id}/content`、`thumbnail`、`provenance` | 生成媒体查询/读取别名。 |
| POST `/v1/speech/streams` | 创建当前配对连接拥有的转写流。 |
| POST `/v1/speech/streams/{id}/chunks` | `application/octet-stream` 原始16kHz单声道PCM16LE，不自动重发。 |
| POST `/v1/speech/streams/{id}/finish`、`cancel` | 完成或取消流；跨配对连接访问被拒绝。 |
| POST `/v1/speech/transcriptions` | 有界multipart整段音频转写。 |

媒体记录含 `id,source,threadId,turnId,itemId,status,mediaType,mimeType,favorite,hasGenerationInfo,originalUrl,thumbnailUrl`；API相对URL只在当前认证节点使用，不能当作远端原生文件路径。pending记录不能用于读取成品。公网图片40MiB，原生生成图片64MiB，媒体传输上限1GiB；缓存2GiB/24小时。ASR通过 `RIVO_SPEECH_BACKEND_URL` / `RIVO_SPEECH_BACKEND_TOKEN` 显式配置，不自动部署模型或服务。


进程级 ownership takeover 仅适用于已核实的本地主机 daemon，描述符、原生HOME、PID启动时间/二进制/socket在预览和执行时重复核对。显式共享 Desktop 模式已有本连接订阅的 release 使用 `thread/unsubscribe`，返回 `releaseScope=relay-subscription`，Desktop/远程均不杀进程，也不宣称释放了其他客户端的锁。`nativeRuntime.handoffAvailable` 仅表达强制接管能力；正常释放不依赖该值。

### 会话权限选择

`POST /v1/threads` 与 `POST /v1/threads/{id}/settings` 可分别传入 `sandbox`（`read-only`、`workspace-write`、`danger-full-access`）和 `approvalPolicy`（`on-request`、`untrusted`、`never`、兼容旧版的 `on-failure`）。省略表示沿用原生设置，两个选项互不隐式覆盖。新建传给原生 `thread/start`；已有会话映射为 `thread/settings/update` 的 `sandboxPolicy`，保留已有显式网络开关。讨论模式下拒绝修改已有会话的文件权限，避免覆盖退出讨论后的恢复基线；审批仍可修改。

Android 输入框设置面板提供权限选择和“设为新会话默认”，默认值仅保存在当前手机的对应节点中，不修改 Desktop 全局配置。草稿保存明确的“沿用节点默认”选择，避免重开草稿时被手机默认值覆盖。会话创建已成功而首条发送尚未完成时，权限选择锁定至进入已创建会话，避免重试时显示未生效的选项。

显式 `POST /v1/threads/{id}/turns` 在发送时允许正常获取已释放会话的写权限；获取失败不会启动 turn，也不结束外部进程。读取会话仍核验实际持有者（包括已释放会话），后台队列发送仍受主动释放标记约束。


## VPS 双通道配对（v2）

v1 配对保持兼容。`scripts/start-node.sh` 的 v2 二维码 fragment 增加 `node`（稳定节点 ID）、`direct`、`relay`、`pin`（网关完整 DER 证书 SHA-256），以及可选 `updates=1`。所有值使用 URL 编码；桌面注册密钥不进入二维码。`GET /health` 额外返回 `nodeId`，供本机启动器确认监听端口属于指定 workspace。

手机重新配对已保存的节点时，`POST /v1/pair` 可以增加 `resume:{deviceId,binding,proof}`，不携带旧 Bearer。`binding` 为 UTF-8 的 `direct + "\n" + relay + "\n" + pin` 的 SHA-256 小写十六进制；服务端必须与自身 `gateway-client.json` 配置计算的结果一致。

请求 proof 为 `HMAC-SHA256(SHA256(token), "request\0" + code + "\0" + nodeId + "\0" + binding)`，结果为小写十六进制。有效一次性配对码和 proof 在同一事务里验证，只有未撤销的原连接可复用。复用响应返回原 `deviceId`、空 `token`，并提供相同算法、方向改为 `response` 的 `reuseProof`。手机校验响应 proof 后保留本地旧 token。正常新配对仍返回新 token；无效 proof 不会复用旧连接，也不会绕过配对码校验。

网关对外路径 `/relay/{slot}/v1/...` 保留现有业务认证、请求体、响应及 SSE。完整传输与部署约定见 [VPS 双通道说明](vps-gateway.md)。

### 目录摘要

`GET /v1/projects?view=summary` 只返回项目身份、名称和根路径，`statsReady:false` 表示计数尚未加载，不应显示为零。默认 `view=full` 保留完整统计。

`GET /v1/threads?projectId=…&view=summary` 返回状态库中的轻量会话元数据，顶层 `catalogComplete:false`。它是可展示的暂时结果，不包含完整 rollout/工作区归属与运行状态校准；不能据此断言不存在其他会话。客户端随后请求默认完整列表（`catalogComplete:true`）替换，且应在补全期间保留此前发现的会话。`archived` 过滤对两种视图均生效。摘要结果不用于执行准入或确认批量操作对象。

### Sites（只读）

Sites 网页另使用 VPS 专属 `GET /relay/{slot}/v1/sites/tunnel?host=…` WebSocket 字节隧道，需节点设备 Bearer，每条连接重新鉴权，仅允许公网 IPv4 的 HTTPS 443。未配置代理返回 503、无效设备令牌返回 401；不会将节点凭据转发给网站。详见 [VPS 中转](vps-gateway.md#sites-网页中转2026-11-10)。

`GET /v1/sites?cursor=…` 需要节点鉴权，返回 `{items:[{id,title,url,accessMode}],cursor}`。
`url` 仅来自 `current_live_url`，未发布时为 null；分页 cursor 原样传递，客户端不解析。
通过当前节点的辅助连接和临时会话调用固定的 `sites.list_sites`，结束时解除临时订阅并释放连接。
不返回源码凭据、账号名单、截图签名 URL 或完整工具响应，不提供通用 MCP 分发、创建或发布接口。
