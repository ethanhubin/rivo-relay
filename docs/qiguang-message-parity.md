# QiGuang 消息解析与展示对照

本次对照 QiGuang 当前工作区的实际解析和 Compose 实现。来源仓库 HEAD 为 `f31333c248b862827a0179260ce5d9c571f441be`；`qiguang_codex.py` 存在未提交修改，因此以读取时的 SHA-256 为准。逐文件来源、摘要和调整记录见 [migration-sources.json](../client/android/migration-sources.json)。未修改 QiGuang 源仓库。

| QiGuang 来源 | Rivo 采用的规则 |
| --- | --- |
| `qiguang/server/qiguang_codex.py`：`_visible_user_text`、`_visible_session_text` | 原生问答回复显示为“问题”换行“答：回答”，多个问答分段，保留随后追加的文字；协议包装和 `questionItemId` 不进入有效回复正文。截断的会话标题/预览显示“已回答提问”。 |
| 同文件：原生 turn 投影、`_persisted_activity`、`_function_output_text` | 保留原生 turn/item ID 和顺序；区分用户、最终回复、过程消息、命令、文件变更、工具、分析摘要等；Hook 诊断不作为聊天内容；工具二进制/加密输出显示类型标签。 |
| `CodexExecutionProjection.kt`、`CodexActivityContent.kt` | 同轮相邻活动折叠为紧凑摘要，连续命令显示数量，运行中显示当前命令；用户、过程和最终消息保留在原位置。展开后再显示每步摘要、输出和可点击文件路径。 |
| `ChatTimelineContent.kt`、`MarkdownRenderer.kt`、`CodexFileCitation.kt` | 用户气泡最大宽度 326dp，用户与助手正文共用 Markdown；文件引用继续走节点只读预览；窄列长文本表格用卡片，短数据或宽表格用带表头和对齐的横向滚动网格。 |
| `ChatComposerContent.kt`、`AttachmentAddIcon.kt`、`SessionScreens.kt` | 最小 52dp、最多三行的输入区，分隔线与单条紧凑工具栏；图片/引用收在＋菜单，设置、运行中停止和发送/补充保持直接操作。 |

## 解析边界

Rivo 在客户端显示投影中采用这些规则，原生 `raw` 数据不变，也不会把历史回答当作新的审批或命令执行。界面读取显示投影，避免再次绕回原始协议文本。

问答解析只接受消息开头的完整已知包装和严格 JSON 字段，兼容紧凑及换行格式。代码围栏、行内代码、缩进代码、引用和嵌在普通文字里的示例不会被解包；畸形、缺字段、重复字段或未知负载保留原文。附件说明只匹配 QiGuang 的完整前缀与固定说明；Git policy 只移除完整末尾块，不截掉其后的用户文字。

与原实现相比，Rivo 不对整段用户文本做全局 HTML 实体反转义，也不凭一个不完整的 policy 起始标签删除后文。这些限制用于保留用户输入的代码和普通文本。

## 保留的差异

- 活动展开保留摘要、输出、状态和文件路径；尚未迁入 QiGuang 的阶段动画、精细耗时/变更统计及完整活动图标集。当前历史分组以原生轮次和消息边界为准。
- 文件与图片复用 Rivo 的原生节点 FS/预览接口；未复制 QiGuang 的媒体代理、下载/分享、音视频播放和业务附件系统。
- 发送区采用 44dp 触控目标和 80dp 发送按钮。writer、离线队列、语音及无关业务入口不在本次迁移范围内。
- 原生未知活动保留类型与原始详情；已知格式仅做显示归一，不推测未识别协议的含义。

另外修正了历史分页方向：`nextCursor` 才用于继续读取更早记录；`backwardsCursor` 是反方向锚点，不再用作“加载更早的消息”的回退值。

对应回归覆盖：完整/畸形/代码示例包装、原生数据不变、附加文字、会话预览、活动分组与消息边界、未知输出保留、表格分类，以及只有反向锚点时不出现早期历史入口。构建及模拟器验收结果由本次统一验收记录汇总。

## 0.1.6 工具图片归类修正

栖光 `qiguang_codex.py::_persisted_activity` 将 `imageView` 投影为 image 活动，标题“查看图片”、摘要来源为 path。Relay 原先把它排除在活动之外，ChatScreen 直接调用 NativeImage，导致开发时读取的验证截图插进正文，并在加载时出现图片进度条。

现将 imageView/image_view 纳入连续工具活动分组，摘要显示文件名，展开后点击路径才请求文件预览。移除正文 imageView 分支。用户消息附件与 imageGeneration 仍保持原有图片结果展示，原始历史不删除。
