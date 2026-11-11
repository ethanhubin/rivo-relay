# Rivo DSH 本机桥

将 Rivo 节点接到已经运行的 DSH Web/Desktop Host，复用原生会话、模型与工具。插件不启动另一套 DSH，不读写 DSH 历史文件，不持有独立 Agent writer。本轮目标契约为 DSH `0.2.2-rc.1`；升级后的兼容性必须重新验证，`status.supportedRuntimeVersion` 只是适配目标。

## 安装与边界

以 Host 插件加载 `index.mjs`，注入 `sessionController`、`typertGateway`、`connection`、`workspaceRegistry` 和 `agents`。例如在现有 profile 的 patch 追加：

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
| `projects` | 返回 DSH 工作区列表和归档/置顶身份 |
| `sessions` | 返回官方 `session/list` 结果 |
| `models` | 返回官方模型目录 |
| `create` | 原生 `workspaceId` 或 `cwd`，可附 `sessionId/agentPreset`；返回真实 `sessionId` |
| `snapshot` | `sessionId`、可选 `maxMessages/beforeSeq/throughSeq`；返回 `page/state/requests/generation/cursor` |
| `prompt` | `sessionId/requestId/text`；返回 `accepted:true,requestId`，只代表输入已接收 |
| `receipt` | `sessionId/requestId`；用持久消息 provenance 正证据确认接受，不按正文推断 |
| `cancel` | `sessionId/expectedTurn`，同步核对原生轮次后取消；拒绝取消已变化的任务 |
| `rename` | `sessionId/title` |
| `respond` | `key/outcome`；outcome 仅 `allowed-once/rejected/cancelled` |
| `permissionCatalog` | 当前运行时可选权限预设 |
| `permission` | `sessionId/preset`；只设置该会话，不改用户默认值 |

最新 `snapshot.page` 来自官方 `session/follow` 首帧，包含实时 assistant baseline；旧历史页来自官方 `session/events`。`state` 与历史是先后两次读取，消费者必须根据序号和之后事件校准，不能宣称两者是原子快照。历史保留原生事件信封和顺序。

`GET /events?generation=...&cursor=N` 长轮询最多等待 15 秒，直接返回 `generation/cursor/reset/events`。事件类型：`session`（原生 `event`）、`assistant-stream`（原生 `frame`）、`approval`（`request`）、`approval-resolved`（`key`）、`status` 和 `catalog`。历史缺口、未知代际、过期游标均要求 `reset:true` 后重读权威快照。缓冲限制 512 条及 4 MiB，单条超限也要求重新同步。

审批仅交给已被 Rivo 查看/创建/发送且事件轮询活跃的会话。Rivo 暂时拥有该请求的回答入口，其他会话仍走 DSH 原审批链。轮询超过 30 秒未继续时，撤销 Rivo 请求 key 并委托 DSH 原有回答链；该 key 的迟到回复失败。原生请求取消、插件卸载、已经回答也会使旧 key 失效。断开 Rivo 不会取消 DSH 正在执行的任务。

输入回执对应官方 `requestId`，不编造 `messageId` 或 `turnId`。重复提交是否已接受应通过 `receipt` 核对；扫描有上限，未查完会报不确定错误。`accepted:false` 仅表示所读持久事实中没有正证据，不构成安全重发授权。

## 验证

```sh
node --test integrations/dsh-bridge/bridge.test.mjs
```

测试覆盖 Unix socket 权限和 HTTP 信封、输入身份、官方快照入口、事件缺口、审批单次答复、失联退回、请求取消与持久 provenance 恢复。这些测试不代替实际 DSH 或 Android 扫码验收。
