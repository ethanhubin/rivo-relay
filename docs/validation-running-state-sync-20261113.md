# 运行状态同步修复与验证（2026-11-13）

目标会话：`01a1d24d-777a-7f03-8a69-6f7d133737e8`。用户反馈新版手机客户端未显示电脑运行状态，打开详情后列表也未及时同步。

## 撤回

已撤回把活动表默认空闲查询间隔从 60 秒改为 5 秒的修改及对应测试；本次最终实现保持原有轮询参数。现场后续采样有 236 个待核验会话，原有逻辑已经采用 5 秒查询；不能用缩短空闲间隔解释或修复该现场。之前通过的 25 项测试只验证了被撤回方案，不代表根因已修复。

## 修复前确认的断点

1. `NodeSessionSync.invalidate` 对任意非空目标的执行事件递增全局 `activityEpoch`。详情读取期间其他会话发生事件，也会使 `read` 丢弃整个响应附带的活动快照，但详情响应仍正常交付。
2. `CatalogState.reconcileSession` 只更新标题、时间；列表执行状态由 `withNodeActivity` 覆盖。因而详情内容正确不能补救被丢弃的共享活动快照。`NodeActivity` 的活动接口读取也使用全局 epoch 比较，其他会话事件同样可以使整份查询结果不生效。
3. 服务端 `ActivityRegistry.reconcile` 和 `observe_execution` 更新内存活动表后，没有向 SSE 发布状态变化。原生事件正常转发时可以通知客户端；但通过后台核对或详情读取才发现的变化，没有对应通知路径。

## 复现与对照

Android 临时诊断使用真实 MockWebServer HTTP/SSE：先取得目标 t 的 idle 活动表，启动 t 的详情请求，期间发出 other 会话的 turn/completed，随后返回包含 running 及完整活动快照的详情。暂缓后续活动查询，观察该详情是否能立即纠正列表。

- 缺陷组：`detail=running shared=idle list=idle projectRunning=[]`，预期列表 running 的断言失败。
- 对照组：只将事件替换为不会使执行状态失效的 project/updated；`detail=running shared=running list=running projectRunning=[p]`，测试通过。
- 服务端诊断：后台核对 idle→running 发布 0 个通知；详情证据 running→idle 发布 0 个通知；直接原生 turn/started 发布 1 个通知，说明不是诊断事件接收器失效。

诊断源码和 XML 保存在 `/private/tmp/rivo-running-root-cause-evidence/`；Gradle 日志为 `/private/tmp/rivo-activity-root-cause-controlled.log`。临时诊断测试已移出仓库，避免将已知失败的分析用例混入正常测试。诊断运行包含一个预期的缺陷断言失败，不能记作回归通过。

## 性能分析

现场 10 次本机活动接口采样：248 个已知会话、3 个任务、236 个待核验会话；JSON 20,111 字节，gzip 正文 5,803 字节。本机 HTTP 中位数 0.651 ms，最大 11.79 ms。按同样数据离线运行 100 次内存快照、JSON 序列化和 gzip，中位数 0.116 ms；不包含鉴权、网络和手机解析。

真正稳定空闲时，60 秒改为 5 秒会把每个有订阅节点的定时查询由约 60 次/小时增加到 720 次/小时。以此样本正文大小估算，仅压缩正文额外约 3.65 MiB/小时；不含 HTTP/TLS 开销，且不代表所有空闲节点的固定响应大小。节点 CPU 开销较小不等于手机耗电和唤醒可以忽略。未进行蜂窝网络或手机功耗实测。

最终实现保持运行中 5 秒、空闲 60 秒的兜底间隔，不用修改间隔解决同步链路缺陷。新增变化检测使用模拟的 248 会话、236 待核验会话表重复 1,000 次，无变化通知为 0 条，检测中位数 0.034 ms；单会话变化通知 JSON 为 128 字节（短测试 generation/project 值，真实长度会变化）。这些是本机微基准，不代表手机性能。

## 最终实现

- 节点后台核对、提前取得运行证据、详情观察统一比较可见执行状态；仅变化时发布 `relay/activityChanged`，携带受影响会话 ID、generation 和 revision。不把扫描计数、读取版本、历史内容或可能在扫描时省略的 turnId 当作状态变化，避免详情读取反复产生通知。无受影响会话时不发通知。
- 客户端活动读取与详情共享同一套按会话划分的过期保护。其他会话的新事件只使那些行待核验，不丢弃目标会话的权威状态；新版本快照仍遵守原有版本排序。
- 通知的版本已包含在详情结果中时接受该结果；同会话未覆盖的事件、迟到完成事件、重连和旧运行时代际继续阻止过期结果生效。进入异步队列的结果在消费时再次核对，迟到的版本通知不能抹去无版本原生完成事件的保护。
- 新活动通知只唤醒共享活动读取，不触发项目/列表目录刷新；已被当前活动快照覆盖的通知不重复请求。当前会话需要的详情刷新仍按既有事件范围处理。
- 已在活动表、客户端共享状态与过期保护实现中加入注释，说明实时性依赖变化通知和权威状态合并，性能依赖去重、按会话隔离与共享读取，而非反复调节轮询频率。

## 最终验证

服务端 150 项通过：activity、session_summary、history_read_optimization、events、codex、client_projection。日志 `/private/tmp/rivo-activity-server-final.log`。

Android 30 项相关测试分批通过：ActivityReadFenceTest 4、ActivityDetailSyncTest 1、NodeActivitySyncTest 3、NodeActivityTest 4、NodeSessionSyncTest 10、RefreshConcurrencyTest 8。复用未受后续改动影响的结果；最后新增的通知边界和条件等待用例分别重新验证。日志 `/private/tmp/rivo-activity-fixed.log`、`/private/tmp/rivo-activity-notices.log`、`/private/tmp/rivo-activity-detail-final.log`。Debug 主代码和单元测试 Kotlin 编译通过，`git diff --check` 通过。

回归覆盖原始详情→共享表→列表失败场景、空闲收到通知立即刷新、已覆盖通知不重复读取、无关会话隔离、同会话新事件、重连、迟到版本通知及稳定状态无通知循环。早期服务端检查发现旧测试未区分新增语义通知、沙箱禁止 Unix socket；更新通知断言并在允许测试 socket 的环境完成最终检查，不把中间失败计入通过结果。

新通知为兼容性扩展。新客户端连接旧节点仍有按会话隔离改进，但后台发现变化主动通知需要更新节点；完整效果需客户端和节点都采用此修复。

## 现场限制与交付状态

诊断时本机节点 versionCode 141 的活动表与会话详情均返回目标轮次 running，详情 nativeSync.state=ready；没有取得原问题发生时的手机日志及原生→节点→SSE 完整事件轨迹。上述断点已通过代码与受控复现确认，但不能断言原现场的开始事件具体丢在何处，也不能认定所有延迟都来自同一竞态。

所有临时诊断配对均已撤销。代码位于独立分支 `codex/fix-running-state-sync`，尚未部署；未重启会话或节点、未运行 lint、未构建发布 APK、未发布，未进行真机或模拟器验证。
