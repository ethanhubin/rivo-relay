# 输入区上下文用量

## Codex 源码依据

本次查看 OpenAI Codex 开源 Rust 实现（main，2026-11-12 获取）；未分析闭源桌面客户端的环形组件。

- [protocol.rs](https://github.com/openai/codex/blob/e58bf8f5233b51e7cd1679d947e3028c1ed91b06/codex-rs/protocol/src/protocol.rs)：`TokenUsageInfo::new_or_append` 将每次响应累加到 `total_token_usage`，同时用最新响应替换 `last_token_usage`。`tokens_in_context_window` 返回 `total_tokens`，因此上下文不能使用会话累计消耗。
- [status/card.rs](https://github.com/openai/codex/blob/e58bf8f5233b51e7cd1679d947e3028c1ed91b06/codex-rs/tui/src/status/card.rs)：状态卡使用 `last_token_usage` 和 `model_context_window`，展示剩余比例及 used/window 数值。终端剩余比例通过 `percent_of_context_window_remaining` 计算：从窗口与占用中扣除 12,000 tokens 固定基线，再计算剩余比例并四舍五入。

## Rivo 展示口径

服务端已有 `thread/tokenUsage/updated` → 会话 `tokenUsage` 投影，Android `SessionSnapshot` 已解析该字段。无需新增统计接口。

输入区发送/停止/继续按钮左侧仅显示环形进度图标，不显示文字或百分比。采用 `last.totalTokens / modelContextWindow` 的实际窗口占比，向下取整并限制到 0–100%；不扣终端的固定基线，以保持百分比和 tip 数值一致。环形填充保留原始比例精度。点击弹出 tip，显示已用/剩余比例、精确已用 tokens、窗口容量及统计口径；再次点击同一按钮或返回关闭，切换会话清除展开状态。

缺少有效统计时显示空环，tip 说明统计不可用；已上报的 0 在 tip 显示 0%。窗口未知时仍可查看已知占用。超出窗口时环形限制在 100%，tip 保留原始数值；压缩后的最新统计可使比例下降。移除会话标题旁的重复百分比。该统计来自最近一次模型调用，不估算尚未发送的草稿，也不是实时计费消耗。

## 本次验证

`:app:testDebugUnitTest --tests dev.rivo.relay.ContextUsageTest` 通过（3 项），包含 Android 主代码与单元测试 Kotlin 编译；`git diff --check` 通过。未运行 lint，未进行真机或模拟器 UI 验证，未构建发布 APK。

后续图标与 tip 开关调整：`:app:compileDebugKotlin` 通过；统计逻辑未变，复用上述单元测试结果。未进行真机或模拟器交互验证。
