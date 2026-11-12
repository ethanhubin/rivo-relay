# 功能说明

## 已实现的入口与行为

| 范围 | Relay 当前代码行为 | 实现入口 |
| --- | --- | --- |
| 会话整理 | 最近/归档切换，单个会话置顶/取消置顶，长按多选、批量归档/恢复/永久删除（含确认）；运行中会话不允许归档；批量写操作逐个核对 | `CatalogState.kt`、`SessionsScreen.kt`、`SubmissionCoordinator.kt` |
| 未读与恢复 | 按节点保存会话已读更新时间；新更新出现未读点；项目列表/会话列表/最近历史使用私有缓存，缓存不代替权威历史或分页游标 | `CatalogState.kt`、`ConversationStore.kt`、`SessionController.kt` |
| 项目目录 | 浏览所选节点的目录、返回上级、选择已有目录；可在当前目录创建单层文件夹后注册项目 | `ProjectDirectoryDialog.kt`、`codex/developer.py` |
| 工作树 | 新会话从已提交 ref 创建独立工作树；开发工具展示路径、来源、提交、脏状态；清理绑定工作树状态，丢弃有改动的工作树需明确确认 | `NewConversationDialog.kt`、`DeveloperTools.kt`、`codex/developer.py` |
| 讨论模式 | 使用原生 Plan collaboration mode 与只读 sandbox；普通任务、排队与中途引导仍由 Relay 原有提交身份和队列管理 | `ChatScreen.kt`、`codex/sessions.py` |
| 代码审查 | 展示当前工作空间 diff，按文件筛选；原生 Review 支持未提交改动、基础分支、指定提交、自定义说明；审查评论支持文件与行号展示 | `DeveloperTools.kt`、`ReviewComment.kt`、`SourcePreview.kt` |
| 计划与用量 | 展示原生计划、轮次变更统计与上下文占用；读取账户额度，显示剩余比例、周期与重置时间；展示可用重置次数，确认后消费并通过原生幂等键核对未知结果；展示有对应观察的轮次模型 | `NativeProgress.kt`、`CatalogUsage.kt`、`UsageIndicator.kt`、`codex/observations.py` |
| 草稿与通知 | 草稿按节点/会话保存；任务完成与需要处理的交互可发本机通知；点击通知定位已授权节点和相应会话 | `ConversationStore.kt`、`TaskNotifications.kt` |
| 文件结果 | 源码行号/语法着色、Markdown 预览及相对链接；图片缩放、PDF 预览；通过认证下载后使用系统打开或分享 | `FilePreview.kt`、`SourcePreview.kt` |
| 待办详情 | 子任务、截止日期、本机提醒、按日/周/月/年重复、节点文件引用；附件通过文件浏览选择，交付时转换为原生 mention 输入 | `TodoDetailsEditor.kt`、`TodoReminderScheduler.kt`、`todos.py` |
| 待办离线修改 | 增改删先原子持久化到当前节点的 outbox，再串行同步；重启保留未确认操作；派发 Codex 前先成功同步并读取节点状态 | `TodosState.kt`、`ConversationStore.kt` |

Android 路径均位于 `client/android/app/src/main/java/dev/rivo/relay/`，服务路径均位于 `server/rivo_relay/`。完整 API 路由仍由 `server/rivo_relay/api.py` 定义。



| 已核对范围 | 源码参照 | 迁移或保留结果 |
| --- | --- | --- |
| 项目与会话列表 | `HomeScreen`、`SessionScreens` | 64dp 双行会话头部、2dp 额度条、实心搜索、最近/归档胶囊、左侧状态图标、单行卡片、62dp 新建悬浮按钮；会话页收起底部导航。长按选择、置顶、批量归档及缓存禁写继续按真实状态执行。 |
| 新会话与项目目录 | `CodexProjectBrowserDialog`、pending workspace settings | 全屏目录浏览、上级返回、隐藏目录提示、新文件夹单独确认、紧凑工作区/分支选择、加载/空/失败重试。提交错误显示在弹窗内，结果未知时仍能返回核对。 |
| 聊天输入与设置 | `ChatComposerContent`、`SessionScreens` | 常驻普通/讨论模式、运行中空草稿显示停止、非空草稿加入队列；设置浮层保留输入焦点，标题显示真实模型/思考强度/上下文。更多菜单保留原生会话 ID、置顶、归档与永久删除动作。 |
| 正文、链接与代码 | `SelectableReadOnlyText`、`ChatWebLinks`、`CodexReviewScreen` | 选择文本、全选/复制/引用浮栏、表格卡片与网格、网页打开/分享/保存；文件引用保留行号。Diff 支持统一/按文件、增删底色和新旧行号、二进制/截断提示；复制代码/补丁。 |
| APK 与文件 | `ChatResourceResolver`、`MainScreen`、`FileTransfer`、`ChatTimelineContent` | APK 使用“保存到本机 / 取消 / 打开”紧凑弹窗，普通外部文件可分享；打开菜单不读取/下载。文本、Markdown、禁用脚本的 HTML、图片、PDF、音视频各进入对应预览。下载可取消/失败重试，显示字节与进度；公开链接不携带节点凭据；APK 交给 Android 安装器确认。 |
| 图片与附件 | `ChatTimelineContent`、`AttachmentSourcePicker`、`AttachmentImageEditor` | 本地/节点来源卡片、多选照片/文件、独立视频入口、全屏节点文件浏览；附件预览与移除分开。图片放大/缩放/保存/分享与长按复制。图片编辑工具、裁剪、标注、撤销主体已与源逐段核对，保留 Relay 的草稿字节适配。 |
| 原生问答、计划与队列 | `CodexBottomPanelContent`、计划/队列组件 | 逐题 1/N 与选项卡，切题/折叠保留答案；输入区上方计划浮层、失败/中断状态；队列拖动反馈和分隔线，讨论/审查任务禁用不适用的引导。原生 request key、审批选项、scope、队列 revision 和提交核对不改变。 |
| 待办 | `TodoPage`、`TodoCard`、`TodoEditorDialog`、`ReminderDialog` | 清单底部编辑、回车连续新增、Codex 底栏先保存再选择目标、日历/时间/重复的确定取消；搜索覆盖子任务/附件，完成区与清单展开收起，动画勾选/删除线，长按多选和批删确认。修复连续删除时前一项回调仍处于 mutating 导致下一项被丢弃。 |


## 待办同步与重复语义

待办 CRUD 属于 Relay 自有状态，可安全按相同目标值重放：新建使用手机生成的固定 ID，修改使用明确字段值，删除的 404 视为已删除。每个操作在发出请求前写入独立的 `todos-state.json`，文件采用临时文件、fsync 与原子替换；它不参与普通显示缓存的容量淘汰。HTTP 成功后才从 outbox 移除。请求响应丢失时，下次同步仍使用同一待办 ID。

同步期间新编辑仍先写入本机，节点列表读取结果再叠加未确认操作，避免旧列表覆盖新编辑。待办页回到前台立即同步，停留页面时定期重试；后台停止页面轮询。服务器拒绝某项更改时保留本机修改并明确报错，用户可重试，或确认放弃尚未同步的本机更改后采用节点版本。多设备对同一字段的更新按节点实际接收次序生效，当前没有协同合并编辑界面。

Codex 派发不属于此 outbox。它继续使用既有 submission ID 与结果核对机制，未知结果不自动重发；只有待办同步成功、没有待同步项且待办未完成时，手机才继续派发。派发包含标题、备注、子任务清单和文件引用，保留原生会话关联；不会因为模型回复结束而自动完成待办。

重复表示**手动标记完成后生成下一次待办**。原项保留完成状态；下一次新项不继承 Codex 会话关联，子任务恢复未完成，日期按日/周/月/年推进，月末与闰日落到下一周期的有效日期。事务内使用来源项和下一日期确定新 ID，重放完成请求不会生成重复项目。提醒时间随日期按天平移；当前不单独保存日历时区，跨夏令时地区应重新检查提醒时刻。

提醒是 Android 本机的非精确闹钟，系统省电可能延迟；需要通知权限。记录在手机重启或应用更新后重新调度，删除/完成事项会取消提醒。它不运行 Codex，也不增加后台服务或系统精确闹钟权限。过去的提醒不会在每次列表刷新时重复补发。

## 保留边界

- 语音输入及 ASR/TTS 相关服务、模型和依赖不在本次实现中。
- 新的 Git、目录和工作树操作通过当前原生节点连接执行，沿用节点权限；不会把远端节点的项目误读为 Relay 宿主上的文件。
- Review 和 diff 页面没有暂存、撤销、自动应用修复、提交或推送按钮。
- 通知使用客户端连接和本机通知能力，不宣称应用进程被系统终止后仍有独立远程推送保障。
- 轮次模型依据 Relay 已接受发送的设置和原生 model/rerouted 通知记录。当前原生 Turn 没有 model 字段；没有观察记录的旧轮次不猜测模型，也不套用会话当前模型。
- 待办附件是节点文件引用，不是新的媒体库或上传服务；移动或删除原文件会使引用失效。文件解析和播放能力受原生节点权限、Android 解码器与已安装应用限制。
- macOS/POSIX 节点之外的 Git/工作树/目录流程需要单独设备验收；尚不能把编译或模拟响应检查算作 Windows 实机验证。

## 验证入口

新增或扩展的定向回归覆盖包括：

- `CatalogControllerTest`：项目/归档切换时旧响应隔离、已读版本与新完成状态。
- `CatalogUsageTest`：原生额度映射、缺失额度、消耗节奏与目录名边界。
- `TodoSyncTest`：新建响应丢失后持久恢复、同 ID 重放、派发离线阻断、删除 404 收束。
- `test_todo_details.py`：详情验证、月末/闰年重复、完成重放、会话关联不导致自动完成。
- `test_api.py`：Todo 详情 HTTP 往返、原生 mention 附件派发、已完成待办拒绝新派发、既有回执读取及升级前摘要兼容。
- 其它模块的 `ConversationStoreTest`、`TaskChangeTrackerTest`、`DeveloperPresentationTest`、`test_developer.py` 验证各自数据或展示边界。

测试、现有节点更新和 Android 安装结果以[验证记录](validation.md)为准。未执行的模拟器、真机、系统提醒和原生节点交互不能记为通过。


## 2026-11-07：连接与会话恢复复核（基于 0.2.21）


| 状态/边界 | 实现与回归证据 | 本轮结论 |
| --- | --- | --- |
| 前台恢复、手动重连、连接重置 | AppState / MainActivity；NetworkTransportTest、CatalogControllerTest | 0.2.21 已取消前置节点探测，重建只读传输并重开目录读取；后台读取与 SSE 独立调度，不取消或重放写入 |
| 慢历史读取与兜底轮询 | SessionController；slowSnapshotDoesNotQueueAnotherPollWhileTransferIsRunning | 修复定时器在慢请求期间积压下一次全量读取，改为读取结束后计时；事件仍可触发刷新 |
| 权限变化时旧请求阻塞 | SessionController；responseStartedBeforeInvalidationCannotRestorePermission | 取消已经失效的会话读取，并验证旧服务器响应仍阻塞时，新核验可以启动；保留权限代际检查 |
| 离线缓存、切换会话、迟到响应 | SessionControllerTest、NodeSessionSyncTest | 缓存只读，新鲜核验前不恢复操作；旧会话/旧权限响应不得覆盖当前状态 |
| 历史修复与分页竞态 | SessionControllerTest、test_native_runtime_identity | 历史深度恢复与当前权限读取分离；分页跨 revision 作废；保留现有 macOS 内核可执行路径身份修复 |
| 独立 writer、释放与接管 | test_owned_writer、test_handoff、test_native_convergence | 回归覆盖单会话释放不影响其他 writer、锁未释放不能报成功、迟到审批失效、接管确认绑定身份 |
| 请求结果未知、队列、审批 | SubmissionFailureTest、test_submissions、test_queue、test_owned_writer | 未知写入保留提交身份供核对；不自动重放；相同审批 ID 仍按 writer 区分 |
| 消息、媒体、UI | 全部 Android JVM 与服务端回归 | 本轮未改媒体与 UI 展示协议；JVM/服务端测试不能替代播放器、系统弹窗和生命周期真机验证 |


本轮自动化结果：服务端 437 项通过；独立源码快照 Android JVM 263 项通过，APK 构建与 lint 通过（43 warnings、3 hints，无 error）。验证日志与报告保存在 `workspace/artifacts/recovery-audit-20261107/`。独立快照避免与其他会话的设置页修改、Gradle 输出互相覆盖。

## Sites 与结构化资源

底部导航为 Codex、Sites、待办、设置。Sites 仅列出当前节点登录账号可见的站点，支持刷新和分页；点击在 App 内网页浏览页通过已配对 VPS 的 HTTPS 隧道打开正式地址（手机无需本地代理），没有详情页、搜索或写操作。未发布条目不可跳转。切换节点重新读取，刷新失败保留本次页面已加载列表；不持久化跨账号缓存。

会话通过原始 MCP/dynamic tool JSON 提取 Sites 和 HTTP(S) `resource_link`，实时与历史使用相同解析器。成功工具结果不等于发布成功：区分发布中、失败、正式地址及预览地址。同轮同一站点在最后相关工具组显示，工具组折叠时仍可点击；不同轮次保留各自入口。仅供 assistant 的资源链接不生成用户卡片，不支持的 URI 保留原始工具详情。跳转不携带节点凭据。网页浏览页提供返回、关闭、重试与外部浏览器入口，支持 JavaScript/网页存储，不开放本地文件访问或原生 JS 桥接；SSL 错误不绕过。普通卡片不再重复显示“打开网站/打开链接”，预览及异常状态仍保留。

Sites、Pages、文档、PDF、表格、演示文稿、GitHub、Slack、Gmail、Notion 使用本地图标，其余回退原有活动图标。图标资产来自本机 ChatGPT Desktop 26.1103.41825 的 `webview/images/ecosystem/apps/`，GitHub/Notion 包含深色版本；只提取资源，没有移植桌面执行代码。工具身份/已知图标元数据映射不触发网络加载，也不意味着连接器已经可用。完整 MCP widget、模板指令扩展、站点创建/编辑/发布不在此次范围。


## 额度主动重置（2026-11-11）

设置页将周额度和额度重置入口放在同一卡片，直接展示剩余比例、进度、重置时间及刷新入口，并在“额度重置”右侧显示可用次数（缺失时显示读取中或次数未知）；“额度重置”弹窗只展示可用次数和机会明细，不再重复展示额度窗口；主动选择某次机会后，提交前刷新并确认名称及有效期。无明细、已过期或节点未声明选择能力时不允许兑换；刷新使所选明细变化时需重新选择。所选机会 ID 随提交持久化，断线核对不会换用其他机会。兑换复用原生幂等键，持久提交记录支持断网及进程重启后的结果核对；账户身份变化时保留未知记录并提示恢复原账户。旧配对撤销后，新配对可触发节点核对旧操作；先原子封存新请求，再复用原兑换键恢复，刷新额度后需重新确认。重置成功后重新读取额度，读取失败保留旧值和成功提示，不自动继续任务。旧节点、次数未知、离线或有未确认提交时禁止新重置。

验证：本轮服务端额度专项 15 项及提交存储、恢复、原生行为回归共 82 项通过；Android 额度、提交、目录与刷新策略相关 60 项单测及 Debug 源码编译通过。此前已在独立模拟器用演示数据通过额度入口、确认取消与确认提交的 1 项 UI 测试，截图位于 `workspace/artifacts/quota-reset-settings-preview/`；本轮未重复模拟器测试，未做真机或真实账户额度重置验证，未运行 lint，未发布。

### 选择具体重置机会（2026-11-12）

客户端必须先选择某次可用机会，同一时间只选一条，再次点击已选机会可取消选择；未选择时禁止重置。确认框再次显示名称及有效期；无明细或刷新后所选机会失效时不能提交。节点向原生透传 `creditId`，与原账户、幂等键一起持久保留，重启或替换配对后的核对不会换用其他机会；旧客户端的无 ID 记录仍可恢复。

验证：服务端额度专项与提交存储回归共 36 项、Android 额度相关 11 项单测通过；最终 Debug APK 构建、签名校验及 OPPO 覆盖安装通过，用户已验收最终界面。早期交互方案的 2 项模拟器 UI 测试通过，最终布局未重跑模拟器；未进行真实额度兑换，未运行 lint，未正式发布。仅按授权更新了当前本机节点用于验证，范围及证据见[验证记录](../验证记录/Quota-Reset-Selection-2026-11-12/README.md)。
