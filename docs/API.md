# Rivo Relay HTTP API

更新：2026-10-29；对应当前 `server/rivo_relay/api.py`。运行、配对 CLI 与构建方式见[README](../README.md)，真实能力边界见[开发计划](development-plan.md)。这是一层通向选定原生 Codex 节点的 API，不建立独立执行权限体系。

## 通用约定

- 正式连接使用 HTTPS。`GET /health` 与 `POST /v1/pair` 无需 Bearer；其余接口必须携带一个 `Authorization: Bearer <token>`。
- POST/PATCH 必须为 JSON object，`Content-Type: application/json`，提供单个 `Content-Length`；不支持 chunked request，JSON 请求上限 **8 MiB**，不接受 NaN/Infinity。
- 正常 JSON 响应为 HTTP 200，不使用 `ok=true` 外层包装；错误为 `{"error":{"code":"…","message":"…"}}`。客户端根据 code/status 判断，不依赖中文 message。
- URL 中的 ID、路径与 cursor 应正确编码。时间字段来自原生接口时保持原生值；Rivo 自有记录时间使用 Unix 秒。
- token、完整配对链接、正文和秘密回答不得写日志。连接令牌撤销后读写返回 401，已有 SSE 发 `revoked` 后退出。

## 健康、登录和目录

| 方法与路径 | 请求 | HTTP 200 响应 |
| --- | --- | --- |
| `GET /health` | 无 | `{"status":"ok"}`；仅说明 HTTP 活着，不代表原生已连通 |
| `POST /v1/pair` | `{code, deviceName}` | `{token, deviceId, node:{id,name}}` |
| `GET /v1/node` | Bearer | `{id,name,connected,capabilities,runtime}` |
| `GET /v1/projects` | Bearer | `{projects:[{id,name,path,roots,threadCount}]}`；包含无会话项目 |
| `POST /v1/projects` | `{submissionId,path}`，path 是节点绝对路径 | 提交记录；接受时 receipt 含 `projectId` |
| `GET /v1/models` | Bearer | `{models:[…]}`，保留原生模型字段，不使用固定清单 |
| `GET /v1/threads?projectId=…` | 必填 projectId | `{threads:[{id,name,preview,status,updatedAt,cwd,projectId}]}` |

配对码只由电脑本地 `pair` CLI 生成，没有远程生成码路由。默认 5 分钟且只兑换一次；大小写和分组连字符可归一化，过期/已消费/无效码均返回 401，限流返回 429。节点连接 token 与 OpenAI/Codex 登录 token 不同。

`runtime` 当前包含连接方式、runtimeId、generation 等诊断字段；`desktopShared=null` 与 `desktopVerification=requires_desktop_confirmation` 表示 Desktop UI 尚未确认。`capabilities` 当前不是完整功能支持矩阵，调用方不能因为某字段缺省就推断所有 Desktop 能力已可用。

## 会话、历史和输入

| 方法与路径 | 请求 | HTTP 200 响应 |
| --- | --- | --- |
| `POST /v1/threads` | `{submissionId,projectId}` | 提交记录；接受时 `{threadId}` |
| `GET /v1/threads/{threadId}` | 可选 `cursor`、`limit`（默认 30，钳制到 1–100） | `{thread,requests,settings,activeTurnId,status,nextCursor,backwardsCursor}`；`thread.turns` 是本页原生 turns |
| `GET /v1/threads/{threadId}/history` | 同上 | `{turns,nextCursor,backwardsCursor}` |
| `POST /v1/threads/{threadId}/turns` | `{submissionId,text,model?,effort?}`，或用 `input` 数组 | 提交记录；接受时 `{threadId,turnId}` |
| `POST /v1/threads/{threadId}/steer` | `{submissionId,expectedTurnId,text}`，或用 `input` 数组 | 提交记录；接受时 `{threadId,turnId}` |
| `POST /v1/threads/{threadId}/interrupt` | `{submissionId,turnId}` | 提交记录；接受时 `{threadId,turnId}` |
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

`image` 也接受原生 `fileId` 字段，但该形式尚未真实执行验收；audio/localAudio 不在当前 HTTP 支持列表。节点路径不是 Android `content://` URI；系统选图由 App 转为图片 data URL。输入保留原生结构，由原生运行时进一步校验。不传 model/effort 时沿用该 thread 的原生设置；没有 HTTP 接口替换审批策略、sandbox 或整套运行配置。

历史按原生 thread/turn/item ID 合并，不按文字去重。新建空会话可能还没有持久 rollout，首轮前返回创建元数据和空 turns；完成历史以原生分页为准。`nextCursor` 为空表示没有下一页，cursor 不应跨 runtime/重置继续使用。

## 提交状态与去重

所有原生写操作和待办交付返回：

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

`GET /v1/submissions/{submissionId}` 查询同一连接自己的记录，响应结构同上；其他连接看不到该查询结果。ID 全局唯一且绑定连接、操作、固定目标和请求摘要。同 ID 的相同请求返回既有记录，所有状态都不再次执行；改变正文/目标/归属复用 ID 返回 409。Android 不自动重发写操作。

原生写操作进入 claim 后，即使原生明确拒绝，也可能以 HTTP 200 返回 `status=failed`；必须检查提交状态。请求格式、认证或 claim 冲突等错误仍使用 4xx/5xx。`GET submissions` 当前只查已知持久状态，**不会自动对比原生历史并把 unknown 改成 accepted**；unknown 需结合原生身份/历史人工核对，不提供强制重试或一键丢弃未知执行的接口。去重记录无按时间删除策略。App 的“结束核对”是用户明确确认后的本地等待解除，不调用取消/重发接口，不修改服务记录或把 unknown 改成成功；提交 404 也不能自动推断原请求从未发生。

## 文件预览

`GET /v1/files?path=<encoded-node-absolute-path>` 经同一原生 daemon 读取节点文件：

```json
{"path":"/node/path/notes.txt","size":53,"mimeType":"text/plain","encoding":"utf-8","text":"…"}
```

二进制响应将 `encoding` 改为 `base64`，返回 `dataBase64`。仅预览普通文件；解码后最大 4 MiB，超限 413，原生不支持 FS RPC 时 501。它使用原生节点级文件访问能力，不受某个 turn 的 sandbox 参数约束，也不附加 Rivo 项目白名单。当前上游没有范围读取；4 MiB 是响应上限，不表示只从磁盘读取了 4 MiB。内部目录读取 helper 尚未暴露为 HTTP 目录接口。

## 待办

| 方法与路径 | 请求 | HTTP 200 响应 |
| --- | --- | --- |
| `GET /v1/todos` | 无筛选参数 | `{todos:[…]}`，未完成在前，创建时间倒序 |
| `POST /v1/todos` | `{id?,title,notes?}` | `{todo:{…}}` |
| `GET /v1/todos/{id}` | 无 | `{todo:{…}}` |
| `PATCH /v1/todos/{id}` | `title`、`notes`、`completed` 至少一个字段；不接受其他字段 | `{todo:{…}}` |
| `DELETE /v1/todos/{id}` | 无 | `{"deleted":true}` |
| `POST /v1/todos/{id}/dispatch` | `{submissionId,projectId,threadId?}` | 提交记录；接受时含 `todoId,threadId,turnId` |

Todo 当前字段：`id,title,notes,completed,projectId,threadId,createdAt,updatedAt,completed_at`；最后一个字段保留当前实际 snake_case 名称。标题去首尾空格后 1–200 字符，notes 最多 4,000 字符，客户端 id 最多 128 字符。省略 id 时服务生成；已有 id 相同内容返回原待办，不同内容 409。待办基础 CRUD 不使用 submissionId；原生交付使用。

省略 threadId 会在选定项目新建；提供已有 threadId 时验证其属于 projectId。这是请求目标一致性检查，不是独立权限限制。新建 thread 后先持久 checkpoint，再关联和发送。已有关联或已有 pending/unknown/accepted 交付时，新 submissionId 被拒绝，用户通过关联会话继续；明确失败且没有创建 thread 的交付可用新 ID 显式重试。回执存在时不重复创建 thread。派发消息包含当时标题与 notes，完成待办由用户操作。

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
| 413 | `request_too_large`、`file_too_large` |
| 429 | `pairing_rate_limited`、`stream_limit` |
| 501 | `native_method_unsupported`、`native_fs_unsupported` |
| 503 | `native_unavailable`、`native_timeout`、`native_protocol` 等 |

网络中断和 503 不表示原生写操作一定未发生。先查 submission 和原生会话，不能由传输层换 ID 自动重试。

## 0.1.4 置顶与执行队列

所有写操作继续使用认证及稳定 `submissionId`，返回原有提交状态回执。

- `POST /v1/threads/{id}/pin`：`{submissionId,pinned:boolean}`；操作原生 Pinned 分组。
- 会话列表新增 `pinned`；会话快照新增 `queue` 数组（id/text/status/revision/input/model/effort/turnId/error）。
- `POST /v1/threads/{id}/queue`：`operation=enqueue`，携带原生 `input` 和可选 `model/effort`，回执含 `itemId`。accepted 表示已持久入队，执行状态另看 queue/native turn。
- `operation=edit`：`itemId,revision,text`；只替换正文，保留非文本输入和设置。
- `operation=remove`：`itemId,revision`；撤回待发项或移除已核对的不确定记录，不中断已运行 turn。
- `operation=reorder`：`ids,expectedIds` 均为完整 waiting ID 列表；预期顺序过时则拒绝。
- `operation=steer`：`itemId,revision,expectedTurnId`；只把 waiting 项引导到该原生 turn，成功后不再排队执行。

状态：waiting → dispatching → running → done。明确失败为 failed；不确定写结果或重启时的 dispatching 为 unknown，禁止自动重放。完成/撤回项不在快照中显示。`relay/queueChanged` 触发快照刷新。unknown 仅允许用户核对后移除，不允许编辑后再次派发。
