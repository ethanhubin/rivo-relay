# 接入已有 DeepSeek Harness 运行时

本方案让 Rivo 节点连接正在运行的 DSH Web/Desktop Host，Android 继续使用原有节点、项目、会话和审批界面。DSH 保持历史与执行权威，Rivo 负责扫码鉴权、移动显示和提交核对。一个验证节点使用独立的 Rivo workspace 与端口，避免替换现有 Codex 节点。

当前源码适配目标为 `0.2.2-rc.5`。2026-11-20 核对本机安装版本与官方 nightly feed 一致，运行中的旧桥仍声明 rc.2；本轮未部署源码或发布 APK。初次扫码与模拟器验收见 [rc.1 验证记录](../验证记录/DSH-Integration-2026-11-11/README.md)；此前 rc.2 的真实 HTTP 聊天、审批及更新前空闲核验见[验证记录](../验证记录/DSH-Update-Readiness-2026-11-11/README.md)，不能替代 rc.5 完整闭环验收。

## 先核实并更新实际运行时

检查桌面“关于”和官方更新入口，更新实际运行的 DSH 桌面/Web 包。旧全局 CLI 或 npm 缓存版本不一定是当前 Host 的版本。升级前保留 profile 配置；有活动任务时先按 DSH 自己的流程处理，不用结束进程来强制升级。

项目提供只读检查脚本：

```sh
python3 scripts/verify-dsh.py \
  --app '/Applications/DeepSeek Harness.app' \
  --bridge-socket "$HOME/.dsh/rivo/bridge.sock" \
  --relay-url http://127.0.0.1:18785
```

它读取安装包的 `Info.plist` 和官方更新 feed，核对桥的适配版本，再查询桥状态与 Relay `/health`。JSON 中任何检查失败会返回非零退出码。首次尚未安装桥/启动 Relay 时，相应检查失败是预期结果。脚本不安装、不修改 profile、不重启服务、不生成配对码、不清除 APP 数据，也不访问聊天历史。`/health` 本身不返回后端类型，必须同时核对下方 `--runtime dsh` 启动参数。

## 安装 Rivo Relay 插件

桥源码和 npm 包位于 [`integrations/rivo-dsh-bridge`](../integrations/rivo-dsh-bridge/DEVELOPMENT.md)。在仓库根目录打包，使用实际运行的 DSH profile 安装。桌面通常是 `desktop`，Web 通常是 `web`。

```sh
mkdir -p "$PWD/workspace/artifacts"
npm pack ./integrations/rivo-dsh-bridge --pack-destination "$PWD/workspace/artifacts"
dsh plugin --profile desktop add "$PWD/workspace/artifacts/rivo-dsh-bridge-0.2.1.tgz"
```

从旧版迁移时，先停用之前手动添加的 `index.mjs` 插件行，释放原桥 socket，再安装 bundle。确认「插件 → Rivo Relay」已启用。桥使用 DSH 当前 Host 的原生会话服务；桥接 socket 默认为 `$DSH_HOME/rivo/bridge.sock`，未设置 `DSH_HOME` 时使用 `~/.dsh/rivo/bridge.sock`。

桥接 socket 和管理 socket 都要求绝对路径，父目录归当前用户且权限为 `0700`，socket 权限为 `0600`。若有异常退出残留的 socket，先确认原进程已经停止。在待处理审批完成后更改桥配置，避免桥重载让旧审批退回 DSH 审批链。

节点更新前继续通过桥的 `updateStatus` 被动检查当前 Agent 的执行、排队输入和待回应请求。冷历史记录无需激活或迁移。无法读取状态、断连、旧代际或旧桥不支持此接口时，更新继续暂缓。

## 启动独立验证节点

先按仓库开发说明准备 Python 3.11+ 和已安装服务端依赖的 `.venv`。以下命令在仓库根目录执行，使用 Android 模拟器访问宿主机的标准地址：

```sh
scripts/run-server.sh \
  --runtime dsh \
  --dsh-bridge-socket "$HOME/.dsh/rivo/bridge.sock" \
  --workspace "$PWD/workspace/dsh-validation/relay" \
  --dsh-admin-socket "$HOME/.dsh/rivo/relay-admin.sock" \
  --host 127.0.0.1 --port 18785 \
  --url http://10.0.2.2:18785
```

此命令持续运行。`--dsh-admin-socket` 启用本机管理入口；若工作区路径较长，可将管理 socket 放到更短的同用户私有目录。DSH 节点只使用 DSH 运行时参数。

手机部署按[节点部署](node-deployment.md)为同一 DSH Relay 配置直连 HTTPS 和 VPS 中转，保留 DSH runtime、桥接路径、workspace 和端口。

## 手机配对二合一码

连接器就绪后，用该节点的 VPS 连接器命令加 `--attach --pair-only`，生成[配对二合一码](vps-gateway.md#手机配对二合一码)。在 Rivo 的连接设置中扫码。二维码 5 分钟有效，只能兑换一次。二维码和配对 JSON 留在 Git 忽略目录中。

Android APK 使用 VPS HTTPS 直链交付。开发构建命令：

```sh
cd client/android
./gradlew :app:assembleDebug
```

在测试设备上安装对应 APK 后扫码。没有必要清除现有 APP 数据；新增 DSH 节点即可。扫码后选 DSH 已登记的工作区和会话，或在工作区内新建会话。桥的 `create` 接受工作区或目录二选一；工作区外的验证会话可能归入 DSH 的未分组区域，不能据此断言项目列表里一定可见。

## 交互与恢复语义

- 文本聊天使用同一原生聊天界面。输入被 DSH 接收后以持久 `requestId` 确认，不把“已接收”伪装成已有执行轮次。
- DSH 原生工具审批显示为通用授权卡片，只提供允许一次、拒绝和取消；权限含义由 DSH 执行。测试审批使用专用会话的 workspace-write/ask，不能改变用户已有会话或全局权限。
- Rivo 轮询存活时承接已查看/发送会话的审批；轮询停止超过 30 秒会使旧审批 key 失效，并退回 DSH 原审批链。其他会话保持原有审批路径。
- 断线重连后读取 DSH 权威快照补齐历史。事件缓冲缺口要求 reset；手机未核对的缓存不授权执行。输入结果未知时只查持久接受证据，不自动重发。
- 创建前保存原生会话 ID 与项目绑定，回执丢失后只按这两个身份核对原生创建结果；旧记录没有身份时保留未知状态。桥只将已确认的执行前拒绝标为失败，阶段不明的原生异常仍保留未知状态。
- 未承接的原生问题和已交回 DSH 的审批显示具体待处理提示，需在 DSH 客户端完成；手机单独离线不会使仍在轮询的 Relay 审批租约失效。历史失败、重试诊断和实时思考内容使用 APP 现有消息展示。
- 停止操作携带期望轮次，在 DSH 进程内核对同一轮次后同步取消，避免旧停止按钮误取消随后开始的新任务。
- 历史图片保留原生附件与 Markdown 文件引用，复用 APP 图片预览、保存和分享。媒体引用绑定会话、事件及图片位置；读取使用 DSH 自身附件服务或文件预览 provider，支持其可读的已引用工作区外文件与符号链接。不会把 `media.read` 等同于任意文件读取权限。
- 模型和推理强度取自 DSH 原生目录及会话设置，不套用 Codex 的固定选项。DSH 的 `selectModel` 会同时保存未来会话默认模型，这是原生行为；模型变更与后续输入不是原子事务，输入失败后已选模型可能保留。Rivo 不展示 DSH 不支持的 Codex 快速模式和权限选项。
- 图片输入使用原生 image block，普通文件使用原生会话上传回执。新会话和已有会话的文件先放在 Rivo 私有暂存区，发送时绑定确切会话和提交 ID；项目附件须核对目标会话归属。未知上传结果不会自动重传，已接收输入仍按原生 requestId 核对。
- 节点通过 `capabilities.limits.maxAttachmentBytes` 声明附件上限，APP 在准备与读取文件时执行限制。历史图片定位跳过代码示例，引用式图片转换为内联展示；预览和下载独立于通用文件权限，编辑入口只对编辑器支持的格式显示。
- 本机测试包以独立包名安装并关闭正式更新；项目页和连接设置通过认证节点元信息持久化后端类型，显示 Codex 或 DeepSeek Harness 官方图标，不通过节点名称猜测后端。

### 普通与计划模式

桥检测到 DSH 的 `planMode` 服务时才声明 `session.plan`。APP 选择普通或计划模式后，在发送下一条消息时，桥先检查该 requestId 是否已经被接收，再通过原生 `planMode.set(agent, active)` 应用选择并提交消息；重复提交不重复改变模式。切换不改变沙箱或审批权限，消息投递方式仍为原生 queue，与计划模式是两个独立概念。

快照读取原生 active/pending 状态，冷会话读取 plan 投影；APP 在原生确认选择后跟随会话状态。DSH 不声明目标能力，因此菜单隐藏目标模式；暂时不可用但支持的模式仍保留禁用状态。计划完成后的 `plan-review` 在 APP 显示原生计划详情与选项，回答交回 DSH 决定是否退出计划模式。

接口依据：已安装 rc.2 的 `dsh-plan-mode/lib/types/index.js` 中 `get/set/selectedActive`，以及客户端计划界面。REA 静态证据 `ev_a67dc5ec07220f434ae09368894b1137a564614cf1e182334b1046c8d915a2ad`，ASAR SHA-256 `1535941c163698d68a0cf45dd06e1ce1a18a308108d9b84076074f35a6e9c39d`；分析限定上述计划模块及类型文件，不能替代真实运行验证。

此前计划切换验证：桥接 42 项、Python 51 项、Android 能力契约 7 项通过。

### 原生提问与计划确认

桥承接已查看会话的 `user-questions/request`：普通单选和自由文本问题复用 APP 回答界面；带 `approval` 或 `plan-review` 意图的单个问题复用审批卡片，保留原生详情与选项。服务端校验会话、运行时代际、请求版本，再映射为原生 `{answers:[{id,selected,custom?}]}`；桥再次核对当前 key、租约、完整问题 ID 和选项。计划确认不接受自由文本代替明确选择。

带原生超时的问题通过 `userQuestions.attachWait` 持有等待声明；回答、取消、桥关闭或 Relay 轮询租约到期时释放。租约到期交回 DSH 原处理链，旧回答不能再使用。手机单独离线不等于 Relay 租约停止，恢复 APP 时仍读取同一权威待办。多选、超过支持范围及已经转为后台继续的问题仍交由 DSH 处理，不从历史重建可执行请求。

接口依据为本机 rc.2 的 `dsh-user-questions` 服务与类型，REA 静态证据 `ev_e8203e464ba897d1bafe025ab363bca7e341fe503d31caa384c773aa502407d3`，ASAR 哈希同上。分析仅覆盖所选模块，不能替代真实运行验证。

本轮已合入 `origin/main` 的 `6ba4a17`。桥接 46 项与 Python 78 项回归通过，覆盖原生回答映射、HTTP 提交、代际/版本失效、断线交回和 Codex/DSH 共享交互契约；Android 能力和上下文展示单元测试及编译通过。未运行 lint、真机或模拟器验证，未更新运行节点或重新发布 APK，现有 local.4 不包含本轮计划与提问改动。

## 当前验证范围与限制

### 2026-11-20 主分支同步与适配补齐

已合入主分支 `ed0cbce`，保留连接兼容校验、会话状态同步、原生审批生命周期以及额度重置的幂等回执，同时保留多后端的能力限制与代际校验。原有未提交适配文件在合并前完整备份并恢复，未推送或发布。

- DSH 会话目录从原生待办事件和桥持有的请求投影 `pendingInput`，服务端映射到统一 `responseKinds`，使列表显示待回答／待授权；清除待办不冒充对未知会话的空闲确认，也不为列表读取额外打开历史。
- 冷会话 plan 投影的 `pending` 是切换标记，实时 `planMode.get` 的 `pending` 才是目标布尔值。桥分别归一化，覆盖 active/pending 四种组合，避免恢复后误显示模式。
- 节点兼容声明按后端支持能力过滤目标、待办派发和附件入口；Codex 的额度重置纳入能力校验，DSH 请求在调用原生方法前被拒绝。已移除的 Sites 不再声明可用。

rc.5 的 ASAR SHA-256 为 `6f019878f523428f452957dbaad601c6468051df4093eda8af751ec65226f88e`。本轮通过本地解包源码核对计划模式、原生提问与会话状态接口；REA CLI 的三个所选模块产生部分证据 `ev_eed78c39f1f04fe5536e8a5a0ece4c08e71967dd457101f974b7bc95c479317f`，但命令以 `cleanup_incomplete` 结束，不能记作完整分析成功。随后确认无遗留分析进程。静态观察不证明实际聊天执行。

验证：桥接 48 项通过；服务端回归覆盖 253 项，其中删除 Sites 后的旧断言已更新，相关 13 项定向复验通过；Android 能力、上下文、会话状态和更新隔离共 29 项通过，编译通过。未运行 lint、真机或模拟器；未重启节点、DSH 或 Codex，未发布测试包。

同日补充 Codex 保持性检查：服务端接管、writer 身份、审批并发、活动状态、回执及原生功能共 172 项，唯一失败为主分支已新增 `resetCreditSelectionSupported` 后未同步的旧断言；更新该断言后原生功能 12 项定向复验通过。Android 会话控制、原生设置展示、会话恢复与提交协调 67 项通过。此结论限于源码与自动化测试，尚未进行新版真机交互验收。

PR 合并前审查发现：统一审批回答入口未通知 Codex 节点活动表，且旧入口在不确定发送失败时也漏发通知；原生未立即发送解决事件时，列表可能保留待授权提示。新增两项回归先复现，修复为两个入口均在退出时通知活动表重新核验；相关审批、状态、writer 并发和 Codex 回归 187 项通过。

本阶段覆盖既有运行时的文本会话、流式显示、工具审批和恢复；具体通过/未通过项以[本次验证记录](../验证记录/DSH-Integration-2026-11-11/README.md)为准。只读检查脚本成功只能证明版本和连接状态，不代表扫码、聊天或审批闭环通过。

模型、推理强度、图片与普通附件输入、历史图片查看下载的实现及实测见[功能验证记录](../验证记录/DSH-Media-Inputs-2026-11-11/README.md)。原生桥必须明确声明新能力，旧桥继续关闭未实现的入口。

单个上传文件及历史图片当前限制为 20 MiB，图片输入合计 40 MiB。Rivo 暂存区上限 256 MiB，24 小时未使用的内容可清理；只清理 Rivo 自有文件，不删除 DSH 原生附件。历史图片支持 PNG、JPEG、WebP、GIF、AVIF、BMP、ICO、SVG；SVG 原字节下载保留，缩略图静态渲染并禁用外部资源。原路径消失时按 DSH 当前读取结果报告错误，不猜测另一附件是其替代文件；桌面 HTTP 缓存不等同于原生文件仍可读取。

当前仍未开放 Rivo 待发队列、目标模式、代码审查/差异、通用文件管理、历史普通文档下载、会话分叉/删除、Codex writer 接管等功能。未知非图片内容保留可见说明，不静默丢弃。较大的历史读取有界，超过适配窗口会报错，不静默返回不完整历史。

后端没有复制一份已完成聊天数据库；显示缓存与提交去重记录仍归 Rivo。上游接口迭代很快，每次 DSH 升级后应重新运行版本/连接检查及受影响的真实闭环验证。
