# QiGuang 迁移清单

更新：2026-10-29。主要迁移已落地到 `server/` 和 `client/android/`。下表区分来源能力与当前实际目标；它不是整份源文件逐字复制清单，也不等于所有产品能力已验收。完成状态见[开发计划](development-plan.md)，当前职责见[架构](architecture.md)。

## 1. 来源与迁移规则

来源仓库：`/Users/ethan/Documents/QiGuang-Next`。本次调查时 HEAD 为 `f31333c`，工作区存在未提交修改，包含项目列表、项目接口和 Codex 相关文件。迁移时以实际选定的文件内容为准，记录源路径、HEAD、文件摘要与本地差异，不能把工作区内容全部归到该提交。

只读使用来源仓库，不移动、删除或改写 QiGuang 文件，不改变其运行服务。按能力抽取并记录来源和调整理由，不复制整套应用。已落地来源证据包括 [Android 文件摘要](../client/android/migration-sources.json)、[存储记录](migration-evidence-storage.md)与[原生历史来源/验证](native-validation.md)。Rivo 不要求兼容 QiGuang 私有 API、组件部署体系和旧数据库。

本次检查未发现仓库级 `LICENSE`、`NOTICE`、`COPYING` 或所查源文件的许可证头。迁移时保留可识别的来源及第三方声明，核对保留依赖的许可，不自行将原项目标记为 MIT/Apache 等公开授权。

## 2. 服务端迁移

下表源路径相对于 `QiGuang-Next/qiguang/server/`，目标相对于 `Rivo-Relay/server/rivo_relay/`。目标采用扁平能力文件与一个 `codex/` 包，不为每项功能再拆 service、store、controller 三层。

| 能力 | QiGuang 来源 | Rivo 目标 | 迁移处理 |
| --- | --- | --- | --- |
| 原生连接 | `qiguang_codex_desktop_bridge.py`、`qiguang_codex_app_server.py` | `codex/connection.py` | 参考并发请求、响应匹配和通知分发，当前仅连接指定既有 daemon 的 Unix WebSocket。Desktop bridge 宿主缺失，未迁该入口；没有独立 stdio 自动降级 |
| 原生历史 | `qiguang_codex_native_history.py` | `codex/history.py` | 作为直接迁移基础，保留分页、原生 ID 和完整 item；按实际原生协议校准，不引入 sidecar 历史 |
| 项目与工作树 | `qiguang_codex_projects.py`、`qiguang_codex_external_worktrees.py`；`qiguang_codex.py` 中项目归属方法 | `codex/projects.py` | 当前使用原生 project/list/read/create 与 thread/list，保留空项目及 projectId/根路径归属。没有迁入旧外部工作树推断、保底项目或额外白名单；不改写 Desktop 全局配置 |
| 会话和操作 | `qiguang_codex.py` 中列表、resume、submit、turn payload、停止相关方法 | `codex/sessions.py` | 保留原生身份与结构化输入，精简为直接原生操作；删除任务排队、强制接管和 `danger-full-access` / `approvalPolicy=never` 覆盖，沿用 Desktop 实际配置 |
| 原生交互 | `qiguang_codex.py` 中 `answer_interaction`、`_handle_interaction_request` | `codex/interactions.py` | 抽取提问、响应匹配与校验；补齐原生审批和双端先回答者生效；不增设 App 审批 |
| 增量与 SSE | `qiguang_codex_notifications.py`、`qiguang_codex_stream.py`、`qiguang_api.py::_codex_events` | `events.py` 与 `codex/connection.py` | 迁移有界通知与按序发送；流中断或缺口触发重读原生快照。删除依赖队列/sidecar 的 overlay，截断内容不能冒充完整历史 |
| HTTP 与启动 | `qiguang_http_server.py`；`qiguang_api.py`、`qiguang_codex_daemon.py` 的必要入口 | `http_server.py`、`api.py`、`app.py`、`__main__.py` | 已落地有界 worker、连接超时、JSON/SSE、TLS 和 CLI。保留资源边界，去掉 Gateway/组件 Unix HTTP 转发、upstream tracking 与无关路由 |
| SQLite | `qiguang_sqlite.py` | `database.py` | 迁移事务/savepoint、WAL、外键、超时和 schema 版本机制；在 Rivo workspace 建新库 |
| 待办 | `qiguang_todos.py`、`qiguang_data_daemon.py` 的待办方法 | `todos.py`、`api.py` 的待办路由 | 抽取基本 CRUD、客户端 ID 与事务；去掉视频格式、附件、提醒和其他未纳入首版的字段与依赖。Codex turn 结束不自动完成待办 |
| 配置 | `qiguang_component_config.py`、`qiguang_subprocess.py` 中必要 helper | `config.py` | 按 Rivo CLI 直接实现地址、TLS、workspace 和原生 socket 参数；仅参考必要配置边界，不迁组件配置继承、节点注册或多服务编排 |

HTTP helper 采用的来源 blob：`qiguang/server/qiguang_http_server.py` → `32a897ccf1c2d8158b11467cb1ce9c6b4dc8cd59`。该记录对应实际采用文件；不能因源仓库 HEAD 固定而忽略工作区差异。

### 服务端新增（已实现）

- `pairing.py`：二维码和手动码共用的短期一次性配对、长期连接令牌、撤销；电脑本地生成配对，不允许匿名远端生成。
- `submissions.py`：记录稳定提交 ID、请求摘要和原生回执，支持核对不确定提交。只解决重复执行问题，不迁 QiGuang outbox 和任务队列。
- `api.py`：统一的 Rivo 路由、身份验证与明确错误语义，不保留对旧 `ok=true` 包装的兼容层。
- `codex/files.py`：经已选原生 daemon 的 FS RPC 预览节点文件；响应上限 4 MiB，不用本地文件读取代替远端读取。
- TLS、workspace 独占锁、连接配置和脱敏日志。正式依赖仅 WebSocket 与二维码生成；认证模拟器相机依赖放在可选 `[emulator]` extra，不复制旧 requirements。

### 服务端不迁入

| 来源 | 原因 |
| --- | --- |
| `qiguang_gateway*.py`、`qiguang_node_*.py`、组件编排 | 首版只有一个 Relay 服务，无中央服务或多节点注册需求 |
| `qiguang_codex_store.py`、`qiguang_codex_schema.py`、`qiguang_codex_outbox.py` | 旧任务队列和复杂 sidecar 不属于 Rivo 数据模型 |
| `qiguang_writer_process.py`、`qiguang_codex_native_reprojection.py` | 不通过杀 writer、修写 rollout 或索引实现正常续接 |
| `qiguang_codex_live_overlay.py` 的队列投影 | 使用原生身份、快照与临时增量，不建立第二套权威运行记录 |
| 媒体、ASR/TTS、NAS、家庭设备、通用 Bot、Manager | 超出 Codex 与待办范围；不引入业务或发布控制面耦合 |

不整体复制 `qiguang_codex.py`、`qiguang_api.py`。它们是能力抽取的来源，不是 Rivo 的应用骨架。

## 3. Android 迁移

下表源路径相对于 `QiGuang-Next/qiguang/app/app/src/main/java/com/example/qiguang/`，目标相对于 `Rivo-Relay/client/android/app/src/main/java/dev/rivo/relay/`。

| 能力 | QiGuang 来源 | Rivo 目标 | 迁移处理 |
| --- | --- | --- | --- |
| 主题 | `theme/{Color,Theme,ThemeMode,Type,UiTokens}.kt` | `ui/theme/` | 保留亮暗色、字体、间距和圆角；首版保留默认品牌色并替换名称，去掉无关配色选项 |
| 项目首页 | `ui/main/HomeScreen.kt` 的 `HomePage`、`CodexProjectRow`、`CodexNodeSelector` | `ui/projects/ProjectsScreen.kt` | 迁移项目卡片、节点行和新建入口，沿用项目→会话导航；删除通用 Agent 依赖，不新增欢迎总览页 |
| 会话列表 | `ui/main/SessionScreens.kt` 的 Codex 分支 | `ui/sessions/SessionsScreen.kt` | 抽取列表、搜索、状态与新建入口；删除通用 Bot 和中央服务路径，不复制整文件 |
| 消息与执行内容 | `ui/main/{ChatTimelineContent,MarkdownRenderer,CodexActivityContent}.kt` | `ui/chat/{ChatScreen,MessageContent,MarkdownRenderer,CodexFileCitation,FilePreview}.kt` | 保留 Markdown、代码/文件引用、执行过程和原生 item 身份；去掉通用聊天与媒体库耦合 |
| 输入与原生交互 | `ui/main/ChatComposerContent.kt`，含 `CodexInteractionCard` | `ui/chat/{ChatScreen,NativeRequestPanel,McpForm,Attachments}.kt` | 迁移输入和提问布局，按原生 method/params 补审批与回答；移除手动 writer 控件。语音等独立扩展不进入本次迁移 |
| 项目/列表状态 | `ui/main/{CodexCatalogStateHolder,CodexSessionListStateHolder}.kt` | `codex/CatalogState.kt` | 保留各自状态 owner、当前目标和迟到响应防护；精简中央节点、Bot 与旧 API 身份 |
| 活动会话 | `ui/main/{CodexActiveSessionStateHolder,CodexActiveSessionCoordinator,CodexHistoryController,CodexEventController}.kt` | `codex/SessionController.kt` | 采用生命周期、取消和迟到响应防护；当前 SSE 触发原生快照读取，未逐字迁入旧 reducer/event controller 或队列/sidecar 协议 |
| 历史合并 | `data/CodexTimeline.kt`、`ui/main/{CodexTimelineReducer,CodexHistoryProjection,CodexSessionController}.kt` | `data/Models.kt` | 保留 thread/turn/item 身份、顺序与快照覆盖；仅保留服务实际提供的版本/游标字段，不编造旧 `control_version` |
| 提交 | `ui/main/CodexSubmissionCoordinator.kt`、`data/OperationContext.kt` | `codex/SubmissionCoordinator.kt` | 保留固定请求目标、稳定 ID 与回调防串会话；删除自动重发、外部 writer 禁用和手动接管。补进程重启后的未确认提交恢复 |
| 网络与数据 | `data/HttpTransport.kt`；`RepositoryContracts.kt`、`DataRepository.kt`、`ApiModels.kt`、`DataParsers.kt` 的相关部分 | `data/{RelayApi,Models}.kt` | 采用可取消 JSON/SSE，替换旧 URL、包装和错误结构；Bearer、TLS 与禁用自动重试/重定向已实现。当前各 controller 直接使用 RelayApi，没有额外 repository 层 |
| 待办 | `ui/main/{TodoController,TodoScreen}.kt`、对应 `TodoRepository` | `ui/todos/{TodosScreen,TodosState}.kt` | 迁移卡片、勾选、编辑和 CRUD 状态隔离；新增向选定项目/会话交付和关联。首版不迁分类、子任务、提醒、附件和离线同步 |
| 节点登录 | QiGuang 当前没有本产品要求的扫码授权首页 | `connection/{PairingParser,PairingRepository,CredentialStore}.kt`、`ui/login/{LoginScreen,QrScanner}.kt` | 新增扫码主入口、手动备用、同一解析器、配对交换和安全保存；登录后转项目首页 |

`App.kt` 与 `AppState.kt` 负责导航和节点生命周期，各业务 controller 拥有独立状态。没有整体迁移 `MainScreen.kt`、`MainScreenViewModel.kt` 或 `HttpQiGuangRepository`。保留独立状态 owner 和原生 ID 合并的边界，用少量实际文件实现，不增加通用 UI 框架或多 module 分层。

结构化输入、原生模型设置、审批/提问、文件和图片已进入实际实现与验证。MCP 普通表单采用明确子集；复杂扩展表单、userVerification 和音频采集仍在能力缺口中。删除旧媒体或语音耦合不等于可以遗漏 Desktop 原生能力；确有缺口的内容加入阶段 3 验收，不以“精简”为理由隐藏。

## 4. 测试随能力迁移

下表是来源验证思路，不表示这些原测试整份复制。Rivo 当前测试按自己的 HTTP、存储、原生协议与 Android 合同重新组织；真实 fixture、服务端测试和模拟器测试分开计数，不能替代 Desktop UI 验收。

| 来源 | 保留的验证 | 调整 |
| --- | --- | --- |
| `qiguang/server/test_qiguang_codex_native_history.py` | 分页、原生 ID、完整 item、错误传播 | 使用当前原生协议样例；去掉旧品牌命名 |
| `qiguang/server/test_qiguang_codex_desktop_bridge.py` | RPC 匹配、断连、超时和通知 | 只证明 adapter 行为；不能代替真实 Desktop 宿主验证 |
| `qiguang/server/test_qiguang_codex_project_trust.py` | Desktop 原生项目信任状态与生效回读 | 按实际原生接口保留或改写；不将 `trust_level` 等原生行为当作额外白名单删除 |
| `qiguang/server/test_qiguang_todos.py` | CRUD、ID、事务、关联 | 仅保留首版字段，不迁媒体/提醒测试 |
| Android `CodexTimelineReducerTest`、`CodexChatTimelineProjectionTest` | 增量与快照合并、重复及乱序 | 仅保留 Rivo 合同确实包含的字段 |
| Android `CodexActiveSessionStateHolderTest`、`CodexSessionListStateHolderTest`、`CodexCatalogStateHolderTest` | 切换会话、迟到结果、状态隔离 | 去掉中央节点与通用 Bot fixture |
| Android `HttpTransportTest`、`CodexSessionIdentityTest`、`TodoControllerTest` | 传输取消、身份与 CRUD | 改用 Rivo API；另测写操作断线不重放 |

新增测试集中于新行为：配对过期/一次性消费/撤销、二维码和手动入口一致、凭据恢复、未确认提交、原生交互竞争，以及真实模拟器端到端。仅 QiGuang 独立白名单、手动 writer、队列和离线同步测试随对应功能移除；Desktop 原生项目信任与审批的必要验证仍须保留。

## 5. 草稿已整理为当前工程

| 早期草稿/入口 | 当前落点 | 当前状态 |
| --- | --- | --- |
| `android/` 的 Gradle、manifest、资源 | `client/android/` | 已迁移为唯一客户端，debug/test APK 已构建安装；旧目录不保留 |
| `Pairing.kt` | `connection/PairingParser.kt` | 共用扫码/手动链接与地址校验，JVM 测试通过 |
| `CredentialVault.kt` | `connection/CredentialStore.kt` | Keystore AES/GCM；UI 测试核对存储恢复及密文 |
| `RelayApi.kt` | `data/RelayApi.kt` | 对齐真实 API、取消、认证、错误语义，关闭自动重试和重定向 |
| `RelayViewModel.kt` | `AppState.kt` 与各 controller | 登录生命周期和业务状态分开，非整份旧 ViewModel 迁入 |
| `scripts/qr-emulator-camera.py` | 保留原路径 | hash 路径避免纹理缓存，真实相机解码已验证，支持恢复默认海报 |
| 新增复现脚本 | `run-server.sh`、`verify-service.py`、`verify-emulator.sh`、`emulator-camera-pose.py` | 提供开发入口、HTTP 核对和认证相机姿态；fresh 扫码/会话/待办 3/3 通过，证据按最终验收记录 |

服务端单包已实现并完成隔离测试；Android 已实现并完成 JVM/UI、真实扫码/会话/文件/图片/待办及恢复流程；登录与待办阶段验收通过。文件存在、可构建、协议通过和完整 Desktop UI 验收是不同状态，具体见[开发计划](development-plan.md)。相机/相册测试走真实解码与系统选择器，没有预解码 intent 入口。

## 6. 明确不迁的数据

QiGuang 的待办数据库、客户端缓存、sidecar、outbox、事件日志、生产连接配置、密钥和发布产物均不迁入。Rivo 创建自己的数据目录和新待办库；Codex 历史通过原生接口继续访问，不复制、不修写。

首版待办只保留基本 CRUD、选定项目/会话执行与关联。分类、子任务、提醒、附件和离线同步延后，不建立空接口或占位数据库表。后续有明确需要时再加。

## 0.1.4 增补：置顶与待发队列

根据后续产品要求补充，覆盖上文初版不含队列的范围说明。

- 置顶参考 `qiguang/server/qiguang_codex.py::_pinned_section_id`、`thread/section/move` 及 Android `SessionScreens.kt` 长按操作。迁入原生分组语义和置顶优先展示，采用单项长按菜单，未增加批量归档等范围外操作。
- 执行队列参考 `ui/main/ChatComposerContent.kt::CodexQueuePanel`：前三项、展开、长按排序、编辑/撤回、引导。Relay 使用编辑弹窗保留附件和设置，不把待发项重新塞回主输入草稿。
- 服务以 `queue.py` + 一张 SQLite 表实现当前功能，复用原有认证、submission 去重和原生适配器；不迁旧 outbox、writer 接管、离线同步和历史 overlay。等待任务为 Relay 节点自身的数据，不宣称与 QiGuang sidecar 互通。
