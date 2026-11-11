# 会话列表运行状态漏显示（2026-11-11）

## 现场证据

目标会话 `01a1c814-ab5e-74d3-95a1-dfcce9168412` 的原生目录返回 `notLoaded`，但日志生命周期为 `inProgress` 且 writer lock 被持有。使用已部署服务端代码进行补充探测得到 `running`；实际运行的 Relay `/v1/activity` 同样返回该会话 `running`、`executing_with_queue`、排队数 1，且不在 unknown 集合中。完整活动扫描单次耗时 6.48 秒并成功识别该会话。

本机服务端保留的最近 450 条事件中，19 条是 `relay/writerDisconnected`，其 `threadIds` 均为空数组、`ownedWriter` 为 true。它们不表示目标会话停止。原 Android `nodeActivity` 将这些通知与其他会话的生命周期通知全部转换为全局 `confirmed=false`；列表、项目和详情共享此投影，因此服务端已知正在运行的会话也被客户端显示为 unknown，失去运行标记。无关通知还会使正在返回的活动快照失效。

现场未采集手机端逐事件日志，不能逐条还原用户看到漏显示的时刻；上述通知在真实服务端持续出现，且相同通知已通过客户端真实 SSE/HTTP 链路的回归测试复现故障。

## 修复

活动状态按通知中的 `threadIds`、`threadId`、`conversationId` 或嵌套 thread ID 局部失效。空数组不使任何会话失效，项目元数据事件只触发刷新。事件流中断、重连、reset、撤销授权和原生节点断线继续使全局状态失效；缺少可确定会话范围的生命周期事件保持保守处理。详情快照使用同一规则，空 writer 和项目元数据通知不再丢弃有效响应。

## 验证

校准测试先建立 SSE 连接，再读取运行快照，以避免将首次 connected 事件混入待测场景。对照未修复源码，空 writer 断连用例确实失败：期望 running，实际 unknown；恢复修复后验证同一用例及相关回归。

最终结果：19 项测试通过，debug Kotlin 编译通过，diff 空白检查通过。

检查范围：NodeActivitySyncTest、NodeActivityTest、NodeSessionSyncTest、ProjectActivityProjectionTest，以及所需 debug Kotlin 编译；覆盖无关会话结束、空 writer/其他 writer 断连、目标 writer/节点断线、恢复刷新、旧版本快照与延迟详情响应的保护。

未运行 lint、未构建发布 APK、未进行真机或模拟器 UI 验证；上述验证阶段未提交、推送或部署，未重启 Codex 原生运行时。HTTP 诊断使用的临时本机凭据均已撤销。
