# Rivo DSH 本机桥

将 Rivo 节点接到已经运行的 DSH Web/Desktop Host，复用原生会话、模型与工具。插件不启动另一套 DSH，不读写 DSH 历史文件，不持有独立 Agent writer。本轮目标契约为 DSH `0.2.2-rc.2`；已核实其官方 `list/state` 实现与 `rc.1` 一致，并通过真实 HTTP 聊天、审批和更新许可验证，见[记录](../../验证记录/DSH-Update-Readiness-2026-11-11/README.md)。此前模拟器完整闭环对应 `rc.1`，未在新版重跑；`status.supportedRuntimeVersion` 只是适配目标。

## 安装与边界

以 Host 插件加载 `index.mjs`，注入 `sessionController`、`typertGateway`、`connection`、`workspaceRegistry`、`agents`、`fileUploads` 和 `fs`。例如在现有 profile 的 patch 追加：

```yaml
- insert:
    - id: rivo-dsh-bridge
      name: /absolute/path/to/rivo-dsh-bridge/index.mjs
      config:
        socketPath: /Users/example/.dsh/rivo/bridge.sock
```

实际安装使用本机可维护路径，并先保留原 patch。已启用配置热加载的 Host 可增量挂载；替换源码后可递增该行的 `config.revision` 整数触发 HMR，并核实桥 `generation` 已改变。本插件不重启 Host。默认 socket 位于 `$DSH_HOME/rivo/bridge.sock`，未设置 `DSH_HOME` 时使用 `~/.dsh/rivo/bridge.sock`。父目录必须归当前用户且权限 `0700`，socket 权限为 `0600`。不会覆盖已有 socket；异常退出遗留 socket 需确认所属进程已停止后处理。

这条本机连接拥有 DSH owner 权限，只供同一 OS 用户下的 Rivo 节点使用。手机继续通过 Rivo 原有的一次性扫码、撤销与鉴权流程进入，不能直接连接该 socket。插件没有 TCP 监听。

## 协议

`POST /rpc` 输入 `{"method":"...","params":{...}}`，返回 `{"result":...}`；失败返回 `{"error":{"code":"...","message":"...","uncertain":true}}`。写请求在收到不确定错误或连接失去回执后不能自动重发。只有明确参数错误等本地拒绝才声明 `uncertain:false`。

| 方法 | 参数/结果 |
| --- | --- |
| `status` | 返回 `connected/generation/protocol/supportedRuntimeVersion` |
| `updateStatus` | 只读返回 `generation/cursor/idle`；核实当前 Host 可见会话是否空闲，未知或检查期间变化返回 `idle:false` |
| `projects` | 返回 DSH 工作区列表和归档/置顶身份 |
| `sessions` | 返回官方 `session/list` 结果 |
| `models` | 返回官方模型目录 |
| `selectModel` | `sessionId/selection`；原生一次选择 `provider/model/reasoningEffort`，仅改强度时从当前 Agent 读取模型路线 |
| `upload` | `sessionId/data/name?`；可选 `mediaType` 走原生图片上传，返回会话绑定的 `receiptId/file` 或 `receiptId/image` |
| `media` | `sessionId/eventSeq/blockIndex/imageIndex`；只读取该历史位置实际引用的图片，返回有界原始字节 `data/size/sha256/mediaType/name` |
| `create` | 原生 `workspaceId` 或 `cwd`，可附 `sessionId/agentPreset`；返回真实 `sessionId` |
| `snapshot` | `sessionId`、可选 `maxMessages/beforeSeq/throughSeq`；返回 `page/state/requests/generation/cursor/selected/media` |
| `prompt` | `sessionId/requestId/text` 或原生 `content`，可带 `selection`；返回 `accepted:true,requestId`，只代表输入已接收 |
| `receipt` | `sessionId/requestId`；用持久消息 provenance 正证据确认接受，不按正文推断 |
| `cancel` | `sessionId/expectedTurn`，同步核对原生轮次后取消；拒绝取消已变化的任务 |
| `rename` | `sessionId/title` |
| `respond` | `key/outcome`；outcome 仅 `allowed-once/rejected/cancelled` |
| `permissionCatalog` | 当前运行时可选权限预设 |
| `permission` | `sessionId/preset`；只设置该会话，不改用户默认值 |

最新 `snapshot.page` 来自官方 `session/follow` 首帧，包含实时 assistant baseline；旧历史页来自官方 `session/events`。`state` 与历史是先后两次读取，消费者必须根据序号和之后事件校准，不能宣称两者是原子快照。历史保留原生事件信封和顺序。

新功能以 `status.capabilities` 明确声明；旧桥没有声明时调用端必须关闭相应入口。`snapshot.media` 给出历史原生图片块及 Markdown 本地图片的事件位置；Markdown `start/end` 是源地址在文本中的 Unicode 码点偏移，调用端按此替换为鉴权媒体入口。桥下载时重读原生事件，只接受位置，不接受调用方提供的路径或附件引用。原生附件须验证哈希、格式和尺寸；本地图片读取检查前后版本。当前单文件与媒体上限为 20 MiB，内联图片合计 40 MiB、最多 20 张，RPC 请求上限 64 MiB。

历史文件通过与 DSH 聊天 `/api/file` 相同的原生文件服务解析，包括已引用的工作区外文件和符号链接；不按名称猜测替换为其他附件。支持 PNG/JPEG/WebP/GIF，以及由 Relay 完整验证的 BMP/ICO/AVIF/SVG；SVG 在桥内不解析、不执行，服务端负责有界验证和安全预览，下载保留原字节。原生附件下载的是 DSH 已保存的标准化图片。文件已删除而桌面仍有浏览器缓存，不代表原生文件接口还能重新读取；桥不宣称能恢复桌面缓存。

`selectModel`、`prompt`、`upload` 在同一桥会话内串行。模型与推理强度采用 DSH 的原生选择，成功后 DSH 还会异步保存为未来会话默认；选择成功而随后输入被拒绝时，已选模型仍保留。选择与发送不是跨客户端事务，原生桌面仍可并发更改。重复输入的已接受证据会在重选模型前检查。`content` 支持文本、内联图片或 `{type:'file',receiptId}`；图片内容和模型输入能力继续由原生准入核验。上传回执仅属于接收它的原生会话，输入进入历史后可能被回收，不能当作跨会话或永久文件路径。项目尚未有会话时须由 Relay 暂存，不能为上传隐式创建 DSH 会话。

`updateStatus` 从官方 `session/list` 取全体可见会话身份，要求各行 `agentAvailable/running` 为布尔且均未运行；`agentAvailable` 直接来自当前 Agent 注册表，不是可能滞后的列表投影。仅对有 Agent 的会话读取官方被动 `session/state`，要求无运行、队列或待回应输入，并检查原生全局 pending 与桥待审批均为空。没有 Agent 的历史会话不读历史，因此旧格式待迁移记录不阻止空闲判断。此调用不激活 Agent、调用历史/follow 接口、新增 watcher 或延长审批租约；读取期间发生会话/目录/状态变化，或字段缺失、原生读取失败、取消、桥关闭，均不放行。范围包含非 Rivo 发起的 DSH 当前工作，可能保守阻止更新。调用端还须设置短超时并复核连接代际。此结果是读取时的观察，不冻结 DSH，返回后电脑端仍可能启动新任务。

`GET /events?generation=...&cursor=N` 长轮询最多等待 15 秒，直接返回 `generation/cursor/reset/events`。事件类型：`session`（原生 `event`）、`assistant-stream`（原生 `frame`）、`approval`（`request`）、`approval-resolved`（`key`）、`status` 和 `catalog`。历史缺口、未知代际、过期游标均要求 `reset:true` 后重读权威快照。缓冲限制 512 条及 4 MiB，单条超限也要求重新同步。

审批仅交给已被 Rivo 查看/创建/发送且事件轮询活跃的会话。Rivo 暂时拥有该请求的回答入口，其他会话仍走 DSH 原审批链。轮询超过 30 秒未继续时，撤销 Rivo 请求 key 并委托 DSH 原有回答链；该 key 的迟到回复失败。原生请求取消、插件卸载、已经回答也会使旧 key 失效。断开 Rivo 不会取消 DSH 正在执行的任务。

输入回执对应官方 `requestId`，不编造 `messageId` 或 `turnId`。重复提交是否已接受应通过 `receipt` 核对；扫描有上限，未查完会报不确定错误。`accepted:false` 仅表示所读持久事实中没有正证据，不构成安全重发授权。

## 验证

```sh
node --test integrations/dsh-bridge/bridge.test.mjs
```

测试覆盖 Unix socket 权限和 HTTP 信封、输入身份、官方快照入口、事件缺口、审批单次答复、失联退回、请求取消与持久 provenance 恢复。这些测试不代替实际 DSH 或 Android 扫码验收。
