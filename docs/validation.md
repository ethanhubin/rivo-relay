# Rivo Relay 开发验证记录

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
