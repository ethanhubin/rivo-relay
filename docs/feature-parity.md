# Relay 与 QiGuang Codex 功能补齐记录

本次以 QiGuang-Next 的 Codex 模块为功能参照，扩展 Relay 现有 Android 客户端和 Python 节点接口。QiGuang Manager、通用助手、媒体生成、NAS 与设备管理不属于比较范围。功能取舍以 Relay 自身的认证、原生 Codex 协议和节点权限边界为准。

允许扩展现有 Relay API；不新增 ASR、TTS、模型服务或额外部署服务。语音输入明确排除。本文记录代码行为与验证边界，部署及设备验收结果以[验证记录](validation.md)为准。

## 已实现的入口与行为

| 范围 | Relay 当前代码行为 | 实现入口 |
| --- | --- | --- |
| 会话整理 | 最近/归档切换，长按多选、全选、批量置顶/取消置顶、归档/恢复；运行中会话不允许归档；批量写操作逐个核对 | `CatalogState.kt`、`SessionsScreen.kt`、`SubmissionCoordinator.kt` |
| 未读与恢复 | 按节点保存会话已读更新时间；新更新出现未读点；项目列表/会话列表/最近历史使用私有缓存，缓存不代替权威历史或分页游标 | `CatalogState.kt`、`ConversationStore.kt`、`SessionController.kt` |
| 项目目录 | 浏览所选节点的目录、返回上级、选择已有目录；可在当前目录创建单层文件夹后注册项目 | `ProjectDirectoryDialog.kt`、`codex/developer.py` |
| 工作树 | 新会话从已提交 ref 创建独立工作树；开发工具展示路径、来源、提交、脏状态；清理绑定工作树状态，丢弃有改动的工作树需明确确认 | `NewConversationDialog.kt`、`DeveloperTools.kt`、`codex/developer.py` |
| 讨论模式 | 使用原生 Plan collaboration mode 与只读 sandbox；普通任务、排队与中途引导仍由 Relay 原有提交身份和队列管理 | `ChatScreen.kt`、`codex/sessions.py` |
| 代码审查 | 展示当前工作空间 diff，按文件筛选；原生 Review 支持未提交改动、基础分支、指定提交、自定义说明；审查评论支持文件与行号展示 | `DeveloperTools.kt`、`ReviewComment.kt`、`SourcePreview.kt` |
| 计划与用量 | 展示原生计划、轮次变更统计与上下文占用；读取账户额度，显示剩余比例、周期与重置时间；展示有对应观察的轮次模型 | `NativeProgress.kt`、`CatalogUsage.kt`、`UsageIndicator.kt`、`codex/observations.py` |
| 草稿与通知 | 草稿按节点/会话保存；任务完成与需要处理的交互可发本机通知；点击通知定位已授权节点和相应会话 | `ConversationStore.kt`、`TaskNotifications.kt` |
| 文件结果 | 源码行号/语法着色、Markdown 预览及相对链接；图片缩放、PDF 预览；通过认证下载后使用系统打开或分享 | `FilePreview.kt`、`SourcePreview.kt` |
| 待办详情 | 子任务、截止日期、本机提醒、按日/周/月/年重复、节点文件引用；附件通过文件浏览选择，交付时转换为原生 mention 输入 | `TodoDetailsEditor.kt`、`TodoReminderScheduler.kt`、`todos.py` |
| 待办离线修改 | 增改删先原子持久化到当前节点的 outbox，再串行同步；重启保留未确认操作；派发 Codex 前先成功同步并读取节点状态 | `TodosState.kt`、`ConversationStore.kt` |

Android 路径均位于 `client/android/app/src/main/java/dev/rivo/relay/`，服务路径均位于 `server/rivo_relay/`。完整 API 路由仍由 `server/rivo_relay/api.py` 定义。

## 0.2.1：按栖光源码核对现有 UI

0.2.0 的“功能入口具备”不能视为交互一致。0.2.1 以用户提供的 APK 弹窗、会话列表截图和 QiGuang-Next Android 源码逐项核对。所有修改位于 Relay 客户端，沿用已部署的 0.2.0 节点；没有新增服务、模型、客户端库或语音权限。源码参照位于 QiGuang-Next 的 `qiguang/app/app/src/main/java/com/example/qiguang/ui/main/`。

| 已核对范围 | 源码参照 | 迁移或保留结果 |
| --- | --- | --- |
| 项目与会话列表 | `HomeScreen`、`SessionScreens` | 64dp 双行会话头部、2dp 额度条、实心搜索、最近/归档胶囊、左侧状态图标、单行卡片、62dp 新建悬浮按钮；会话页收起底部导航。长按选择、置顶、批量归档及缓存禁写继续按真实状态执行。 |
| 新会话与项目目录 | `CodexProjectBrowserDialog`、pending workspace settings | 全屏目录浏览、上级返回、隐藏目录提示、新文件夹单独确认、紧凑工作区/分支选择、加载/空/失败重试。提交错误显示在弹窗内，结果未知时仍能返回核对。 |
| 聊天输入与设置 | `ChatComposerContent`、`SessionScreens` | 常驻普通/讨论模式、运行中空草稿显示停止、非空草稿加入队列；设置浮层保留输入焦点，标题显示真实模型/思考强度/上下文。更多菜单保留原生会话 ID、置顶与归档动作。 |
| 正文、链接与代码 | `SelectableReadOnlyText`、`ChatWebLinks`、`CodexReviewScreen` | 选择文本、全选/复制/引用浮栏、表格卡片与网格、网页打开/分享/保存；文件引用保留行号。Diff 支持统一/按文件、增删底色和新旧行号、二进制/截断提示；复制代码/补丁。 |
| APK 与文件 | `ChatResourceResolver`、`MainScreen`、`FileTransfer`、`ChatTimelineContent` | APK 使用“保存到本机 / 取消 / 打开”紧凑弹窗，普通外部文件可分享；打开菜单不读取/下载。文本、Markdown、禁用脚本的 HTML、图片、PDF、音视频各进入对应预览。下载可取消/失败重试，显示字节与进度；公开链接不携带节点凭据；APK 交给 Android 安装器确认。 |
| 图片与附件 | `ChatTimelineContent`、`AttachmentSourcePicker`、`AttachmentImageEditor` | 本地/节点来源卡片、多选照片/文件、独立视频入口、全屏节点文件浏览；附件预览与移除分开。图片放大/缩放/保存/分享与长按复制。图片编辑工具、裁剪、标注、撤销主体已与源逐段核对，保留 Relay 的草稿字节适配。 |
| 原生问答、计划与队列 | `CodexBottomPanelContent`、计划/队列组件 | 逐题 1/N 与选项卡，切题/折叠保留答案；输入区上方计划浮层、失败/中断状态；队列拖动反馈和分隔线，讨论/审查任务禁用不适用的引导。原生 request key、审批选项、scope、队列 revision 和提交核对不改变。 |
| 待办 | `TodoPage`、`TodoCard`、`TodoEditorDialog`、`ReminderDialog` | 清单底部编辑、回车连续新增、Codex 底栏先保存再选择目标、日历/时间/重复的确定取消；搜索覆盖子任务/附件，完成区与清单展开收起，动画勾选/删除线，长按多选和批删确认。修复连续删除时前一项回调仍处于 mutating 导致下一项被丢弃。 |
| 工具输出、连接与权限 | 工具活动组件、原生 approvals | 工具摘要、分组、等宽详情已核对，Relay 继续显示实际失败退出码。扫码连接、节点凭据、原生 writer/审批属于协议差异，未照搬栖光账户、Gateway/NAS 或强制接管控件。 |

仍有明确的结构差异：Relay 新会话先创建原生 thread，再进入输入页；会话列表尚无栖光 source/worktree commit 标记所需的完整数据；待办没有分类、手机 URI 附件或关联任务运行扫光；源代码高亮使用现有 Compose 词法实现，媒体使用 Android 自带解码器。没有对应数据或协议的显示不伪造，不把这些边界称为逐像素一致。站内转发依赖栖光收件体系，Relay 使用系统分享。

## 待办同步与重复语义

待办 CRUD 属于 Relay 自有状态，可安全按相同目标值重放：新建使用手机生成的固定 ID，修改使用明确字段值，删除的 404 视为已删除。每个操作在发出请求前写入独立的 `todos-state.json`，文件采用临时文件、fsync 与原子替换；它不参与普通显示缓存的容量淘汰。HTTP 成功后才从 outbox 移除。请求响应丢失时，下次同步仍使用同一待办 ID。

同步期间新编辑仍先写入本机，节点列表读取结果再叠加未确认操作，避免旧列表覆盖新编辑。待办页回到前台立即同步，停留页面时定期重试；后台停止页面轮询。服务器拒绝某项更改时保留本机修改并明确报错，用户可重试，或确认放弃尚未同步的本机更改后采用节点版本。多设备对同一字段的更新按节点实际接收次序生效，当前没有协同合并编辑界面。

Codex 派发不属于此 outbox。它继续使用既有 submission ID 与结果核对机制，未知结果不自动重发；只有待办同步成功、没有待同步项且待办未完成时，手机才继续派发。派发包含标题、备注、子任务清单和文件引用，保留原生会话关联；不会因为模型回复结束而自动完成待办。

重复表示**手动标记完成后生成下一次待办**。原项保留完成状态；下一次新项不继承 Codex 会话关联，子任务恢复未完成，日期按日/周/月/年推进，月末与闰日落到下一周期的有效日期。事务内使用来源项和下一日期确定新 ID，重放完成请求不会生成重复项目。提醒时间随日期按天平移；当前不单独保存日历时区，跨夏令时地区应重新检查提醒时刻。

提醒是 Android 本机的非精确闹钟，系统省电可能延迟；需要通知权限。记录在手机重启或应用更新后重新调度，删除/完成事项会取消提醒。它不运行 Codex，也不增加后台服务或系统精确闹钟权限。过去的提醒不会在每次列表刷新时重复补发。

## 保留边界

- 语音输入及 ASR/TTS 相关服务、模型和依赖不在本次实现中。
- 新的 Git、目录和工作树操作通过当前原生节点连接执行，沿用节点权限；不会把远端节点的项目误读为 Relay 宿主上的文件。
- Diff 目前是有大小上限的统一 patch 和文件摘要，不等同于 QiGuang 的结构化 revision/hunk/line 分页接口。较大 diff 会提示截断。
- Review 和 diff 页面没有暂存、撤销、自动应用修复、提交或推送按钮。
- 通知使用客户端连接和本机通知能力，不宣称应用进程被系统终止后仍有独立远程推送保障。
- 轮次模型依据 Relay 已接受发送的设置和原生 model/rerouted 通知记录。当前原生 Turn 没有 model 字段；没有观察记录的旧轮次不猜测模型，也不套用会话当前模型。
- 待办附件是节点文件引用，不是新的媒体库或上传服务；移动或删除原文件会使引用失效。文件解析和播放能力受原生节点权限、Android 解码器与已安装应用限制。
- macOS/POSIX 节点之外的 Git/工作树/目录流程需要单独设备验收；尚不能把编译或模拟响应检查算作 Windows 实机验证。
- 手动改名、分叉会话、跨节点迁移、通用助手和其它 QiGuang 业务功能不因本次补齐而自动具备。

## 验证入口

新增或扩展的定向回归覆盖包括：

- `CatalogControllerTest`：项目/归档切换时旧响应隔离、已读版本与新完成状态。
- `CatalogUsageTest`：原生额度映射、缺失额度、消耗节奏与目录名边界。
- `TodoSyncTest`：新建响应丢失后持久恢复、同 ID 重放、派发离线阻断、删除 404 收束。
- `test_todo_details.py`：详情验证、月末/闰年重复、完成重放、会话关联不导致自动完成。
- `test_api.py`：Todo 详情 HTTP 往返、原生 mention 附件派发、已完成待办拒绝新派发、既有回执读取及升级前摘要兼容。
- 其它模块的 `ConversationStoreTest`、`TaskChangeTrackerTest`、`DeveloperPresentationTest`、`test_developer.py` 验证各自数据或展示边界。

测试、现有节点更新和 Android 安装结果以[验证记录](validation.md)为准。未执行的模拟器、真机、系统提醒和原生节点交互不能记为通过。
