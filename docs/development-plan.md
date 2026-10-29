# Rivo Relay 开发与验收计划

更新：2026-10-29。`server/` 与 `client/android/` 的主要实现已落地，已从架构规划进入真实联调和验收收尾。阶段编号保留原来的依赖关系；实现完成与验收完成分开记录，不因先做了可独立验证的模块而视为上游验收已通过。真机反馈后的消息解析、工具活动、发送区和刷新修复见[消息展示对照](qiguang-message-parity.md)与[验收报告](validation.md)。

依据：[产品说明](product.md)、[当前架构](architecture.md)、[迁移清单](migration-plan.md)、[HTTP API](API.md)。

## 当前状态

| 阶段 | 实施状态 | 验收状态 |
| --- | --- | --- |
| 0 架构与迁移准备 | 已完成 | Python 单服务、Compose 单模块、来源和数据边界已明确 |
| 1 原生接入与 Desktop 延续 | 既有 daemon 单路径已实现 | 双原生客户端同 runtime 操作已通过；Desktop UI 双向接续待用户协助 |
| 2 服务、登录与项目 | 已实现 | 验收通过：真实扫码/手动、拒绝相机权限后的备用入口、凭据恢复、离线、撤销和 HTTP/TLS 边界 |
| 3 完整会话延续 | 主会话、原生交互、结构化输入和文件预览已实现 | 真实文本会话通过；原生文件/五类输入通过；Android 原生文件预览和系统选图整例通过，复杂原生交互有缺口 |
| 4 待办闭环 | 已实现 | 验收通过：fresh CRUD/真实交付/完成/删除整例及 Relay 重启、App 冷启动后关联会话恢复 |
| 5 模拟器与跨端验收 | 构建、测试和复现脚本已具备 | 模拟器主要流程及脚本整跑通过；Desktop UI 与当前版本已知原生能力差异尚未全部完成 |

当前结果快照：服务端 **78 项测试通过**（Python 3.13，1.835 秒，含同 cwd 不同 projectId 的隔离回归）；Android **18 项 JVM 测试、11 项 ClientUiTest 通过**，lint **0 errors、20 warnings**。11 项 UI 在 360×640 dp 小屏亮色和默认尺寸暗色下均通过，另有实际相机权限拒绝测试通过。包含“结束核对”不会发送新 HTTP/触发旧回调的回归；完整结果见[验收报告](validation.md)。

`verify-emulator.sh` fresh 扫码 → 文本会话 → 完整待办流程 **3/3 通过**，附件在 `workspace/artifacts/emulator-final/`。手动授权、系统相机权限拒绝后的手动入口、系统 Photo Picker、节点文件预览、离线保留登录、撤销回授权页、服务重启与 App 冷启动后的已有待办关联恢复均有真实通过记录。服务完整 native 写入验证 `service-e2e.json` 和最终只读复核 `service-final-read-ok.json` 已通过。详细原生证据见[原生验证记录](native-validation.md)，完整日志、截图和交付状态见[验收报告](validation.md)。

## 阶段 0：架构与迁移准备

- [x] 固定 `server/`、`client/android/`、`docs/`、`scripts/`、忽略的 `workspace/`；旧 Android 草稿已整理到新目录。
- [x] 固定首页：无登录节点是授权页；已有登录节点是项目列表 → 会话列表 → 会话。扫码主、手动辅，已登录浏览沿用 QiGuang。
- [x] 固定原生权限、历史归属，以及连接/待办/提交的独立存储边界。
- [x] 建立来源表，记录存储模块摘要与原生历史来源；仅迁源码，不迁旧库、缓存或服务配置。

## 阶段 1：原生接入与 Desktop 延续

- [x] 实现已有 Codex daemon 的 Unix WebSocket JSON-RPC；不生成 runtime、不自动降级 stdio、不停止 writer。
- [x] 真实核对原生项目、空项目、thread/turn/item、分页、模型、增量、steer、interrupt、审批、提问与重连。
- [x] 在同一个既有 daemon 上验证两个独立客户端，保留 request ID/generation；一端回答后另一端旧响应失效。
- [x] start/resume/send 不注入审批或 sandbox 覆盖；新建使用项目原生根路径，设置以原生回读为准。
- [x] 锁定当前所需 Python、WebSocket、二维码依赖及 API；保留不支持的原生版本/方法的明确错误。
- [ ] 用户在 Desktop 中打开隔离项目，确认 App 发起的线程/结果可见，并实际完成 Desktop → App、App → Desktop 的双向续接。
- [ ] 按当前 Codex 0.159.x 能力对照验收并处理已知差异；不能用相同目录或双原生客户端替代 Desktop UI 证据。

Desktop UI 自动化工具明确禁止操作当前应用，当前没有绕过限制使用深链、AppleScript、注入或其他界面控制路径。`desktopShared=null`/`requires_desktop_confirmation` 是当前真实状态；剩余 UI 验收需要用户手动协助。完整延续仍是验收目标，没有降级为独立执行。

## 阶段 2：服务、登录与项目闭环

- [x] 单服务、16-worker 有界 HTTP、4 条 SSE 上限、健康接口、TLS、workspace 独占锁和 SQLite 四表落地。
- [x] 本地 CLI 生成配对；5 分钟一次性码、摘要存储、原子兑换、来源/全局限流、连接列表和撤销。
- [x] debug 本机 HTTP 与 release HTTPS 边界分开；真实 TLS 测试验证显式 CA 信任及错误 hostname/CA 拒绝。
- [x] ZXing 实际相机扫码、手动同一解析器、Keystore、凭据恢复、项目/会话读取和空状态已实现。
- [x] 模拟器 virtual-scene 注入新生成 QR，经实际相机解码后进入项目列表，已留截图。
- [x] 隔离 HTTP 测试覆盖过期、重复/并发兑换、撤销已有 SSE、无效凭据和限流；UI 测试覆盖手动输入及首页导航。
- [x] 离线启动保留已登录首页的真实 UI 用例通过；临时原生转发断开显示 503，恢复转发后无需重启 Relay 可再次读取项目和文件。
- [x] 电脑 CLI 撤销当前模拟器连接，冷启动后回到扫码授权页的真实 UI 用例通过。
- [x] 新码通过手动入口实际配对，返回真实项目列表的整例通过。
- [x] 实际系统相机权限弹窗选择拒绝，仍能进入手动备用入口（2.283 秒）。
- [x] Relay 重启及 App 冷启动后恢复连接，打开已有隔离待办的关联会话并读取原结果（3.197 秒）。

本阶段按实际节点项目与原生会话验收通过，没有额外权限配置；release 不继承 debug 明文例外。mock、解码结果 intent 和单独解析器测试不能算真实扫码验收。

## 阶段 3：完整会话延续

- [x] 原生新建/继续、分页、状态、模型/effort、steer/stop、消息和工具结果展示；SSE 通知触发快照刷新并按 ID 合并。
- [x] 固定目标和稳定 submission ID；服务在 dispatch 前持久化 claim，身份回执与未知状态保存；客户端写操作不自动重试。
- [x] 重启 pending → unknown，保留 checkpoint 和长期去重信息；同 ID 同请求不再次执行，改变请求返回冲突。
- [x] App 提供查看已知原会话与显式“结束核对”：确认后只解除手机等待、清除旧回调，不取消/重发、不删服务记录；对应第 11 项 UI 回归已通过。
- [x] 原生提问、秘密回答、命令/文件审批、policy amendment、权限请求，以及 MCP 基础表单/URL 已有 UI；未知结构明确回 Desktop。
- [x] 服务支持 text/image/localImage/mention/skill 输入；Android 系统选图、内存压缩、节点引用、图片展示和文件预览已实现。
- [x] 在隔离原生任务中验证五类结构化输入、文本/图片文件读取与权限设置前后一致；Android 真实文本 roundtrip 已整例通过。
- [x] Android 系统 Photo Picker → 图片提交 → 原生返回 `RED` 整例通过；文件引用 → 原生 mention → `/v1/files` → `RIVO_NATIVE_FILE_PREVIEW` 展示整例通过。
- [ ] 处理当前 0.159.x 已知差异：`openai/userVerification` 原生签名挑战、`openai/form` 超出标准扁平表单子集的 schema，以及 audio/localAudio 采集 UI。当前不支持的结构明确回 Desktop，不承诺覆盖未来未知协议。
- [ ] Desktop UI 双向同时打开、提问/审批竞争与执行中交接。

这些待办是能力缺口，不是 App 权限政策。不能因为有“请回 Desktop”提示就把完整延续标记为通过。`accepted` 只代表原生接受操作；网络不确定时保留待核对状态，不给用户暗示可以自动重发。目前核对接口只查询持久记录，unknown 不会自动比对原生历史并补写接受结果。

## 阶段 4：待办闭环

- [x] 基础新建、编辑、搜索、完成、删除；不迁分类、子任务、提醒、附件、离线同步和旧数据。
- [x] 选项目/已有或新会话交付，稳定关联；新建 thread 后先 checkpoint，再 link/send，避免崩溃后丢失已创建身份。
- [x] 同 todo 两个不同提交竞争只能一个 create/send；已有 thread 或未确认交付阻止新提交，确认失败且未创建 thread 时可显式重试。
- [x] 从关联待办继续原生会话；完成由用户操作，turn 完成不自动完成待办。
- [x] 存储/HTTP 隔离测试通过；模拟器实际交付并得到 `RIVO_TODO_OK`。
- [x] fresh `todoCrudAndRealDispatch` 全流程通过：编辑、交付、真实回复、返回待办、手动完成、删除及本次测试数据清理。
- [x] Relay 重启 + App 冷启动后，打开已有隔离关联待办并读取原结果通过；没有生成新提交或重复任务。

本阶段验收通过。

## 阶段 5：最终验收与交付

- [x] Python 服务测试、Android JVM/UI 测试、debug/test APK 构建、lint 已运行。
- [x] `run-server.sh`、`verify-service.py`、`verify-emulator.sh`、相机注入/恢复脚本以及运行/API 文档已提供。
- [x] 真实扫码和文本会话截图已保存；服务协议样例使用脱敏原生 fixture。
- [x] 待办完整整例、结束核对 UI 回归、HTTP 完整 native 写入与最终只读复核、模拟器 fresh 脚本整跑均通过。
- [x] 实际离线/撤销、重启关联恢复、系统权限拒绝备用入口，以及小屏亮色 UI 已通过；迟到/重复通知和原生请求失效另有协议/单元测试。默认尺寸暗色复跑与最后界面核对结果在验收报告记录。
- [ ] 用户协助完成 Desktop 双向验收；当前版本已知原生能力差异解决后再宣布“完整延续”通过。
- [x] 相机姿态、海报、尺寸、密度和夜间模式恢复原值；明文连接仍仅限 debug 配置，没有改动用户原有服务。
- [x] 完成初轮报告/APK 与脱敏附件交付核对；清理 4 条残留测试待办和 3 个旧测试连接，保留最新模拟器连接。初轮配对材料与临时进程已清理；后续真机测试已重新启动专用 Relay/SSH 转发并保持运行，保留手机已有登录。隔离原生线程保留供 Desktop 核对。

`verify-emulator.sh` 是明确的 fresh workflow，会仅清除 `dev.rivo.relay` 本地数据，必须在隔离验证环境使用。本轮脚本已有真实 3/3 整跑证据，不能以它替代 Desktop UI 验收。正式签名、后台驻留、升级和发布不计为本轮已交付。

## 记录规则

实现按当前代码确认；通过按真实结果确认；隔离 stub 测试、真实原生测试、Android UI 和 Desktop UI 各自记录，不互相替代。没有全能力证据之前不宣称阶段 1/3/5 全部完成。后续新增能力继续保持 Desktop 原生权限与身份，不用独立审批层或手动执行权流程掩盖技术差异。

## 0.1.4 会话操作增补

- [x] 长按置顶/取消置顶，原生 Pinned 分组与列表排序。
- [x] 持久待发队列，编辑、撤回、长按排序、展开与原生 turn 引导。
- [x] v1→v2 数据迁移、稳定提交去重、重启不重放不确定任务。
- [x] 本机原生两任务顺序执行、撤回不执行及 pin/unpin 验证；Android 定向操作测试。
- Desktop 独立 stdio 跨运行时执行仍保留原验收缺口，不能以队列等待替代该能力。
