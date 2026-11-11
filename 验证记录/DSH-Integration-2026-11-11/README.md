# DSH 既有运行时接入验证

目标：Rivo 原生 APP 通过原有扫码配对连接既有 DSH 桌面，实现文本聊天、审批和断线恢复。

## 版本与环境

2026-11-11 查询官方 npm `@deepseek-ai/dsh` latest/next 均为 `0.2.2-rc.1`；桌面官方更新源 `https://download.deepseek.com/dsh-desk/feeds/mac-arm64/nightly-mac.yml` 同为 `0.2.2-rc.1`，已安装 `/Applications/DeepSeek Harness.app` 的版本亦为该版本。因此活动桌面已经最新，没有替换或重启桌面。旧 Web 启动脚本仍指向 `0.1.7-rc.2`，但不是当前活动运行时；本轮未启用旧 Web。

为读取同版本公开接口另外安装了 `~/.dsh/rivo-runtime-0.2.2-rc.1`；它只作接口研究依赖，没有启动独立 DSH Agent。运行包静态身份：app.asar SHA-256 `f21d83abfb33d463822a52c439cc9b15c8245dbce4ae4cf9424c90eae8eba94f`；REA retained evidence `ev_d4f9dfd6a458644fa75794042956d98db21a12e9545bbb5821c8502b0e403bcb`，分析范围有限，不是完整安全审计。

桥插件通过现有桌面 profile 的 HMR 热加载，副本位于 `~/.dsh/profiles/desktop/rivo-bridge/index.mjs`，新增 row `rivo-dsh-bridge-validation`；原配置备份为同目录 `cordis.patch.yml.before-rivo-20261111-161914`。桥仅监听 `~/.dsh/rivo/bridge.sock`（0600，父目录0700）。回退应只移除该 row，避免整份备份覆盖后续用户配置变化。

Rivo 独立验证节点监听本机 `18785`，workspace 为项目内 `workspace/dsh-validation/relay`，不替换现有 Codex 节点。原生测试会话 `session-c2d2026e-eb0a-444f-bdce-2cd37d555e15`，工作目录 `workspace/dsh-validation/project`；原生权限为 workspace-write/ask。

## 当前证据

- 已通过真实 Unix socket 读取活动 DSH 的目录和会话，原生会话创建成功。
- 经 Rivo 一次性配对、HTTP 鉴权与发送接口，DSH 返回 `RIVO_DSH_HTTP_OK`，历史可读取。
- 首次输入已被原生接受但 Rivo 初版回执核对失败，保留 unknown；修复后通过原生持久 requestId 核对恢复 accepted，没有新增输入身份。
- 原生 Bash 请求写入 `workspace/dsh-validation/approval-proof.txt`，真实审批转发为 `runtime/approval`。APP 允许本次写入；DSH 随后另请求读取同一文件核验，APP 第二次允许后收到 `RIVO_DSH_APPROVAL_OK`。首个仪器脚本只预期一次审批，因此停止等待并为第二个请求重新运行，没有把一次授权扩大为后续自动授权。
- `DshLiveTest#cameraPairing` 通过：模拟器真实摄像头和 ZXing 解码，无预解码 Intent。
- `DshLiveTest#nativeApproval` 最终通过：原生审批、APP 选择、DSH 执行与最终回复。
- `DshLiveTest#chatAndNetworkRecovery` 在最终桥/后端的新会话 `session-53cb020a-dc57-4704-8ade-b60da155db4f` 通过（17.793 秒）：APP 发消息得到 `RIVO_DSH_ANDROID_OK`，关闭 Wi-Fi/移动网络进入 stale，恢复后消息身份与正文完全一致。
- Python 受影响回归累计 86 项、桥 Node 测试 10 项、Android 定向单测 36 项通过；debug 与 androidTest APK 构建通过。官方版本和连接检查见 [connectivity.json](connectivity.json)。Python 范围为 `test_dsh`、`test_submissions`、`test_submission_recovery`、`test_config`；Android 单测范围为 `SubmissionCoordinatorTest`、`NativeFormsTest`、`ConversationStoreTest`。
- 截图复核曾发现自动更新弹窗遮挡及节点活动状态仍显示待核验。现已修复 activity 的权威快照、审批 waiting、断线失效与版本契约，新增相关回归；关闭更新提示后的[最终截图](dsh-chat-recovered.png)显示 DSH 已连接、回复已完成，无待核验状态。未安装更新提示中的正式版本，避免覆盖验证 APK。
- 最后只读复核发现迟到的旧 status 回包可能覆盖新代际，两个 status 入口已统一加入 generation/control epoch 校验；新增 3 项竞争与合法重连回归后，DSH 20 项全部通过。独立验证节点已重新加载最终代码并通过连接检查；该小修未重复 Android 流程。
- 未进行物理手机、公网 VPS、Claude Code 或全部 DSH 工具插件验收。停止任务竞态仅完成单元验证，未做 APP 停止操作验收。

本轮不运行 lint，不发布、不提交、不推送。

扫码完成后已恢复模拟器摄像头默认海报及原始视角，删除本轮临时配对二维码、JSON 和明文验证 token。保留 APP 中的验证节点配对和测试会话，便于复核。

## 范围与架构后续

当前支持文本、既有工作区会话、原生审批和重连。沿用 DSH 模型设置；附件发送、队列、目标、工作树不在本轮范围，部分通用 UI 入口尚未完全按后端能力收敛，服务端拒绝不支持操作。当前仍使用 Codex 兼容投影，是验证原型，不代表多后端契约重构已完成。架构方案见[多运行时接入架构](../../docs/multi-runtime-architecture.md)。
