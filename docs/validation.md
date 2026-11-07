# Rivo Relay 开发验证记录

## 2026-11-07：0.2.20 架构、性能与边界收尾

服务端完整 **431 项**回归通过，无失败、错误或跳过。覆盖同会话串行与跨会话并发、关闭期间队列准入、元数据重连、辅助上传租约、工作树清理、历史追加/替换/截断/修复代数，以及媒体请求合并、缓存固定和并发清理。日志：`workspace/artifacts/release-0.2.20/server-final-tests.log`。

Android 完整 **259 项 JVM** 测试通过；最终 `assembleDebug`、`assembleDebugAndroidTest`、`lintDebug` 通过，lint 为 0 错误、43 警告、3 提示。覆盖共享事件流、请求取消与时效窗口、批量 Unicode 增量、Markdown 扫描预算、图库失效，以及导航批写、图片冻结、旧指纹兼容、损坏恢复记录保护和磁盘故障传播。日志：`workspace/artifacts/release-0.2.20/android-timeout-final.log`。

Android 36 专用只读 `medium_phone` 模拟器最终 **88 项不同的交互测试通过**。首批成功 75 项；两项旧测试分别误禁正常 capabilities 请求、使用了被父节点合并的 semantics，修正后与正确包名下的 11 项图片手势/尺寸测试共同复测，13 项全过。没有以关闭能力查询、隐藏提示或放宽功能保护来通过测试。一次 lint Kotlin 分析器内部错误经单 worker 重跑通过。最终日志为 `ui-final-tests.log`、`ui-corrections-tests.log`、`ui-summary.json` 和 `android-lint-final.log`；21 张受控界面截图位于同目录 `screenshots/`，已查看消息及恢复布局。模拟器没有配对生产节点，未连接实体手机。

提交前检测到并行会话“感觉服务器连接不稳定，很慢，很卡是为什么？”已停止，并留下超时修复及 0.2.20 / code 32 版本递增；本次保留并合入，0.2.19 未发布。最终 0.2.20 重新通过完整 JVM/构建/lint；持续传输 12 秒历史的新增回归成功，传输中仍禁止操作。最终 APK 又通过节点设置、待发送草稿、提交恢复及聊天保护 **17 项定向交互复测**，日志 `ui-timeout-final.log`。此前 88 项验证的消息/媒体实现保持不变，测试使用较早的 0.2.19 候选包；不将它们写成最终包完整重跑。

APK 0.2.20 / code 32 为 **18,889,236 字节**，SHA-256 为 `a657bb4aef14b56d2377e8466ba3146fff7452f14055c719fff22c8cfd61c0f1`。签名证书 SHA-256 `3f31e18aa34d6144102e35b4640ff94e5fdfa3c92b074df474d02583934e1800` 与 0.2.18 相同。记录：`workspace/artifacts/release-0.2.20/package.json`。

已提交并推送全部功能改动（`8d788e0`），并发布到主节点 HTTP 更新源。部署前核验没有待处理提交/队列，三个 Relay 自有 writer 的最新任务均已终止；SQLite 在线备份及完整性检查通过。Relay PID 从 56015 更新为 52007，公共 Codex PID 保持 21622；`sessionIsolation=true`，21 个项目可读，原有授权保留，临时验证授权已撤销并确认 401。HTTP 清单为 0.2.20 / code 32，完整 APK 下载的长度及 SHA-256、Range 206 均匹配。未创建 GitHub Release 或 Git tag；本次专用模拟器已关闭。证据：`node-update.json`、`publication.json`、`http-verification.json`。

真实 Codex 二进制另在临时 `CODEX_HOME` 下验证：A/B 会话拥有不同 PID；释放 A 后 B 和公共 daemon 均存活；A 恢复后获得新 writer，历史为 `ready`。模型仅指向本机拒绝连接的 fixture，没有调用真实模型或操作用户会话。结果：`workspace/artifacts/release-0.2.20/native-writer-verification.json`。

以下为本机隔离微基准，不是手机帧时间、生产负载或模型首字速度：

| 样本 | 优化前/完整读取 | 优化后/增量读取 |
| --- | --- | --- |
| 11.93 MiB 合成 rollout | 完整检查中位数 56.38 ms，解析 40,002 条 JSON | 单条追加中位数 5.08 ms，仅解析新增 1 条；仍完整哈希核验旧前缀。无变化读取 0.17 ms |
| 缓存图片遇到另一个 500 ms 慢源站 | 缓存读取等待 508.369 ms | 缓存读取 0.070 ms，早于慢下载结束 |
| 10 万条合成队列记录，目标会话查询 | 全表扫描及临时排序，中位数 1.892 ms | 使用新增索引，中位数 0.002 ms |

证据位于 `workspace/artifacts/perf-review-20261107/` 的 `history-benchmark.json`、`root-microbench.json`、`media-after.json`。历史基准仍有 O(n) 前缀字节校验，不宣称追加读取完全 O(1)。

## 2026-11-07：消息格式解析与展示（未发布）

本轮服务端完整 **403 项**回归通过；Android 完整 **231 项 JVM** 测试通过，无失败、错误或跳过；`assembleDebug`、`assembleDebugAndroidTest`、`lintDebug` 成功。新增覆盖历史未知类型保留与严格轮次校验、工具混合/结构化输出、未知执行状态、流式顺序与终态保护、多段 reasoning、空失败轮次、消息锚点和 Markdown 边界。

本轮专用只读 `medium_phone` / Android 36 模拟器（emulator-5580）最终 **23 项 UI 测试全部通过**，耗时 49.944 秒。范围为 MessageFormatBoundaryUiTest、NativeTurnNoticeUiTest、MessageInteractionUiTest、ActivityFailureUiTest、NativePanelsParityUiTest、CodexLatestParityUiTest，覆盖链接目标、代码内图片不加载、表格内容保留、原生错误按文本展示、空失败会话、引用/复制/浏览器恢复、工具状态及既有设置/转发交互。使用受控 fixture，未配对生产节点、未发送真实任务、未连接真机。

首轮 UI 暴露一个合并 semantics 选择器和一个缺失权限信息的旧设置 fixture，已修正测试定位与样本后复测；没有放宽默认设置保护。最终组合检查另修复 Notice 重排本地 overlay 的问题，保留 startsThread 和 item 锚点，并以 JVM 回归锁定；整屏测试确认空失败轮次不再出现空会话文案。之后重新构建、运行完整 JVM/lint 和上述整组 UI，均通过。

日志位于 `workspace/artifacts/message-formats/`：`server-tests.log`、`android-checks-final.log`、`ui-tests-final.log`。实现细节见 [消息格式对照](qiguang-codex-sync.md)。保留此前状态/历史/媒体优化与原有会话目录修改；本轮未提交、推送、部署或发布，版本仍为 0.2.18。

## 2026-11-07：App Codex 状态、历史与媒体优化（未发布）

本轮服务端 388 项回归、Android 210 项 JVM 测试、应用与测试 APK 构建、lint 均通过。新增覆盖 writer 断线立即撤销操作权限、threadIds 精确过滤、旧快照失效、长历史后台恢复及跨 revision 丢弃、未知所有权投影、空会话释放能力、设置未知/继承/自定义区分与默认值防误写。

在本轮启动的只读 `medium_phone` 临时模拟器（emulator-5580）运行 19 项定向 UI 测试，全部通过：NativeSettingsAvailabilityUiTest、TakeoverUiTest、NativePanelsParityUiTest、PermissionSettingsUiTest、CodexMediaInteractionUiTest、CodexMediaIntegrationTest、CodexVideoUiTest。验证空会话释放按钮禁用/恢复、只读重新核验、默认设置不猜值、原图失败重试、切换媒体 client 取消旧附件回调、后台视频不自动播放及停止后暂停。网络使用 MockWebServer 与本地媒体 fixture，未配对或写入生产会话；未连接真机。

日志与截图位于 `workspace/artifacts/codex-optimization/`：`server-tests.log`、`android-checks-final.log`、`ui-tests-final.log`。保留工作区原有会话目录修复，未部署主节点、提交、推送或发布本轮修改，版本号仍为 0.2.18。

验证日期：2026-10-29。服务和 Android 已连接真实 Codex 原生运行时，登录、项目、会话、待办、文件和图片的模拟器流程已通过。阶段 2、4 验收通过；阶段 1、3、5 的 Desktop UI 双向接续和已知能力差异仍未全部完成。

## 最新部署：本机 Relay

根据用户要求，已停止此前 Relay PID 48668 和其专用 e2-mac-mini SSH 转发 PID 32102；未停止远端 Codex 或 Desktop 自己的 SSH 连接。本机安装 `~/Library/LaunchAgents/local.rivo-relay.plist`，标签 `local.rivo-relay`，登录启动、退出后自动拉起。连接本机原有 socket，诊断 runtime ID 为 906，原生版本 0.159.2；项目读取为 20 个。新数据目录为 `workspace/local-node`，旧远端数据库保留且未复制凭据、提交或待办。

已通过实际 Tailscale HTTPS 入口完成临时配对、认证节点/项目读取，并确认新的 nodeId 与原远端节点不同；验证凭据已撤销且再次请求返回 401。结果保存在 `workspace/local-node/installation-verification.json`。运行配置与管理命令见 [README](../README.md#当前本机安装)。手机需重新配对；当前 Desktop 的本地 stdio 与该 daemon 仍是不同运行时，`desktopShared` 保持 `null`。

## 0.1.2 节点记录与重试

修复配对接口 401 被当成现有登录失效的问题：配对码过期、已使用或无效时给出对应提示，不清除保存的节点地址。合法地址在发起配对前保存；缺码和连接失败也保留地址，二维码片段和一次性配对码不进入节点记录。已有登录自动迁入加密节点列表，有效凭据可切换重连；授权失效仅移除该地址的失效凭据，保留地址供重新授权。已登录页面的节点菜单提供“切换节点”。旧版已删除的历史地址无法追溯恢复。

41 项 JVM 测试、构建和 lint 通过；13 项登录/基础 UI 测试通过。模拟器在本机 HTTPS 节点完成实际手动配对，并通过“切换节点 → 已保存节点 → 无需新码重连”流程。日志为 `android-build-0.1.2.txt`、`nodes-ui-0.1.2.txt`、`local-pairing-0.1.2.txt`、`saved-reconnect-0.1.2.txt`（均在 `workspace/artifacts/logs/`）。截图见 `workspace/artifacts/emulator-0.1.2/saved-nodes.png`。

APK：[`rivo-relay-0.1.2-debug.apk`](../workspace/artifacts/rivo-relay-0.1.2-debug.apk)，versionCode 3，支持覆盖安装。

## 0.1.3 已有会话的写入者冲突

本机实测复现：`thread/read` 和 `thread/turns/list` 成功，但打开会话先执行的 `thread/resume` 返回 `already has an active writer`，被统一映射成 409“操作状态已变化”。修复仅对这一明确拒绝退回原生历史读取，并返回 `readOnly=true`；其他原生错误仍保留失败，不伪造空历史、不抢占 writer。手机展示历史和运行时占用提示，禁用发送、停止和请求回答。普通读取失败不再显示空会话引导或一直显示“正在读取”。

通过原 HTTPS 入口复核两条此前拒绝的本机会话，均返回 200，分别读取到 15 和 19 轮原生历史；仅创建并撤销验证连接，没有发送消息或中断原任务。结果见 `workspace/artifacts/history-read-0.1.3.json`。服务端 84 项测试、Android JVM/构建/lint 通过；针对历史显示和禁止向外部 writer 派发的模拟器结果见 `workspace/artifacts/logs/history-ui-0.1.3.txt`。

APK：[`rivo-relay-0.1.3-debug.apk`](../workspace/artifacts/rivo-relay-0.1.3-debug.apk)，versionCode 4。服务已加载修复，现有登录保留。此版本解决查看历史；另一运行时持有的会话仍不能通过当前 daemon 接续执行，完整 Desktop 双向接续未因此完成。

## 0.1.1 真机反馈修复

对照 QiGuang 当前源码迁移消息显示规则，见[逐项对照与边界](qiguang-message-parity.md)。完整问答包装显示为“问题／答”，有效回复不显示内部 ID；正常代码、畸形负载保留原文。同轮连续命令/工具活动折叠为摘要，展开查看各步输出；用户和助手使用 Markdown，过程与最终消息顺序不变。发送区改为单行起步、最多三行的输入区和单条工具栏，图片/引用进入＋菜单。

活动项目增加 QiGuang 同款 1.8 秒、2dp 环绕扫光；空闲或任务结束后移除。节点从已有原生线程列表投影活动计数，包含等待审批/用户输入的 active 线程。首页可见时每次读取完成后间隔 5 秒更新，离开或后台停止轮询；并行刷新复用进行中的 GET。来源与计数边界见[项目扫光对照](qiguang-project-activity.md)。选图返回等正常恢复过程显示“正在同步会话”，保留历史，不把正在同步误报为连接中断。

读取成功后清除该会话此前的读取错误，独立写入错误不被覆盖；旧连接或旧分页返回不能覆盖新状态。只用 `nextCursor` 加载更早历史。服务端每个连接代次首次 resume 建立订阅，后续用 read 获取元数据，消除了重复 resume 自己触发通知再读取的循环。真机截图中的 409 原始触发未复现，已确认并修复的是错误恢复后红条残留；没有添加写入重试或伪造空历史。原生证据见[复核记录](native-validation.md)。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 服务 | 83 项通过 | `workspace/artifacts/logs/server-tests-0.1.1.txt` |
| Android | 41 项 JVM 测试通过，debug/test APK 构建通过；lint 0 错误、20 警告 | `workspace/artifacts/logs/android-build-0.1.1.txt` |
| 界面 | 15 项通过，含问答投影、连续命令分组、展开输出、键盘、输入区、扫光移动/停止和项目点击 | `workspace/artifacts/logs/ui-final-0.1.1.txt` |
| 小屏 | 360×640dp 亮色下扫光、发送区和消息键盘 4 项通过；原 1080×2400 / density 420 / 夜间模式已恢复 | `workspace/artifacts/logs/small-light-0.1.1.txt` |
| 原生活动状态 | 隔离项目任务执行时活动计数非零，完成并收到指定回复后归零；全项目只读检查未增加历史或逐项目请求 | `workspace/artifacts/project-activity-native.json`、`project-activity-read-only.json` |
| 真实节点 | 升级保留登录后，新会话发送、系统选图发送、节点文件预览 3 项通过；会话回复后无残留错误条或虚假历史入口，键盘弹出时标题与发送按钮可见 | `workspace/artifacts/logs/native-{conversation,image,file}-0.1.1.txt` |

消息展示样例截图保存于 `workspace/artifacts/emulator-0.1.1/chat-rendering.png` 和 `chat-keyboard.png`，使用固定的原生历史结构，未执行其中命令。真实会话/图片/文件验证使用已有隔离项目；对应日志为 `native-conversation-0.1.1.txt`、`native-image-0.1.1.txt`、`native-file-0.1.1.txt`。

交付 APK：[`rivo-relay-0.1.1-debug.apk`](../workspace/artifacts/rivo-relay-0.1.1-debug.apk)，版本 `0.1.1` / code `2`，12,595,825 字节。SHA-256：`e644e573b36be9f6cffdf94715e616cbff55c46219ab93d77d758c64757db10d`；摘要见 `workspace/artifacts/build-summary-0.1.1.json`。包名与签名沿用旧版本，可覆盖安装并保留登录。

本轮消息和扫光验收期间，曾重启本项目专用 Relay 加载修复，保留当时的 HTTPS 地址、连接凭据和 SSH 转发，未重启原生 daemon。活动验证时所选远端节点的原生诊断返回 runtime ID 56530，以新增记录为准。随后已按用户要求改装本机 Relay，最新部署见上节；此处及下方初轮清理均为历史验收记录。

## 环境与隔离范围

| 项目 | 本次环境 |
| --- | --- |
| 服务 | Python 3.13.14，单进程 Python HTTP + SQLite，开发监听 `127.0.0.1:18765` |
| Android | Kotlin 2.3.20、AGP 9.1.0、Gradle 9.3.1、JDK 17，单 `:app` 模块 |
| 模拟器 | 已有 `emulator-5554`，Medium Phone，Android 16，真实 Android 相机与 Photo Picker |
| 手机访问地址 | debug 专用 `http://10.0.2.2:18765`；release 不允许明文 HTTP |
| Codex | 已有 `e2-mac-mini` 节点原生 daemon 0.159.0，PID 92543，经临时 Unix socket SSH 转发接入 |
| 测试项目 | `Rivo Relay Validation`，`/Users/e2/Documents/Rivo-Relay-Validation-20261029` |
| 原生项目身份 | `01a18395-9695-7313-bebc-12a2516c8126` |

只在隔离项目创建测试会话和文件；待办使用新 Rivo 库。未重启用户 Desktop、原生 daemon、已有 SSH proxy 或模拟器，也未操作已有 8765 服务。原生模型、effort、工作目录、审批和 sandbox 由上游继承，产品没有强制 full access 或增加 App 权限等级。详见[原生验证记录](native-validation.md)。

## 0.1.0 初轮已通过验证

| 层次 | 检查与结果 | 证据 |
| --- | --- | --- |
| 服务单元与协议 | 78 项通过；包含原子配对、撤销、持久化提交去重、未知结果不重放、Todo 关联、HTTP/SSE 资源边界与原生适配 | `workspace/artifacts/logs/server-tests.txt` |
| Android 构建 | debug/test APK 构建通过；18 项 JVM 测试通过；lint 0 错误、20 警告 | `workspace/artifacts/build-summary.json`、`client/android/app/build/reports/` |
| HTTPS | 临时 CA 与正确主机名通过；错误 CA、错误主机名被拒绝；未关闭证书校验 | `server/tests/test_api.py` |
| 真实 HTTP → Codex | 创建会话、发送、SSE、原生历史读回、同提交重复请求仍为同回执且仅一轮、Todo CRUD 清理 | `workspace/artifacts/service-e2e.json` |
| 原生共享运行时 | 双客户端同 thread，通知、引导、停止、提问、审批竞争与重连；没有另启 stdio | [原生验证记录](native-validation.md) |
| Android 界面 | 最终 11 项通过；含 Keystore 恢复、无隐式审批、密文输入、文件引用、未知提交只核对不重发、冻结草稿和显式结束核对不执行旧回调 | `workspace/artifacts/logs/client-ui-final.txt` |
| 小屏与主题 | 360×640 dp 亮色下 11 项 UI 测试通过；默认暗色下最终 11 项通过，项目页截图无横向裁切 | `workspace/artifacts/logs/client-ui-small-light.txt`、`small-light-projects.png` |
| 真实扫码 | 新码注入模拟器虚拟相机，经 ZXing 解码和一次性交换后显示原生项目列表 | `qr-paired-projects.png` |
| 原生会话 | App 新建会话并发送，界面显示真实返回的 `RIVO_ANDROID_OK` | `native-conversation.png` |
| 待办完整闭环 | 创建、编辑、选项目交付、新会话收到 `RIVO_TODO_OK`、返回待办、手动完成、删除，整例通过 | `workspace/artifacts/emulator-final/logs/todoCrudAndRealDispatch.log` |
| 全新流程复验 | 脚本清除测试 App 自身数据，依次真实扫码、原生会话、待办全流程，3/3 通过并自动恢复相机 | `workspace/artifacts/emulator-final/` |
| 节点文件预览 | UI 添加原生文件引用、发送、点击引用，经 `/v1/files` 读取节点文件，显示 `RIVO_NATIVE_FILE_PREVIEW` | `workspace/artifacts/logs/native-file.txt`、`native-file-preview.png` |
| 系统相册与原生图片输入 | 测试创建红图，通过实际 Photo Picker 选择，展示附件，再发送给 Codex，收到 `RED`；没有注入 picker 返回结果 | `workspace/artifacts/logs/native-image.txt`、`native-image-reply.png` |
| 服务离线 | 停止本次 18765 服务，App 冷启动保留项目首页与凭据，显示连接错误，没有跳回扫码或自动重发 | `workspace/artifacts/logs/offline-home.txt`、`offline-home.png` |
| 手动配对 | 新码经手动输入地址/码，实际兑换并进入真实项目列表 | `workspace/artifacts/logs/manual-pairing.txt`、`manual-paired-projects.png` |
| 撤销授权 | CLI 撤销测试连接，App 冷启动收到未授权并返回扫码页 | `workspace/artifacts/logs/revoked-login.txt`、`revoked-login.png` |
| 相机拒绝 | 系统权限窗口实际点击拒绝，显示相机权限提示，手动输入仍能提交；该用例的手动提交使用回调断言，真实兑换另有上一行证据 | `workspace/artifacts/logs/camera-denied.txt`、`camera-denied.png` |
| 关联恢复 | 重启 Relay 并冷启动 App，打开之前已关联的隔离待办，读回原会话 `RIVO_TODO_OK`；未新建或重发任务 | `workspace/artifacts/logs/todo-restart.txt`、`todo-association-restored.png` |
| 原生转发中断恢复 | 临时转发断开时显示原生不可用；恢复自己的转发后，不重启 Relay 即可重新读取项目和完成文件验证 | `native-disconnected.png` 与后续文件通过日志 |
| 最终服务启动 | 载入最终项目归属修复后，健康、认证读取、7 个原生项目与原生连接状态通过 | `workspace/artifacts/service-final-read-ok.json` |

截图主要保存在 `workspace/artifacts/emulator/verification/`，fresh 三流程另存于 `workspace/artifacts/emulator-final/screenshots/`；小屏与原生断连截图在 `workspace/artifacts/emulator/`。工作区受忽略，不将登录令牌、配对链接或原生完整运行数据写入文档。

## 调试中已修复的问题

- 模拟器按文件路径缓存虚拟场景图片；复用同一路径会反复扫描旧码。注入脚本改用内容摘要命名，二维码保留足够边框，并使用已验证相机姿态。
- 新建尚无首轮的原生 thread 不支持直接恢复历史；适配器只暂存原生创建回执，首轮之后使用原生历史。
- Photo Picker 会暂停 Activity；返回时保留当前会话和草稿，再刷新原生状态。
- Todo 搜索框和行标题同文案导致测试选择器歧义，现限定行与弹层节点；菜单等待真实项目加载。
- 未确认提交的旧完成回调可能清掉新草稿；等待核对时冻结输入和附件，并且核对只做 GET。
- unknown 长期无法核实时，提供明确确认后的“结束核对”；仅清手机等待和旧回调，不取消、重发或删除服务端去重记录。
- 同目录的不同原生项目不应混入彼此会话列表；按原生 projectId 过滤，同时保留没有 projectId 的旧根目录会话和已绑定项目的工作树会话。
- Python 3.13 的 TLS 严格验证要求测试 CA 正确包含标识扩展；修复测试证书，没有放宽生产校验。

## 尚待核实的验收边界

Desktop 自动化工具明确拒绝操作 `com.openai.codex`。本次没有通过 AppleScript、深链、注入或其他通道绕过。已请求用户在 Desktop 的远端节点打开隔离项目并发起一条验证消息；在用户确认前，`desktopShared` 保持 `null`，不能把 Desktop ↔ App 双向界面验收标为通过。

当前电脑 Desktop 使用独立 stdio，本机默认 daemon 与它不是同一运行时。本次接通和验证的是 Desktop 已配置的远端 `e2-mac-mini` 节点原有 daemon，不把它描述成当前本机 Desktop 已接通。

当前客户端支持普通 MCP 表单、URL 验证、原生提问、命令/文件审批和原生权限请求；复杂扩展表单及 Desktop 原生身份签名验证没有伪装成支持，遇到时明确提示。详细能力边界以[原生记录](native-validation.md)和[客户端说明](../client/android/README.md)为准。这些属于协议/界面覆盖，不是另建 App 权限体系。

## 0.1.0 初轮交付与清理

- debug APK：[`rivo-relay-0.1.0-debug.apk`](../workspace/artifacts/rivo-relay-0.1.0-debug.apk)，版本 `0.1.0` / code `1`，16,250,615 字节。
- SHA-256：`cfba8fc346de2c684d0b2772713be1510da2cb00cbd35544e5d183c13a760f54`。构建摘要另存 `workspace/artifacts/build-summary.json`。
- 模拟器保留已安装 App 和最新配对连接，便于按 README 重新启动服务后复查。4 条失败调试遗留的测试待办已删除，3 个旧测试连接已撤销；隔离原生线程保留给 Desktop 人工验收。
- 配对图、码文件、API 测试令牌文件与 QR 临时缓存已删除。没有清理原生去重记录或用户历史。
- 相机位置恢复 `(0,0,0)`、原旋转约 `(-4.75,0,0)`，默认海报已恢复；屏幕回到原 1080×2400 / density 420，夜间模式恢复为 yes，相机权限恢复。
- 本次临时 Relay 服务和 SSH 转发已停止，临时 socket 已删除。已只读核对原 Desktop、其 SSH proxy 及远端 daemon 仍为原进程。重新体验需按 README 启动服务和所选原生连接；用户原有服务、Desktop 和模拟器继续保留。

## 复验命令

```sh
.venv/bin/python -m unittest discover -s server/tests -v
cd client/android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

真实 API 检查使用 `scripts/verify-service.py`，连接文件只存于受忽略、仅本机用户可读的工作区；传隔离 `--project-id` 才执行新会话测试。`scripts/verify-emulator.sh` 用于首装扫码、会话和 Todo 的连贯复验，会明确清除测试 App 自身数据，保留原生测试会话供 Desktop 检查。其余真实文件、选图、手动配对、离线与撤销测试的前置条件及命令见[客户端说明](../client/android/README.md)。

使用方式、TLS、配对与撤销见[项目说明](../README.md)。正式签名、后台部署与发布不属于本次模拟器开发验收。

## 0.1.4 置顶与执行队列（2026-10-29，本机节点）

- 服务端 95 项 unittest、Android JVM 41 项通过；lint 0 errors / 20 warnings。
- 模拟器 5 项定向 UI 测试通过：置顶优先及长按 pin/unpin、前三项/展开/长按排序、编辑/撤回/引导及 revision/turn 身份、历史消息渲染、外部 writer 仅允许排队。修正测试 Activity 的 edge-to-edge/resize 配置后，3 项队列与置顶测试再次通过，键盘弹出时标题、队列和发送入口可见。
- 已登录模拟器通过真实 HTTPS 连接本机服务，1 项 nativePinRoundTrip 通过：长按置顶→列表显示置顶→取消置顶→打开原生队列执行历史。保留原手机连接，无需重新配对。
- 本机既有 daemon 在隔离项目 `Relay 0.1.4 队列验证` 完成两项排队任务，依次回复 QUEUE_FIRST、QUEUE_SECOND；撤回项未创建 turn；原生 pin/unpin 均已核对。记录 `workspace/artifacts/native-queue-0.1.4.json`。
- 部署后通过实际 HTTPS 入队重复提交，同一 submission 返回同一回执，只产生一次 QUEUE_HTTPS_OK，后台 worker 自动派发并清空队列；临时验证连接已撤销。
- 本机 Relay LaunchAgent 已更新；数据库预先私有备份，schema 1→2 后原有两个有效连接保留。Codex daemon PID 906 未重启，未触碰 QiGuang/e2mac 服务。
- APK：`workspace/artifacts/rivo-relay-0.1.4-debug.apk`（versionCode 5）。SHA-256：`9fa0153a42cfd8b913dff4abdb9b1cc0811a86803c1e6338a62a5e913aebadab`。
- 截图：`native-pin-0.1.4.png`、`execution-queue-0.1.4.png`、`execution-queue-keyboard-0.1.4.png`，均在 workspace/artifacts。

限制：其他运行时持有 writer 时队列等待，不抢占；本次没有完成 Desktop 独立 stdio 的执行接续。队列是 Relay 自有待发记录，未导入 QiGuang sidecar 的历史队列。

## 0.1.5 会话滚动（2026-10-29）

修复首次进入长会话不滚到最新、末条长消息只滚到开头的问题。列表使用末尾锚点，处于底部时随实际布局更新跟随，包括流式正文增高；向上翻阅会暂停跟随，右下角显示“回到最新消息”按钮，点击到底后恢复。切换会话重置到最新；加载更早消息不主动拉回末尾。

验证：41 项 JVM 测试、8 项模拟器定向 UI 测试通过，lint 0 errors / 20 warnings。新增三项覆盖异步首次加载、末条消息由 40 段增长到 60 段、上翻后追加消息保持位置、按钮恢复跟随和跨会话重置；原消息渲染、键盘、队列与置顶测试通过。截图已查看：`workspace/artifacts/chat-scroll-button-0.1.5.png`、`chat-scroll-long-message-0.1.5.png`。

安装包：`workspace/artifacts/rivo-relay-0.1.5-debug.apk`，versionCode 6；SHA-256：`39625740dc12d5e245877e890162cd9d813ea3b823e5f7ccc91de3d27a0c690e`。已覆盖安装到模拟器，保留登录；本次仅客户端更新，未重启节点服务或原生运行时。

## 0.1.6 工具截图误入正文（2026-10-29）

将 imageView/image_view 归入折叠工具活动；点击文件路径才预览。42 项 JVM 测试和 6 项模拟器测试通过，lint 0 errors / 20 warnings。新增验证：截图与相邻命令合组、上传附件和生成图片仍为消息、折叠和展开阶段都不读取图片、明确点击后读取一次；滚动回归通过。

APK：`workspace/artifacts/rivo-relay-0.1.6-debug.apk`，versionCode 7，SHA-256：`15033c01101908e2d9e64f8cd4966f0a12dc3d326b3d5cf05824bbd9250e629b`。已覆盖安装模拟器，保留登录，未修改节点服务。

## 0.1.7 工具组失败状态展示（2026-10-29）

折叠组标题始终使用普通颜色，右侧独立显示“X 项未完成”，不再把成功命令或整个组染红。展开后仅 status=failed 的活动摘要与状态标红；命令显示原生 exitCode（含 0），缺失/null 不推测成 0，保留 aggregatedOutput；失败命令缺少输出时提示原生记录未提供错误输出。

43 项 JVM、4 项模拟器 UI 测试通过；lint 0 errors / 20 warnings。新增测试验证混合成功/失败组的实际文字颜色、计数、退出码、错误输出，以及缺失退出码解析。消息渲染和工具图片按需预览回归通过。

APK：`workspace/artifacts/rivo-relay-0.1.7-debug.apk`，versionCode 8；SHA-256：`651166c8f5105b92c837306f7a58d630b1e070aab9d1bbc5f08dde733644dc43`。已覆盖安装模拟器，保留登录，无服务端改动。

## 0.1.8 附件编辑与文件选择（2026-10-29）

新增图片裁剪与标注、手机文件上传和节点目录选择，移除手填引用入口。服务端 98 项测试、Android 单元测试、lint 和测试 APK 编译通过；本次版本号更新后重新 assembleDebug 成功。无设备验收，未安装客户端或部署节点服务。文件功能需要同步更新节点。

APK：`workspace/artifacts/rivo-relay-0.1.8-debug.apk`，versionCode 9；SHA-256：`bf5f36ecf6900b02af72d634fb4d80f4f7c65a7c92a8dbac7821b0b34e5935db`。

### 0.1.8 节点同步部署

已通过 SIGTERM 平滑重启 `local.rivo-relay` 并由 launchd 自动拉起，加载本仓库服务端更新。重启前在线备份 SQLite，无 pending submission；保留原有 2 个有效连接。未重启 Codex 原生运行时。通过现有 Tailscale HTTPS 入口完成认证、节点目录读取、二进制附件上传和原生读取逐字节比对，测试文件已删除、临时验证凭据已撤销并确认 401。记录：`workspace/local-node/attachment-deployment-0.1.8.json`。用户无需重新配对。

## 0.1.9 文件下载与大附件（2026-10-29）

修复用户从聊天点击 APK 被 4 MiB 预览上限阻断且误报“手机上传上限”的问题。新增保存到手机入口、流式 HTTP 文件传输和 100 MiB 上限。旧客户端可使用单文件、1 小时有效的浏览器下载链接完成更新。

服务端 101 项测试、Android 44 项 JVM 测试、APK/测试包构建及 lint 通过。原生接口单条大消息实测受限，改为 4 MiB FS 分块加默认权限下的原生合并；真实 Tailscale HTTPS 完成 32 MiB 和 100 MiB 上传/下载，并验证 SHA-256 一致。APK 无认证浏览器链接下载字节校验通过。节点已平滑重启加载，原有连接保留，临时验证凭据撤销，测试大文件删除。记录：`workspace/local-node/transfer-deployment-0.1.9.json`。手机保存位置交互暂无设备实测；测试包仅编译。

APK：`workspace/artifacts/rivo-relay-0.1.9-debug.apk`，versionCode 10，SHA-256：`2028183990f6a0dcba291b3324414ddbe62795db4a2760beb247d5682cdf464d`。

## 0.1.10：1 GiB 上传和下载（2026-10-29）

统一单文件上限为 1 GiB，上传由 HTTP 流直接拆为原生 4 MiB 文件块，下载采用远端范围读取与逐块 HTTP 写出，避免整文件 Base64 与内存副本。恢复原生 WebSocket 32 MiB 帧上限。传输无固定总时长限制，保留空闲超时。

102 项服务端测试通过，包含整整 1 GiB 的有界读取测试与超限拒绝；Android 单元测试、APK/测试包构建和 lint 通过。真实 Tailscale HTTPS 测试上传 1,073,741,824 字节用时 18.81 秒，下载用时 34.75 秒，SHA-256 一致。验证进程最高 RSS 约 52 MiB；下载过程中 Relay RSS 单点采样约 295 MiB，此采样不代表峰值。节点已加载更新，既有登录保留，测试文件和临时认证凭据已清理，浏览器 APK 下载校验通过。记录：`workspace/local-node/transfer-deployment-0.1.10.json`。暂无手机端真机测试。

APK：`workspace/artifacts/rivo-relay-0.1.10-debug.apk`，versionCode 11，SHA-256：`b4d1a881486a349f696d610f64ca1e58cdde2fe723953c0a49cf7cea58b730d8`。

## 0.2.0：本轮功能补齐（2026-10-29）

按用户确认，仅扩展现有 Android 与 Relay 接口，不新增服务、模型或部署依赖；语音输入、录音权限和识别入口已移除。实现范围与保留边界见[功能记录](feature-parity.md)：批量会话整理、未读、额度/上下文、按会话草稿、显示缓存、模型变化提示、完成/待回应通知、目录与工作树、Plan/Review/diff、文件预览及待办详情/本机提醒/离线 CRUD。

- 服务端 **143 项**回归通过，包括 schema 升级、权限恢复、首轮历史窗口、Review 队列、待办旧摘要兼容及 **23 项**真实临时 Git 工作树检查。
- Android **73 项 JVM 测试**通过；debug APK、测试 APK 与 lint 通过，lint 无错误（保留 27 项警告，主要为依赖更新和既有 API/风格建议）。
- 现有 `medium_phone` 模拟器覆盖安装保留数据；**21 项 UI/集成测试**通过，覆盖草稿、归档/批量目标、目录选择、额度/计划、diff、模型变化/未知边界、缓存写保护、共享 SSE、提交核对恢复及原有消息/队列/输入/文件选择。截图与日志在 `workspace/artifacts/parity-0.2.0/`。
- 现有 Relay 已平滑重启加载代码；升级前 SQLite 在线备份，schema **2→4**，原有 **2 个有效连接保留**。没有重启原生 Codex，runtime ID 仍为 **906**。
- 通过现有 Tailscale HTTPS 入口实测：额度、项目目录、已归档列表、Git ref、Todo 详情往返；独立工作树创建、干净 diff、Review 范围读取；两轮实际原生执行验证 Plan 为只读、普通模式恢复原 sandbox，并观察到轮次模型信息；最后成功归档测试会话并移除工作树。
- 真实首次读取暴露原生 rollout 已建立但元数据仍为空的窗口。现仅对已有接受回执的新会话在最多 30 秒内使用创建元数据与真实 live turn，既不自动重发，也不隐藏其它历史失败；修复后上述完整原生流程通过。
- 所有本次测试工作树均已移除，验证凭据已撤销并确认 401；脱敏结果见 `workspace/local-node/parity-deployment-0.2.0.json`。

本轮未实际发起原生 Review 执行：四种目标和队列行为经回归测试，真实节点验证到 Review 范围读取。系统提醒准时送达、进程被杀后的通知与 Windows 节点尚无实机保证；复杂身份签名挑战和 Desktop UI 双向接续未因此完成。通知不新增远程推送服务。

APK：[`rivo-relay-0.2.0-debug.apk`](../workspace/artifacts/rivo-relay-0.2.0-debug.apk)，versionCode **12**，13,308,975 字节，SHA-256：`97e27aaed1541e0e1359fd80fb56fc8aa1d86c285a65e1e1a2e25f238e7920fd`。摘要见 `workspace/artifacts/build-summary-0.2.0.json`。本地包与模拟器已更新，尚未上传 GitHub Release。


## 0.2.1：同类 UI 源码核对与迁移（2026-10-29）

- 按栖光实际源码和用户截图核对会话/项目、聊天/设置、正文选择/链接、APK/文件、附件、问答/计划/队列、diff、待办和目录流程。详细对应关系及结构差异见[核对记录](feature-parity.md#021按栖光源码核对现有-ui)。图片编辑器及工具活动主体已有同源实现，保留原生审批、writer、队列 revision 和未知提交核对。
- Android 单元测试 **90/90** 通过；`assembleDebug`、`assembleDebugAndroidTest`、`lintDebug` 通过，lint **0 errors / 30 warnings / 1 hint**。构建日志见 `workspace/artifacts/ui-parity-0.2.1/build.log`。
- 已运行的 `medium_phone` 模拟器覆盖安装 0.2.1（versionCode 13），保留 App 数据。**76 项不重复的 UI/集成回归最终通过**，分批日志、首次失败及修复后重跑均保留；不把单元测试类误记为设备测试。初次完整 74 项中发现设置跨窗口测试方式、历史跟随布局重入及附件节点合并问题；后续 23 项回归验证滚动崩溃修复、最新目录/待办及新增媒体失败重试、Markdown 内行号导航，最后 2 项以系统级实际触摸验证浮层关闭和发送参数/草稿清空。
- 修复的实际问题还包括：取消后旧下载进度重建弹窗；Markdown 内文件行号混入节点路径；媒体初始化异常未释放、重试复用旧播放器及后台准备完成后继续播放；批删前一项完成时 mutating 未释放导致下一项被丢弃。
- 已检查模拟器浅色截图：`workspace/artifacts/ui-parity-0.2.1/sessions.png`、`apk-actions.png`；其它交互证据位于同目录 `verification/`。截图使用本地固定数据，不包含节点凭据。
- 本轮没有更新或重启 Relay 节点，也没有新增服务、模型或客户端库；无需额外部署。不支持语音。系统保存选择器的名称/MIME、文件 Intent 的只读授权、下载取消及类型分流有定向测试；未将模拟器结果当成实际手机的 APK 安装器、第三方分享接收或全部媒体格式验收。

APK：[`rivo-relay-0.2.1-debug.apk`](../workspace/artifacts/rivo-relay-0.2.1-debug.apk)，versionCode **13**，**13,791,737** 字节，SHA-256：`f1c61e941097bce718f6090b7f280301ee0f37fb1d918b23a66e70fd2cd4bc08`。摘要见 `workspace/artifacts/build-summary-0.2.1.json`。本地包与模拟器已更新；Git 提交与 PR 状态以仓库记录为准，尚未发布 GitHub Release。


## 2026-10-29 响应链路优化验证

初次优化阶段为本地源码和构建验证；后续 0.2.2 部署和模拟器实测见下方记录。尚未做同条件旧版本/新版本的端到端对照。

- Python：`test_performance.py` 3 项通过，验证缓存过期/失效、并发读取复用、读取中失效、返回值隔离和 SSE 位置字段；`test_codex.py` 24 项通过。
- Android：`LiveDeltaTest`、`SessionControllerTest`、`CatalogControllerTest` 定向回归；覆盖快照与事件重叠、Unicode、命令输出、旧协议回退、断线历史重置及跨会话读取保护。集成测试验证收到文本增量后无需增加历史 GET。
- `:app:assembleDebug` 成功，产物为 `client/android/app/build/outputs/apk/debug/app-debug.apk`；版本号未更改，未发布。

这轮证据说明减少了增量路径的历史请求及重复工作，不代表已测得端到端耗时改善百分比。


## 0.2.2：部署、栖光性能对照与远程模拟器迭代（2026-10-29）

- 本机 `local.rivo-relay` 已平滑重启加载服务端优化；原有 2 个授权连接保留，Codex runtime ID 仍为 906。部署前 SQLite 在线备份。只更新 Relay，没有更新或重启本机/远端原生 Codex 或栖光服务。
- 本机 `emulator-5554`（API 37）和 Tailscale e2mac 的 `emulator-5582`（API 35）已安装最终 0.2.2 / versionCode 14。远端使用已有 `MergeDemoMinimalBase_API35` AVD，以 read-only/no-snapshot-save 启动，未操作已连接的实体手机；两个模拟器的网络开关已恢复。远端验证路径为 Android → e2mac 主机 Tailscale → 本机 HTTPS 18766 → Relay，没有 SSH 隧道代替这条应用网络路径。
- 对照并适配了栖光的积压通知合并、150ms 展示批次、历史刷新合并和缓存边界；来源摘要和保留差异见 architecture.md。Python 定向 29 项通过（原生语义 24 + 性能 5），最终 Android 定向 23 项 JVM 测试通过。
- 最终包本机 4 项设备回归通过：真实历史断网恢复 + 3 项长历史/流式滚动测试；远端 2 项真实设备流程通过：历史断网恢复，以及从 UI 新建会话、真实命令分段输出、40 段长回复、断网/重连和内容一致性。随后两端各 1 项最终接口延迟测试通过。先前真实配对和本机完整流式流程的日志也保留。
- 远端完整流程收到 111 个带位置的增量事件，其中 19 个命令输出事件；2535 字符长回复完整显示。断网检测 94ms，恢复 702ms；独立历史流程断网检测 105ms，恢复 379ms，前后 message/turn ID 与文本一致。本机最终历史恢复 155ms。
- 实际迭代修复：旧验收脚本遗漏新建确认弹窗及恢复页面导航；辅助 SSE 测量任务在断网前未结束；Android `onLost` 中同步查询旧 activeNetwork 的竞态；旧 HTTP/2 连接使恢复等待约 90.65 秒；主动取消只读请求误报错误横幅；TLS 连接清理在主线程触发异常。最终回归覆盖实际网络开关，而非仅模拟 callback。写请求不随连接池重建被取消或重发，专门测试保留一次 POST 的接受回执。

同一原生会话、同一 HTTPS 地址，每接口连续 8 次，以下为最终包中位数（毫秒）：

| 接口 | 本机模拟器 | e2mac 模拟器 |
| --- | ---: | ---: |
| 节点状态 | 1.89 | 22.47 |
| 项目列表（缓存命中） | 2.33 | 24.4 |
| 会话列表 | 535.87 | 559.4 |
| 历史页（约 7.7KB） | 4.79 | 29.46 |

Tailscale 本次探测走 peer relay，往返约 15–16ms。项目列表首次未命中时本机 HTTP 样本约 942ms；上表项目连续读取已预热，不能当作冷启动耗时。单独 RPC 诊断显示会话列表约 0.53 秒主要花在 `thread/list(cwd=...)` 的旧会话兼容查询，并行查询不能消除它。以上是两种网络位置的样本，不是栖光与 Relay 的端到端性能比赛，也不代表模型首字速度。

证据目录：`workspace/artifacts/performance-0.2.2/`。最终日志为 `local-final-io.log`、`remote-final-io.log`、`local-latency-final.log`、`remote-latency-final.log`；原始失败和修复过程日志保留。测速 JSON 为 `local-network-final.json`、`remote-network-final.json`，截图 `remote-reconnected-final.png` 已视检。临时 host 验证凭据已撤销并确认 401，私密令牌文件已删除；保留两台模拟器的配对。

最终 APK：[`rivo-relay-0.2.2-debug.apk`](../workspace/artifacts/rivo-relay-0.2.2-debug.apk)，13,454,903 字节；SHA-256：`5af1ad81518d11abfeca72f6faae79f3bb12768b2624e1a3b114ab23b5556803`。未发布 GitHub Release，未安装到实体手机。


## 0.2.3：实体手机连接失败的恢复与诊断（2026-10-29）

用户确认实体手机为 0.2.2，保存地址与现有 HTTPS 18766 入口一致，且手机浏览器 `/health` 返回 OK。此时本机与 e2mac 的 HTTPS 健康检查、临时授权的 `/v1/node` 与 `/v1/projects` 均返回 200；停止了此前留在前台的两个模拟器 App，避免测试连接持续占用。上述结果不能替代实体手机 App 的验证，事故的手机侧具体异常仍待新版本提示确认。

对照栖光 `DataRepository.kt` 的 `retryOnConnectionFailure(true)`，发现 Relay 对 GET/SSE 也关闭了连接恢复。0.2.3 为这两种只读请求启用 OkHttp 连接恢复，允许失败地址回退和旧连接恢复；POST/上传仍使用禁止自动重试的客户端，不自动重发操作。新增回归以不可连接 IPv6 + 可连接 IPv4 的 DNS 顺序验证回退，并保留丢失写回执只发送一次的测试。

通用错误改为不含敏感原始异常文本的 DNS/TLS/联网权限/超时/连接失败分类，并显示节点主机与端口；聊天读取也使用同一分类。错误横幅增加“重连”，只重建读取连接并验证节点，不清除登录或提交记录；节点设置显示版本号。最终 14 项定向 JVM 测试通过。设备验证日志在 `workspace/artifacts/connectivity-0.2.3/device-verified.log`，包含已配对真实节点读取及故障提示/重连界面测试（DNS 错误为明确注入的 UI fixture，不是手机事故根因）。

APK：`workspace/artifacts/rivo-relay-0.2.3-debug.apk`，versionCode 15；SHA-256 `4cece2c83dea6ef916e3837b5abfa7effdf1eb26b7270a5cbdacfa7eb16630bc`。已生成限定该 APK 的 1 小时浏览器下载链接并通过 HTTPS 下载校验；临时签发用授权已撤销。未发布 GitHub Release；手机覆盖安装与实际恢复仍需用户确认。

### Android 0.2.4 — observed phone DNS failure (2026-10-29)

The user's updated phone briefly connected, then reported UnknownHost for the correct
`ethanmac-mini.tail791ea5.ts.net:18766` node. Server health remained healthy. This
identifies the failing resolution stage; the precise Android/VPN DNS cause is not proven.

Added OS-first `TailnetDns`: only `.ts.net` names can fall back to UDP Quad100
(1.5 s timeout per query), then a same-host last-successful answer held in memory
for at most 10 minutes / 32 hosts. Transport resets keep this resolver; process
restarts discard its cache. Original HTTPS hostname, SNI and normal certificate
verification remain in place. No public resolver, node-IP hardcoding, new dependency,
credential reset or write replay. Tailscale documents Quad100 as its local DNS endpoint:
https://tailscale.com/docs/reference/quad100 . Failure still surfaces if all resolution
paths fail and no valid cached answer exists.

Validation: Gradle build successful; 11 targeted JVM tests passed (4 DNS tests,
5 transport tests including no replay of a lost POST response, 1 network reset,
1 error classification). DNS tests cover OS preference, fallback, host-isolated
cache expiration, non-tailnet exclusion, malformed reply rejection and actual UDP.
Local API 37 emulator: 2 instrumentation tests passed, including DNS failure and
error/retry UI. Remote e2mac API 35 emulator: 1 forced-DNS-failure test passed over
the real tailnet path. Each DNS test forces OS lookup to throw UnknownHost twice,
reads authenticated node/projects over HTTPS and repeats after transport reset.
Whole sequence elapsed: local 2678 ms; remote 596 ms (not individual request latency).
Remote initial invocation omitted the project fixture and was interrupted; the
corrected invocation is `remote-device-verified.log`. Test apps were force-stopped
afterward to release SSE connections. Physical phones and native runtime untouched.
Artifacts: `workspace/artifacts/connectivity-0.2.4/`. APK delivered through a scoped
one-hour download link; downloaded bytes verified SHA-256
`b79b034e23b7a79e8bc03062769ea46bb29cd0d8c5e4ba4ee5e770b4962b7396`.
The user's actual phone needs the update; long-term recurrence is not yet verified.

### Android 0.2.5 — user-requested HTTP + Tailscale IP migration (2026-10-29)

Deployed tailnet-only TCP 18767 -> 127.0.0.1:18765 and updated the Relay
LaunchAgent public URL to `http://100.124.150.1:18767`. Only Relay was reloaded;
its native node reports connected. HTTPS 18766 and unrelated Serve entries remain.
Initial immediate bootstrap after bootout failed while unloading; subsequent bootstrap
succeeded and authenticated node plus download endpoints were verified over the IP.

The configured IP is explicitly allowed by release/debug Android network-security
policy, pairing parser and server public URL validation. Other cleartext remote
hosts remain rejected; no public/LAN listener was added. Upgrade migrates only the
exact known old HTTPS endpoint, preserves credentials and identity, and keeps the
previous storage scope for cached history, drafts, pending submissions and reminders.
Old notification URLs still select the migrated node. No automatic write replay.

Validation: 9 server config/TLS/process tests and 11 targeted JVM tests passed;
APK and instrumentation build passed. Remote e2mac API 35: existing HTTPS login
migrated, authenticated node/projects/history read succeeded with an injected DNS
implementation that always fails (zero DNS calls), including a transport reset.
Remote network outage test passed: detection 85 ms, recovery 369 ms, history unchanged.
Local first run found the emulator at the login screen, so its initial two UI tests
timed out; these are not counted as passes. Re-established a fixture login through
0.2.4's real pairing UI without clearing data, then covered it with 0.2.5; both
IP/no-DNS and network recovery tests passed. No phone result is claimed.
Test apps were force-stopped to release streams. Artifacts:
`workspace/artifacts/connectivity-0.2.5/`, final local log `local-device-verified.log`.
APK scoped download now also uses HTTP + IP; downloaded bytes verified SHA-256
`eaaf6ebaaaf70ed724f7695fddfd5e5c3e07c70d58313e55870c1f565603128e`.

### Android 0.2.6 — bottom notices and scoped handoff controls (2026-10-29)

Removed top global error/reconciliation/cache banners and the large external-writer
chat notice. Global states now share a rounded bottom surface, above navigation and
IME; reconciliation is GET-only every 10 seconds after a 3-second delay, with no
write replay. Ownership shows one short line and the requested two buttons.

Added POST `/v1/threads/{id}/ownership` through the existing submission ledger.
Queue dispatch is locked during handoff; an active turn is interrupted and terminal
history must be observed before attempting unsubscribe/resume. Unknown active-turn
identity, interruption failure or timeout prevents handoff. Local release intent
persists so periodic reads and queued tasks cannot silently resume the thread.

IMPORTANT LIMIT: the installed shared native runtime's `thread/unsubscribe` can
leave its writer loaded even after this client unsubscribes. This is returned as
`writer_still_held`, never as successful writer release. The native resume protocol
has no force-takeover parameter; another runtime's writer returns `writer_external`.
No writer processes are killed and no unrelated tasks/runtime are restarted.
Thus the requested cross-runtime forced transfer is NOT implemented by this build.

44 targeted server tests passed (handoff ordering/failures, existing native semantics,
submission receipts). Local isolated real-turn instrumentation: 2 tests passed,
including interruption before detaching, truthful still-held error, reattachment
without pending uncertainty, and the bottom DNS error/retry flow. Initial smoke test
was insufficient (read-only state alone passed despite a failed release); replaced
with explicit error and pending assertions. Also fixed handoff receipts to contain
only threadId, as required by the submission ledger. Earlier invalid receipts were
isolated fixture operations, not user operations. Screenshots inspected under
`workspace/artifacts/handoff-0.2.6/`. Runtime limitation remains explicit.

Final 0.2.6 APK includes IME-aware bottom-panel placement. e2mac remote emulator
bottom-error/retry instrumentation passed (1 test). APK download verified SHA-256
`b39a394aed884b311867340f11d983b2f6f1f1c1340e2f9b7da62044fb8f307f`.
Test apps force-stopped afterward; temporary delivery credentials revoked.

## 2026-10-29：MacBook Pro / e2 Mac mini 部署与既有授权接入（0.2.8）

- 两台安装目录：`~/Applications/Rivo-Relay`；独立 venv、`workspace/data/relay.sqlite3` 和 `workspace/logs/`。用户 LaunchAgent `local.rivo-relay` 已加载，RunAtLoad / KeepAlive，监听 loopback 18765。
- Tailscale Serve 新增 HTTPS 18766，保留现有 Serve 配置。入口为 `https://ethanmacbook-pro.tail791ea5.ts.net:18766` 与 `https://e2mac-mini.tail791ea5.ts.net:18766`。TLS 正常，未认证请求均返回 401。
- 显式连接各自主目录既有 `.codex/app-server-control/app-server-control.sock`，未启动或停止 Codex。认证读取 connected=true，MacBook 返回 5 个项目，e2 返回 7 个项目。临时验证授权已撤销。
- 按用户要求，同步本机 Relay 的既有 connections 记录（含撤销状态，仅 token_hash，不读取手机明文 token 或 Codex 凭据）。当时共 27 条，其中 5 条有效。此次为授权快照；后续新增/撤销并非跨节点实时同步，各节点仍独立保存授权。
- Android 0.2.8 / versionCode 20 将明确已同步的 deviceId 从本机已登录记录迁移为两个额外保存节点：复用 Relay token/deviceId，使用目标独立 nodeId/storageBase；保留活动节点、已有目标记录和已失效记录。不向未知地址共享 token，不迁移未同步设备的新授权。
- 同时修复配对成功响应不含 base 时客户端错误读取 base 的问题，新配对以实际 pairing.baseUrl 建立存储作用域。
- 4 项 NodeEndpoints JVM 测试及 APK / instrumentation APK 构建通过。现有已登录 emulator-5554 覆盖安装后，ProvisionedNodesTest 实际使用原授权经 HTTPS 读取两个节点身份、连接状态和项目成功，并恢复原活动节点；没有清空数据。
- APK：`workspace/artifacts/rivo-relay-0.2.8-debug.apk`；证据：`workspace/deploy-multinode/`。小米 17 Pro Max 未连接 ADB，已准备内网下载交付，实体手机安装及界面确认仍待用户完成。

### Android 0.2.7 — explicit process termination and native resume (2026-10-29)

Implements the user's clarified force-takeover semantics, superseding 0.2.6's
subscription-only limitation. WriterProcesses verifies the local runtime's Unix
socket/PID relationship (rejects remote-forward PID confusion), checks the actual
OS lock, identifies a unique Codex holder and all its open thread locks/descendants.
An authenticated preview returns only PID/affected titles/IDs and a one-use,
120-second ticket bound to the caller/action/thread. Multiple affected threads
require explicit confirmation in a bottom sheet. Changed PID/start identity,
commands, thread scope or descendants reject the stale preview.

Execution pauses affected Relay queues/subscriptions, terminates the verified
holder (SIGTERM then SIGKILL after 2 seconds), terminates verified surviving
children and observes OS lock release. Takeover resumes the original native
thread and verifies the new holder PID. If the terminated holder was Relay's
current runtime, its exact verified binary/arguments restore the same endpoint.
Other affected Relay sessions remain paused; no interrupted instruction is replayed.
A subsequent user message can continue from retained history, not memory state.

Verification: 61 targeted server tests passed, including real file-lock/child-process
termination, stale/reused identities, ticket expiry/caller/action binding, mandatory
shared impact confirmation, kill-before-resume, lock verification, queue regressions
and real HTTP authorization/idempotent POST routing. Isolated native app servers
used a temporary CODEX_HOME and were cleaned up. Final native test froze the writer
with SIGSTOP to force SIGKILL escalation, took over while a turn was in progress,
verified preserved history and no active original turn, killed/replaced the second
runtime for release, verified the OS lock became free, reacquired the same thread,
and completed a new continuation turn. Full sequence including continuation:
12,227 ms; this is not a takeover latency benchmark. Evidence: `native-force.json`
and `native_force_test.py` under `workspace/artifacts/handoff-0.2.7/`.
Initial fixture attempts lacked the code_mode_host flag or requested a tool the
isolated session did not expose; these were corrected before the successful test.

UI validation uses only preview and cancellation on the real production node;
it never confirms process termination there. Both local and remote e2mac emulator
flows verify the impact list and unchanged runtime identity after cancellation.
Final bottom sheet expands fully, with confirm/cancel visibility asserted. Screenshot
`shared-bottom-sheet-final.png` inspected. Production native PID 47706 remained
unchanged; only Relay was reloaded. APK download verified SHA-256
`64b9cb4d7215b28f3ef135b9a43906feef2d628021ecb5a20004ca97aed41291`.

### 0.2.9：节点切换下拉菜单

项目页顶部节点名称打开锚定下拉菜单，列出已授权节点并为当前节点显示勾选；点击其他节点直接切换，不经过登录页。添加节点和节点详情保留在菜单中，点击当前节点仅收起菜单。APK / instrumentation APK 构建通过；模拟器覆盖安装并实际展开菜单、切换至 MacBook Pro，确认项目加载。截图 `workspace/deploy-multinode/dropdown-0.2.9.png` 已视检，APK 为 `workspace/artifacts/rivo-relay-0.2.9-debug.apk`（versionCode 21）。


### 2026-11-07：QiGuang 最新 Codex 迁移

来源为本地 `QiGuang-Next@1642fc810f543a7995ca334e0e4916c20968d858`（2026-11-04）；远端 dev 仍是较早的 `8e0e67e`。逐项实现和保留差异见 [Codex 实现对照](qiguang-codex-sync.md)，来源文件摘要追加在 `client/android/migration-sources.json`。

服务端最终组合回归 173 项全部通过，另有 1 项真实临时进程/文件锁交接测试通过。覆盖队列、原生交互、附件上下文、模型目录、历史收敛、结构化 diff、精确身份核对、Review 指纹、外部工作树和 Range/ETag；文件与 Git 测试使用受控 RPC fixture 后面的真实临时文件，不读取用户项目。命令和输出见 `workspace/artifacts/qiguang-codex-sync-20261107/server-validation.txt`。

Android 最终执行 `:app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug :app:lintDebug`，构建成功，141 项 JVM 测试全部通过（无失败、错误或跳过）。Lint 为 0 errors、30 warnings、2 hints；未把既有提示记为已修复。验证包括多个独立草稿、创建/发送 checkpoint、较新草稿保护、持久显示 receipt、ready 原生身份接管、跨页位置和顺序、缓存版本/节点隔离及下载取消。

APK 保留当前项目版本 `0.2.9` / versionCode `21`，本次不发布版本。产物为 `workspace/artifacts/qiguang-codex-sync-20261107/rivo-relay-0.2.9-qiguang-codex-debug.apk`（14,146,923 bytes），SHA-256：`71ce839d969bfa3927425c382bf89b0d3f2e09e21e26f486296538268456832c`。构建日志、JSON 测试摘要和四张交互截图保存在同一忽略目录。

本次没有部署或重启实际 Relay/Codex 节点、更新 LaunchAgent、安装到实体手机，也没有提交/推送 Git。真实 Desktop 双向接续、Windows 节点和多个真实设备的运行验收仍需实际环境验证；模拟器和受控 RPC 测试不替代这些结果。

最终模拟器验收使用本次专用的 `medium_phone` / Android 36（1080×2400），12 个测试类共 **43 项全部通过**，耗时 100.513 秒。覆盖模型/快速模式、固定澄清、队列恢复、目录信任、多个 pending 草稿与切换、Review/diff、文本/图片交互、扫光像素变化与终态、提交前落盘、大图按窗口解码、FileProvider 只读分享和外部工作树禁止清理。最终原始日志为 `android-ui-validation.txt`；四张 `codex-latest-*.png` 已检查布局。

此前测试中扫光时钟和合并 semantics、键盘弹出时队列按钮位移导致的点击时序均已修正测试夹具；保留真实触摸、具体操作身份、像素变化和停止状态断言，未通过放宽断言规避失败。最终全组重新执行通过。最后仅测试夹具改动又单独执行 lint 通过，待交付 APK 内容未改变。


### 2026-11-07：Desktop、原生历史与媒体后续迁移

继续对照同一 `QiGuang-Next@1642fc810f543a7995ca334e0e4916c20968d858`。Desktop protocol-1、按writer固定的断线/重连代数、实际host原生水位/重投影/备份回滚、原生历史失效、媒体代理/缓存/Range/溯源/墓碑、20/40MiB图片分块落盘、语音流及图片/音视频组件均已接入。此次源文件摘要36项追加到migration manifest，不覆盖上轮记录。实现和实际启用条件见 [完整对照](qiguang-codex-sync.md)、[Desktop桥接](codex-desktop-bridge.md)、[语音输入](codex-voice-input.md)。

服务端全量 **308/308**，零失败、零跳过；实际进程身份/锁/交接只针对临时编译的惰性进程和隔离CODEX_HOME。补充视频扩展名后相关5项再次通过。修复覆盖并发rollout追加、锁inode替换、代数切换、发布失败回滚、不复制auth/config、不自动重放、Desktop仅释放当前订阅及错误runtime不清理另一writer状态。

Android `assembleDebug` / `assembleDebugAndroidTest` / `testDebugUnitTest` / `lintDebug` 全部成功；**172/172 JVM** 无失败/跳过；lint **0 errors、42 warnings、3 hints**。语音13项测试使用fake transport/本地HTTP，覆盖PCM尾块、缓冲上限、光标插入、手改/目标隔离、能力缓存及失效请求不重发，不接真实ASR。

最终模拟器完整回归 **64/64**，201.143秒，使用本次启动的medium_phone / Android36，1080×2400。包含原有聊天、队列、审批、目录、pending、Review/diff及新增图片原版像素/手势、生成溯源只读、代理/凭据隔离、媒体库重新附加、真实FileProvider图片MIME、视频倍速/原地址转发、音频自动播放/暂停/seek/后台暂停/损坏文件重试和语音按钮布局。

大图显示解码被限制到屏幕2倍且每边至多4096px，内存缓存32MiB，保存仍使用原始字节。检查中发现禁用图像内存缓存造成两项缩放中间帧回归，改为有容量上限的缓存后，相关6项图片手势及2项音频复测通过，再完整执行64项通过。未删除或放宽像素/缩放断言。

产物目录：`workspace/artifacts/qiguang-codex-completion-20261107/`。APK `rivo-relay-0.2.9-codex-parity-debug.apk` 保留 versionName0.2.9 / code21；20,346,080 bytes，SHA-256 `3d0e75c73e77e17295c8696e9516e4f06c2faff59ebcc33347cfdc03f907b00c`。目录内包含server/build/UI原始日志和JSON摘要，截图采用受控媒体fixture。

本次未提交/推送Git，未部署节点、修改/重启真实Desktop、注入宿主加载器、写用户真实历史或安装实体手机。真实Desktop共享需要宿主加载适配模块；语音需要配置已有ASR；真实设备/Windows/跨节点运行仍需环境验收，不由模拟器和fixtures替代。

最后仅调整截图夹具（等待稳定帧、使用实际附件弹窗）后另跑5项全部通过，22.337秒；生成溯源、媒体库、视频菜单、语音输入截图已检查，生产APK SHA-256未变。本次启动的模拟器已关闭。

### 2026-11-07：本机节点更新与 Android 0.2.10 发布

用户明确授权更新本机节点，并选择 GitHub Release 永久下载。发布版本升为 `0.2.10` / code `22`，沿用 `dev.rivo.relay` 与原 debug 证书；证书 SHA-256 与已发布0.1.10和既有0.2.9一致：`3f31e18aa34d6144102e35b4640ff94e5fdfa3c92b074df474d02583934e1800`。最终APK 19,764,984 bytes，SHA-256 `ee76a66310348b528801b4dbdcee31f2877f0d0bd0c754b5244e628fa00bfbe5`。本次重新执行 assembleDebug、172项JVM测试与lintDebug，全部通过，lint为0错误、42警告、3提示。暂存检查另清理两个文件末尾空行后，再执行assembleDebug并复验签名，发布采用最后生成的APK。308项服务端与64项模拟器UI结果来自上节相同功能源码，版本发布未重复运行UI，也未安装实体手机。

更新前以SQLite backup API备份 `workspace/local-node/data/relay.sqlite3`，完整性检查为ok，依赖检查通过；无待执行/运行中的Relay队列项或pending提交。以SIGTERM平滑重启 `local.rivo-relay`，Relay PID从64497变为91223，节点ID及5个既有有效授权完全保留，数据库检查仍为ok。重启后的原生连接正常，项目列表21项，媒体代理/缩略图/溯源/媒体库能力已加载，真实原生文件读取产生WebP缩略图并正确返回206 Range内容。临时检查凭据已撤销，没有发送真实Codex任务。Desktop桥接仍未安装宿主加载器，ASR返回unconfigured；没有将代码部署误记为这两项真实服务已启用。

源码与发布记录保存到 `codex/release-0.2.10` 分支及 `v0.2.10` 标签；APK、SHA256SUMS随GitHub测试版Release交付，保留main当前状态。本机备份、源码文件摘要、构建、签名及部署验证记录在忽略目录 `workspace/artifacts/release-0.2.10/`，不上传节点数据库、连接凭据或私有运行日志。

## 2026-11-07 设置页与手动更新（0.2.11 本机构建）

底部增加设置页，登录页提供未配对入口；设置页展示版本、GitHub Release 手动检查、更新说明、浏览器 APK 下载和节点信息。公共更新请求独立于节点授权，包含预发布版本，以数字版本比较且不降级；错误和空发布列表不伪装为已是最新版本。当前没有后台自动检查或静默安装。

`testDebugUnitTest` 176 项通过；`assembleDebug` 和 `lintDebug` 成功，lint 0 错误、42 警告、3 提示。模拟器 3 项设置 UI 测试通过，覆盖检查中禁用重复点击、更新说明和下载跳转、失败重试、已是最新版本以及真实 App 底部 Codex/待办/设置导航。测试使用模拟节点，不访问真实会话。设置页截图已检查，无文字遮挡。APK versionCode=23，debug 签名与 0.2.10 一致；构建和截图位于 `workspace/artifacts/settings-0.2.11/`。本次未向 GitHub 发布 0.2.11，也未安装到实体手机。


## 2026-11-07：e2 Mac mini / MacBook Pro 服务端同步

- 按用户要求同步当前工作区 server/ 至两台 ~/Applications/Rivo-Relay；rsync checksum 复核无差异。未发布 Android APK、GitHub Release 或推送 Git。
- 服务端 403 项回归测试全部通过；两台依赖 pip check 通过。MacBook 虚拟环境补齐 pip。
- 各节点 workspace/backups/relay-sync-20261107-171930 保存更新前 SQLite 一致性备份、server 源码和 LaunchAgent 配置。原 workspace、HTTPS 入口和自启动配置保留；只 kickstart Relay。
- 两台各 5 个有效授权与备份逐项一致；临时验证授权已撤销。MacBook 原生连接正常，读取 5 个项目；e2 原有 Relay 连接断开，更新后重新连接既有 Codex PID 30257（11 月 5 日启动），读取 8 个项目。没有重启 Codex。
- 本机经 Tailscale HTTPS 访问两台 /health 均成功；认证 node/projects 在节点 loopback 验证通过。节点自身 HTTPS 回环探测遇到 TLS 错误，未关闭证书校验；不将其记为认证 HTTPS 验证通过。未进行手机 UI 或真实会话写入测试。
- 脱敏复核产物：workspace/deploy-multinode-20261107/。
