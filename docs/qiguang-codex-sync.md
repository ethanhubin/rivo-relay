# QiGuang Codex 实现对照（2026-11-07）

## 对照版本

- 源项目：`/Users/ethan/Documents/QiGuang-Next`，`1642fc810f543a7995ca334e0e4916c20968d858`，2026-11-04 发布快照。
- 2026-11-07 实际读取 `git ls-remote --heads origin`：远端只有 `dev`，仍指向 2026-10-02 的 `8e0e67e745c2aacce63aa17225c657deff3342e4`。因此本次采用更新的本地业务源码。源目录的未提交文档与安装脚本改动没有复制到 Relay。
- 保留 Relay 工作区已有改动。源码来源 SHA-256 追加到 `client/android/migration-sources.json` 的 `syncPasses`，早期迁移记录保留原来源。

## 本次实现

| 范围 | QiGuang 来源 | Relay 行为及边界 |
| --- | --- | --- |
| 历史分页和收敛 | `CodexHistoryController`、`CodexHistoryProjection`、原生 history | 首页与更早页绑定原生 revision；收敛时保留已有内容并按原生身份合并可信实时项，继续更新增量和控制，只暂停分页与历史缓存；分页冲突重读首页；补齐可视区域没有原来的三页上限，并拒绝循环游标。 |
| SSE 和缓存 | `CodexEventController`、`CodexResponseCache` | 携带 Last-Event-ID 恢复事件；节点、会话和历史代际隔离；持久缓存去除审批、队列、恢复项和游标，不能恢复写权限。 |
| 列表与状态 | `CodexSessionListStateHolder`、`SessionScreens`、status projection | 运行中会话保持顺序；前台活动列表 15 秒、空闲 60 秒更新；展示待办来源、工作树和未读；执行、交付、恢复和 writer 状态分别投影。 |
| 待发送会话 | `CodexSubmissionCoordinator`、pending task | 按节点和项目保存多个独立 UUID 草稿，保留正文、附件、模型、思考强度、模式、快速模式和工作树来源；列表可搜索、继续、确认删除对应草稿；首条创建前立即显示气泡，发送前才建会话，保存创建/发送提交回执，重启核对原 ID；不确定结果不重新发送。 |
| 模型设置 | `SessionScreens`、capabilities | 模型切换保留共同思考强度；快速模式贯通普通任务、排队、恢复、草稿与原生 settings；当前会话设置经稳定提交 ID 保存；“设为默认”按节点保存；目录失败使用持久化的最近成功目录并显示来源提示；历史切换提示来自实际 turn 设置。 |
| 消息提交身份 | `CodexSubmissionCoordinator`、timeline reducer | 提交时立即显示待发送气泡；展示 overlay 与原生历史分离，按节点持久保存有容量边界的 receipt 和完整 input，接受后仍保留到 ready 原生历史按 `clientId` 精确接管；实时同 ID 先隐藏重复显示，尚未落盘时不删恢复数据；按 turn 与 item 锚点插入，旧轮不在当前页时不追加到最新回复后，不匹配正文；等待项以队列为显示入口；未确认提交不自动重放；晚到成功不能清除后来输入的内容。 |
| 提交前后草稿 | submission / pending task | 创建和发送 POST 前等待完整草稿落盘；发送还单独保存冻结的正文与附件，避免新输入合并覆盖待核对内容；接受回执绑定原目标和指纹清理，切走会话与进程重启都不能丢失未确认输入或清除较新草稿。 |
| 原生交互 | `CodexBottomPanelContent`、interactions | 问题/审批固定在输入区上方；待答期间 UI 和服务端均禁止普通任务绕过；草稿保留，逐题切换不丢答案；原生问题 ID 完整校验；异步问题只消费一次队首，结果不确定进入恢复。 |
| 执行队列 | `CodexQueueController`、`ChatComposerContent` | 保留排序 revision 校验；编辑/撤回成功后完整内容回到输入框；支持上滑展开、下滑收起；转引导失败恢复等待，结果未知不重放；讨论和 Review 不转普通 turn 引导。 |
| 中断恢复 | recovery control | 同时有后续队列时仍展示恢复项；“继续”建立检查现状并完成剩余工作的续接任务，保留模型/模式/快速设置，不复制旧指令、附件或 Review；“忽略”只解除阻塞；新活动轮不会被旧恢复项抢占。 |
| 写权限 | `CodexActiveSessionStateHolder`、writer status | 标题下常驻节点和 writer 归属；离线/过期隐藏操作；外部 writer 禁止新任务；确认与预览绑定原线程，迟到结果不会操作其他会话；缺失工作目录仅在确认原项目目录后显式重绑；实际进程交接继续采用 Relay 的已核实 PID 和影响范围票据。 |
| 过程与计划 | `CodexExecutionProjection`、`CodexActivityContent` | 过程正文保持原顺序；历史活动折叠、活动轮最新段保留逐项展示、相邻命令合并；终态收束运行指示；计划固定浮层和队列顺序保留；变更统计可展开逐文件查看。 |
| 文件差异 | `CodexReviewManager`、`CodexReviewController`、`CodexReviewScreen` | 服务端返回 file/hunk/line 投影；统一/文件视图与分页绑定 revision；409 刷新并尽量保留选中路径；旧页面结果丢弃；中文/空格/转义路径、删除、重命名、二进制、空 diff、不完整 diff 有明确表现；只有有效 native turn diff 生成统计。 |
| 审查只读核对 | `qiguang_codex_review` | 原生 Review 开始前保存 HEAD、index、工作区与状态指纹，终态时持久核对结果；工作区变化显示强提示，核对不可用明确说明，不假定都是审查造成，也不还原外部并发改动。 |
| 外部工作树 | `qiguang_codex_external_worktrees` | 在实际节点验证 Git 注册与 common-dir 后关联原项目；只对已验证且原路径确实缺失的记录保留关联；列表和工作树详情显示来源、提交与缺失状态，不接管外部工作树的清理。 |
| 未知执行结果 | outbox / native history | 队列重启后的 unknown 只按唯一原生 `clientId` 或已保存 turn ID 核对，历史收敛或身份歧义时保持未知，不以正文推测接收，也不重新执行。 |
| 项目添加 | 最新 `CodexProjectBrowserController`、project registry | 新建目录和使用目录都先确认准确节点和目标路径；确认后通过原生配置 API 保存该目录的信任；保存未验证则不登记项目；目录异步响应和确认不会跨浏览目标生效。 |
| 附件与预览 | `ChatTimelineContent`、附件/链接组件 | 单次最多 10 张图片，保留 JPEG/PNG/WebP 原始字节，单张 20 MiB、合计 40 MiB、JSON/草稿 64 MiB 总限；选择器、上传和打开结果绑定原会话；撤回队列保留附件；已查看图片活动直接打开预览；现有图片编辑、原生路径、Markdown、PDF、音视频和文件保存继续可用。 |
| 首条文件附件 | pending composer | 待发送页也可添加手机文件、视频与项目文件引用；上传以已选项目根为目标，沿用认证、大小限制和失败清理，不先创建原生会话。 |
| 附件上下文 | native turn payload | 原生 mention 保留为可见引用；UTF-8 文本从选定节点读取，以 `additionalContext.untrusted` 提供完整内容，单文件 4 MiB、总上下文 8 MiB；视频提供明确节点路径与检查/抽帧流程，二进制提供路径，不把附件内容升级成应用指令。普通发送、队列和引导沿用同一规则。 |
| 下载和大图 | `FileTransfer`、`ChatTimelineContent` | 文件保存、打开、分享和媒体预览支持按节点/凭据隔离的部分缓存与 Range 续传；强 ETag 校验，文件变化重新下载；已交给播放器或系统的缓存路径保持稳定。图片原图流式下载到磁盘，时间线按实际半屏像素解码且最长边不超过 1536 px，全屏单独解码，避免 4 MiB JSON 预览限制。 |
| 链接转发 | `CodexLinkAttachment`、`CodexLinkDestinationDialog` | 网页、文件、图片和媒体可选择目标项目/会话，写入目标草稿后跳转；新会话保留待发送状态；选择目标本身不下载文件或执行任务。 |

## 后续补齐：Desktop、原生历史与媒体

| 范围 | 当前实现及边界 |
| --- | --- |
| Desktop 桥接 | 移植 QiGuang protocol-1 认证 Unix JSON-lines 客户端，提供已有 AppServerConnection 的宿主适配模块；`auto / desktop / app_server` 模式、就绪/主机/原生 HOME 核对、按 thread 固定 writer、断线隔离、原生问题来源身份和结果未知不重放。状态明确区分桥接存在和 writer 可用。 |
| 原生历史水位与修复 | 在实际原生运行节点通过受限 helper 读取 rollout 生命周期水位、DB/文件指纹和 writer lock；空闲时使用隔离 CODEX_HOME 的官方 app-server 重投影。输入有大小/记录数边界，发布前重复核对代数、锁、文件指纹，保留备份并失败回滚；不复制 auth/config，不重执行旧任务。客户端收到失效事件后废弃旧分页。 |
| 进程交接 | 本地主机/原生 HOME、PID、启动时间、二进制和 socket 持有者必须全部吻合，ticket 绑定描述符并二次校验；共享 Desktop 不走 daemon 的本地强杀流程。多会话真实影响范围确认继续保留。 |
| 图片与媒体传输 | 原生路径经实际节点读取；公网图片/音视频经节点代理，DNS 地址固定并拒绝私网/重定向绕过，无节点 token 向源站转发。1536px WebP q82 v2 缩略图、内容版本缓存、原图 Range/ETag 与取消续传。Coil 缓存按节点与凭据隔离。 |
| 图片交互 | 移植源版入口/退出动画、半屏尺寸规则、双击缩放动画、双指缩放、边缘阻尼/惯性回弹、滑动翻页、相邻预览、原图渐入和长按菜单；保留保存、分享、转发、编辑。显示解码按屏幕2倍且每边至多4096px采样、内存缓存32MiB，编辑时卸载查看页；保存仍保留原始字节。 |
| 视频交互 | 移植 Media3 播放器，倍速、进度、双击跳转、长按2倍速、水平拖动、亮度/音量、缩放、方向与锁屏控制；停止/离屏释放、音频焦点、错误分类、重试与外部播放器入口。缓存播放不改变原始转发地址；音频同样使用源版Media3，支持进度/暂停、后台暂停、音频焦点与耳机拔出停止。 |
| 生成图片与媒体库 | 按原生 thread/turn/item 身份登记已确认生成结果；提示词标识原始/修订/未知，不推测未记录的模型请求。只查看不导入，明确收藏才导入；删除仅移除副本，tombstone 防止历史重扫复活。附件选择器可浏览、收藏、移除及重新附加，先下载/上传，不能把 Relay 私有缓存路径传给远端 Codex。 |
| 图片输入 | 最多10张 JPEG/PNG/WebP，单张20MiB、总计40MiB；编辑和草稿缩略图按显示尺寸采样并保留EXIF方向。提交前服务端分块落盘至实际原生节点，以 localImage 提交，不在原生 RPC 内塞入大型 data URI。 |
| 语音输入 | 源版麦克风位于输入框左侧；权限后需再次点按，16kHz PCM流式转写、光标插入、增量替换、停止尾块、发送等待final、取消/超时/缓冲上限、节点/会话/草稿代数隔离。通过可选现有ASR后端提供能力，未配置时显示不可用。 |
| 源码预览 | 引入源版 compose-highlight / Highlight.js 组件；保留行号跳转的精确行定位视图与复制功能，HTML文件仍禁用脚本、网络和文件访问。 |

## 保留的运行边界

这些差异不能标为“所有细节已经一致”：

- 当前仓库包含 Desktop 客户端与宿主适配模块，QiGuang 源仓库本身未包含宿主加载器。已安装 Desktop 没有发现该桥接加载入口；本次未修改或注入应用。需要宿主集成实际持有的 AppServerConnection 才能启用真实 Desktop 共享，普通 daemon 连接不冒称 Desktop 已接通。详见 [桥接说明](codex-desktop-bridge.md)。
- Relay 的进程交接可能影响同 PID 的多个会话，必须保留其真实影响范围确认；不能套用 QiGuang 每会话独立 writer 的释放承诺。
- Relay 继续使用认证配对、stable submission 核对和自己的队列存储。没有把 QiGuang sidecar 的旧任务、历史或 token 复制到 Relay；不会以相似正文匹配原生身份。
- ASR 只接入管理员配置的现有服务，不部署或下载模型；真实麦克风/ASR质量和真实 Desktop 双向接续仍需设备验收。通用助手、Manager、TTS自动播报和壁纸生成不属于本次 Codex 范围。源版 Codex 输入分支没有通用助手的自动TTS流程。详见 [语音说明](codex-voice-input.md)。
- 显示 overlay 独立持久化（每节点最多 64 条、64 MiB），它不构成第二份完整聊天数据库，也不能提供执行权限。源文档部分描述等待项同时出现在气泡和队列中，实际最新版 `CodexChatTimelineProjection` 已将 waiting 项只展示在队列，本次按实际代码迁移。

## 验证

编译、服务端回归、Android 单元测试和模拟器定向交互的最终结果记录在 `docs/validation.md` 的本次条目。截图和构建产物位于忽略目录 `workspace/artifacts/qiguang-codex-sync-20261107/`。

真实 Desktop 双向接续、多个实际节点、Windows 和真实设备的运行验收不由这些测试替代。本次没有更新 LaunchAgent、部署远端 Relay、发布或安装到实体手机，也没有提交或推送 Git。
