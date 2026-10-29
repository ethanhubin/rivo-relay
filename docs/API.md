# Rivo Relay HTTP API

更新：2026-10-29；对应 0.2.0 的 `server/rivo_relay/api.py`。运行、配对 CLI 与构建方式见[README](../README.md)，功能和边界见[功能补齐记录](feature-parity.md)。这是一层通向选定原生 Codex 节点的 API，不建立独立执行权限体系。

## 通用约定

- 正式连接使用 HTTPS。`GET /health`、`POST /v1/pair` 与持有临时文件票据的 `GET /v1/download-links/{ticket}` 无需 Bearer；其余接口必须携带一个 `Authorization: Bearer <token>`。
- POST/PATCH 使用 JSON object 和 `Content-Type: application/json`，提供单个 `Content-Length`；不支持 chunked request，JSON 请求上限 **8 MiB**，不接受 NaN/Infinity。文件上传另支持 `application/octet-stream`，见文件接口。
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
| `GET /v1/models` | Bearer | `{models:[…]}`，保留原生模型字段，不使用固定清单 |
| `GET /v1/usage` | Bearer | `{usage:{…}}`；原生 `account/rateLimits/read` 响应 |
| `GET /v1/threads?projectId=…&archived=false` | 必填 projectId；archived 仅接受 `true` / `false`，默认 false | `{threads:[{id,name,preview,status,updatedAt,cwd,projectId,pinned}]}` |

配对码只由电脑本地 `pair` CLI 生成，没有远程生成码路由。默认 5 分钟且只兑换一次；大小写和分组连字符可归一化，过期/已消费/无效码均返回 401，限流返回 429。节点连接 token 与 OpenAI/Codex 登录 token 不同。

`runtime` 当前包含连接方式、runtimeId、generation 等诊断字段；`desktopShared=null` 与 `desktopVerification=requires_desktop_confirmation` 表示 Desktop UI 尚未确认。`capabilities` 当前不是完整功能支持矩阵，调用方不能因为某字段缺省就推断所有 Desktop 能力已可用。

添加项目时，省略 `folderName` 表示添加已有目录；提供它则在 path 下新建单层文件夹并添加项目。名称不能是 `.`、`..` 或包含路径分隔符、NUL。Git 信息中的 refs 最多包含当前 HEAD 和 200 条本地/远程分支；`worktreeRef` 使用其 commit ID，不传用户拼接的工作树路径。`outstandingWorkCount` 统计项目中的原生活跃会话，不包含 Relay 等待队列。

额度对象保留原生 `rateLimits` / `rateLimitsByLimitId`、窗口 `usedPercent`、`windowDurationMins`、`resetsAt` 等字段。缺失值表示尚无数据，不能推断剩余 100%。账户额度与下述单会话 `tokenUsage` 是不同数据。

## 会话、历史和输入

| 方法与路径 | 请求 | HTTP 200 响应 |
| --- | --- | --- |
| `POST /v1/threads` | `{submissionId,projectId,worktreeRef?}` | 提交记录；接受时 `{threadId}` |
| `GET /v1/threads/{threadId}` | 可选 `cursor`、`limit`（默认 30，钳制到 1–100） | `{thread,requests,settings,activeTurnId,status,readOnly,nextCursor,backwardsCursor,queue,plan,tokenUsage,changes,turnSettings}`；`thread.turns` 是本页原生 turns |
| `GET /v1/threads/{threadId}/history` | 同上 | `{turns,nextCursor,backwardsCursor}` |
| `POST /v1/threads/{threadId}/turns` | `{submissionId,text,model?,effort?,mode?}`，或用 `input` 数组 | 提交记录；接受时 `{threadId,turnId}` |
| `POST /v1/threads/{threadId}/steer` | `{submissionId,expectedTurnId,text}`，或用 `input` 数组 | 提交记录；接受时 `{threadId,turnId}` |
| `POST /v1/threads/{threadId}/interrupt` | `{submissionId,turnId}` | 提交记录；接受时 `{threadId,turnId}` |
| `POST /v1/threads/{threadId}/archive` | `{submissionId,archived:boolean}` | 提交记录；接受时 `{threadId}`；false 为恢复 |
| `POST /v1/requests/respond` | `{submissionId,key,response:{…}}` | 提交记录；接受时 `{requestId,threadId}` |

`requests` 项含 `{key,id,method,params,connectionId}`。使用原样 key 和原生 method 对应的 response；不自行生成审批默认回答。请求失效或另一端已回答时不能再次响应。`settings` 仅包含该原生版本返回的模型、effort、cwd、审批、sandbox 等设置；GET 会 resume/join 原生会话以取得实时状态，不能当作纯数据库读取。

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

`image` 也接受原生 `fileId` 字段，但该形式尚未真实执行验收；audio/localAudio 不在当前 HTTP 支持列表，不提供语音转写接口。节点路径不是 Android `content://` URI；系统选图由 App 转为图片 data URL，普通文件先上传取得 mention。输入保留原生结构，由原生运行时进一步校验。不传 model/effort 时沿用该 thread 的原生设置；不接受调用方任意替换审批策略、sandbox 或整套运行配置。

`mode` 可省略，或为 `plan` / `default`。Plan 通过原生 collaboration mode 运行，并将当前 turn sandbox 收紧为 `readOnly`；切回 default 仅在当前配置仍匹配 Relay 保存的 Plan 设置时恢复原先 sandbox，避免覆盖其他端修改。已有原生只读配置不会因此提升权限。`readOnly=true` 表示另一运行时占用了 thread writer，仍可读取持久历史。

归档前检查运行中的 turn、待回答请求和执行队列；存在未处理工作时拒绝，最终操作仍由原生节点校验。批量置顶、归档、恢复由客户端逐项提交独立 submissionId，没有全有或全无的批量事务。未读标记是 Android 按节点和 thread 的已读 updatedAt 本地记录，没有服务端未读字段。

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
| `GET /v1/threads/{threadId}/workspace` | Bearer | `{workspace:null}`，或 `{workspace:{id,path,sourcePath,commit,status,dirty,revision,archivePath}}` |
| `POST /v1/threads/{threadId}/workspace/cleanup` | `{submissionId,revision,discard?:boolean}`，默认 false | 提交记录；接受时 `{threadId}` |
| `GET /v1/threads/{threadId}/diff` | Bearer | `{diff:{patch,revision,files:[{path,added,removed,binary}],truncated}}` |
| `GET /v1/threads/{threadId}/review-context` | Bearer | `{branch,branches:[…],commits:[{id,subject}]}`，最近 30 个提交 |
| `POST /v1/threads/{threadId}/review` | `{submissionId,target:{…}}` | 提交记录；接受时 `{itemId}`，表示审查任务已入队 |

创建会话时指定 `worktreeRef`，Relay 在节点上建立独立 Git worktree，再以该 cwd 启动原生会话。只允许清理 Relay 创建并关联的工作树。清理必须使用最新 `revision`，会拒绝活跃、被占用或仍有队列的会话；有改动时必须显式 `discard=true`。清理先归档会话，再把改动目录打包到节点上的 `archivePath`，复核内容指纹后才移除 worktree。调用结果不确定时保留状态供核对，不自动重试删除；已移除或待核对的工作树不能回退到来源目录继续执行。

`diff` 对比 HEAD，包含已跟踪改动及最多 100 个未跟踪文件，patch 上限 2 MiB。`truncated=true` 表示不能据此视为完整差异；当前没有差异分页 cursor。`revision` 是该响应 patch 的摘要，与工作树清理的内容指纹 revision 不可互换。

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

## 文件、目录和附件

| 方法与路径 | 请求 | HTTP 200 响应 |
| --- | --- | --- |
| `GET /v1/directories?path=…` | 节点绝对路径 | `{path,entries:[{fileName,isDirectory,isFile,…}]}`；保留原生条目字段，最多 2,000 项 |
| `GET /v1/files?path=…` | 节点绝对路径 | 下述预览 JSON，文件最大 4 MiB |
| `GET /v1/files/download?path=…` | Bearer 和节点绝对路径 | `application/octet-stream`，单文件最大 1 GiB |
| `POST /v1/threads/{threadId}/attachments?name=…` | `application/octet-stream` 与明确 Content-Length，最大 1 GiB | `{type:"mention",name,path,size}` |
| `POST /v1/threads/{threadId}/attachments` | `{name,dataBase64}`；仍受 8 MiB JSON 请求限制 | 同上；兼容小文件调用 |
| `POST /v1/download-links` | `{path}` | `{url,expiresAt}`；expiresAt 为 Unix 秒 |
| `GET /v1/download-links/{ticket}` | 临时 URL，无需 Bearer | 与 `/v1/files/download` 相同的文件流 |

`GET /v1/files?path=<encoded-node-absolute-path>` 经同一原生 daemon 读取节点文件：

```json
{"path":"/node/path/notes.txt","size":53,"mimeType":"text/plain","encoding":"utf-8","text":"…"}
```

二进制响应将 `encoding` 改为 `base64`，返回 `dataBase64`。仅读取普通文件，超限 413，原生不支持 FS RPC 时 501。服务端先核对节点文件大小，再通过原生命令按最多 4 MiB 块读取，下载不会在 Relay 内存中拼接整份 1 GiB 文件。当前 HTTP 下载不提供 Range 续传。

文件上传写入会话 cwd 下新建的 `.rivo-attachments/<随机目录>/`，返回节点文件引用；不会直接发送给模型，调用方在后续 turns/queue 中加入该 mention。上传不使用 submissionId；丢失响应后重传会产生另一份文件。上传和下载共享有界传输槽，繁忙返回 429。

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

当前事件流是该认证节点的全局通知，`threadId` query 不做服务端过滤。它不是历史或持久消息队列：遇到 reset 必须重读当前原生快照，再合并后续通知。当前 Android 不保存 SSE cursor，重连走 reset/快照刷新。默认最多 4 条 SSE，超额 429；普通 HTTP worker 耗尽可能直接关闭新连接，不能假定所有容量拒绝均有 JSON 响应。

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

状态：waiting → dispatching → running → done。明确失败为 failed；不确定写结果或重启时的 dispatching 为 unknown，禁止自动重放。完成/撤回项不在快照中显示。`relay/queueChanged` 触发快照刷新。unknown 仅允许用户核对后移除，不允许编辑后再次派发。
