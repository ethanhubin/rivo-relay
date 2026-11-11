# 会话流式显示与底部跟随

## 桌面端依据

只读检查本机 `ChatGPT.app/Contents/Resources/app.asar`（包含 Codex 界面），SHA-256 为 `a77588b2dcfa9b394d722dd8c2c8b13d3a09bacd49c839cc715245742dba6048`。依据为安装包中的 JavaScript，不是原始开发仓库，也不是运行时录屏。

- `225284.63835849b6.js` 的 `o7K3`：助手回答传入 adaptive buffering，`commentary` 不使用该缓冲。
- `380245.a77eae106c.js` 的 `i12N` / `hSWQ`：区分完整文本与显示文本，定时推进并按帧发布；完成、替换、后台等状态直接显示完整内容。
- `884779.bf167971f9.js` 与 `3773.7593174edc.js`：Codex 会话接入滚动控制器，区分跟随和阅读状态，使用距底部距离、用户滚动意图及布局补偿。
- `992741.f8405d6468.js`：桌面新轮次定位还使用可消耗的底部占位。本次沿用 Android 当前新轮次位置，没有引入桌面新轮次置顶布局。

滚动相关选定 bundle 的 REA 静态分析记录：`ev_e48cb031f7f9a087184053a0bedde01da3a1a4ead88cac8f0ad53f1893c48ea8`。动态依赖与功能开关仍有未知项，不宣称所有分支在本机会话启用。

## Android 调整

最终回答在显示层使用独立缓冲，约每 50ms 按接收速率和积压量推进，在显示帧发布；不改原生数据或持久化文本。已有历史不重播，执行说明即时显示，结束/中断/禁用动画/进入后台时补齐；UTF-16 截断不拆开代理对。

显示文本在列表外推进，让正文与底部请求在同一次重组中提交，先请求下一次测量的末尾锚点，再测量新内容，避免先展示增长后的布局再纠正位置。异步媒体和视口变化仍保留布局观察兜底。用户向上滚动暂停跟随，主动向下回到距底部 24dp 内才恢复；跟随时不因临时布局变化闪现“回到最新”按钮。

## 验证

- `StreamingTextBufferTest` 6 项、`MarkdownMessageBoundaryTest` 7 项通过；debug App 与 Android 测试 Kotlin 编译、调试 APK 打包通过。
- Android 16 / API 36、arm64 只读临时模拟器：`ChatScrollFollowingUiTest` 2 项、`ChatScrollUiTest` 6 项通过。逐次布局检查覆盖尾部高度从 60dp 增至 120/360/700/900dp、结束尾行插入，记录的每次布局底部误差均不超过 1px；同时验证长消息阅读位置、历史分页、重连验证、切换会话及恢复跟随。
- UI 为合成数据回归，不是连接真实节点的流式任务录屏；未做性能帧率基准。日志保存在工作区忽略目录 `workspace/diagnostics/streaming-stability-20261111/`。
- 未运行 lint，未构建发布 APK，未发布，未进行真机验证。
- 用户明确授权后完成模拟器验证。首次运行在测试开始前因模拟器 App 启动 ANR 退出；系统同时出现蓝牙服务反复崩溃。在临时副本关闭蓝牙和联网后，分两组重跑全部 8 项通过。原 AVD 不保存修改。
- 从缓存的 `origin/main`（`7bffb04`）创建 `codex/streaming-stability`；开发时两次 fetch 因 GitHub TLS 连接失败，提交 PR 前 fetch 成功，已重新核对远端主线及分支差异。原发布分支保留。

## 状态边界复查与修复

- 原生 `agentMessage` 的 `item/completed` 可不带 `status`。服务端现在按会话、轮次和消息 ID 将已观察到的完成状态投影为 `relayCompleted`；Android 收到该状态后立即补齐显示缓冲，即使轮次仍在执行。此标记只加到响应副本，不修改原生事件或原生持久化数据。终态判断同时覆盖 `canceled` / `cancelled`，避免取消状态仍开启缓冲。
- 完成标记在原生同步状态判定之后加入，更新内容头版本以避免被“内容未变化”响应省略，同时不改变历史分页版本。该修复需要配套服务端版本；旧节点没有该标记时仍按消息原生状态或轮次终态停止缓冲。
- 修复后 `StreamingTextBufferTest` 7 项与 `MarkdownMessageBoundaryTest` 7 项通过，Android debug Kotlin 编译通过。服务端 `test_live_message_projection`、`test_client_projection`、`test_history_read_optimization.ReadScopeTests` 共 30 项通过，覆盖无状态完成、会话/轮次隔离、原生数据不变、同步状态和分页版本不受影响。
- 本轮复用此前 8 项滚动模拟器结果；深入审查阶段另有前后台、关闭动画、完成及切换会话、手势恢复跟随共 4 项临时补测通过。最初默认滑动起点受输入区影响，调整起点并确认到底后的手势测试通过。临时测试和日志保存在忽略目录 `workspace/diagnostics/streaming-boundary-review/`，测试源码已从源码目录清理。
- 修复后的完成边界由服务端投影回归及 Android 单元测试验证，本轮未重跑模拟器、未进行真机或真实节点端到端流式验证；未发布。
- 合并前将最新 `origin/main`（`c100d44`，包含会话删除和计时动画）无冲突合入功能分支；已检查交叉文件的兼容性，并在合并后代码上重跑上述 Android 14 项与服务端 30 项定向回归。
