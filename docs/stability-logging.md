# 稳定性日志

节点和中转使用单行 JSON 运行事件，时间为 UTC。Mac 见 `workspace/local-node/logs/relay-error.log`、`gateway-error.log`；Windows 见节点及连接器 workspace 的 `node.log`；VPS 使用 `journalctl -u rivo-gateway --since '24 hours ago' -o cat`。上线新版服务后才会产生新增字段。

- `gateway_connected`、`gateway_disconnected`、`gateway_reconnecting`：连接器连接状态与下一次重试秒数；异常只记录类型，不记录异常正文。
- `gateway_node_connected`、`gateway_node_disconnected`：中转观察到的节点控制连接变化；node 是接入槽位，不是密钥。
- `gateway_request_finished`：中转请求结束，包含 request_id、node、status、outcome、duration_ms。只有 outcome=completed 表示收到上游 EOF 且写完响应；200 加异常 outcome 仍是中断。rejected 表示未进入成功传输，需结合状态码判断（例如节点离线 503）。
- `http_response`：节点开始发送响应时的状态码和 request_id。
- `node_request_finished`：节点处理函数退出时的耗时和状态。handler_returned 仅表示函数正常返回，不承诺客户端完整收到响应。

中转生成新的随机 request_id，覆盖外部同名请求头，通过连接器传入节点；直连节点生成或接收合法的 32 位小写十六进制 ID。该字段只用于排查，不作为鉴权依据。按相同 request_id 对照两端事件，可找出中转失败但节点没有收到、节点处理慢、节点已返回但中转未完成等情况。日志不记录 URL、查询参数、请求正文、令牌或一次性票据。

分析时按固定时间窗口统计请求总数、5xx 比例、非 completed 的中转请求数量、连接断开次数与重连间隔。短请求可统计耗时分位数；当前没有路由分类，SSE 和下载会拉长耗时，不能直接把所有请求的 P95 当作交互延迟。耗时使用单调时钟，跨机器事件排序仍需系统校时。

边界：这些是被动事件，不是持续健康探测；没有请求的时间段不能推断服务可用。`/health` 只证明 HTTP 存活。进程强制退出可能没有结束事件，需结合系统服务日志。日志仍沿用系统/部署现有保存方式，本次没有自动轮转、保留周期或告警配置；长期采集前应为文件日志配置轮转，并确认 journald 保留期限。

## 读取拥堵保护

普通鉴权 GET 的业务路由使用 30 秒协作式读取预算；同会话操作锁、writer 锁、历史一致性锁等待和只读原生 RPC 等待会检查预算。经连接器访问的本地明文 HTTP 还能检测对端断开，停止等待并清理 RPC pending；不终止原生运行时。超限返回 `504/read_deadline`。SSE、文件传输以及 POST/PATCH 等提交操作不进入该预算。已发出的 resume、修复或写操作仍保留原有结果核对语义，不因 HTTP 读取预算强制中断。

预算不是线程强杀：其他同步 I/O、已开始的修复/重建和非只读 RPC 仍按原有超时完成；直连 TLS 不使用 MSG_PEEK 检查断开，但保留预算检查。因此不能把 30 秒解释成所有接口严格的端到端上限。

连接器将 VPS（49 连接，含控制连接）与本地 HTTP（48 连接）分池，避免 48 个传输任务各占两条连接时争用原来的单一 64 连接池。节点工作线程满载时，明文入口返回 503、Connection: close 和 Retry-After: 1；未完成 TLS 握手的入口仍关闭连接，不发送明文 HTTP。

新增诊断字段/事件：

- `http_response.headers_ms`：处理开始至发送响应头的耗时，与 SSE 存续时间分开。
- `node_request_finished.route`：固定白名单类别，未知类别为 other；不包含会话 ID、原始路径或查询参数。
- `gateway_transfer_failed.stage/request_id`：传输失败阶段；收到请求描述前 request_id 为空。
- `read_lock_wait`：至少 1 秒的锁等待及是否取得锁，不含会话身份。
- `http_worker_rejected`：工作线程容量拒绝。

## 空闲订阅断开清理（2026-11-09）

本机日志在北京时间 23:19:18–23:19:26 记录五次 events 429。已复现一个可导致名额滞留的缺陷：中转关闭本地明文连接后，空闲 SSE 仍等待心跳写入才发现断开。节点现在每轮事件等待前检查 TCP EOF，通常在一秒等待周期内释放名额；不修改正常心跳、客户端失联超时或连接上限。TLS 直连仍沿用写入失败检测，不能据此宣称所有断线原因已解决。

新增真实 HTTP 回归在旧实现上因三秒内未释放名额失败，修复后通过；API 55 项测试通过。未进行真机、模拟器验证或部署。数据通道握手失败和响应被重置尚不能仅凭现有日志确定原因。

继续审查确认数据通道握手存在取消竞态：`prepare()` 等待期间原 HTTP 请求取消，会在握手返回后向已取消 Future 写结果，触发 InvalidStateError 并跳过 active 连接清理；握手抛错也绕过 claimed 清理。已将整个握手纳入 finally，并在交付通道前重新核对票据和 Future 状态。两个定向测试在旧实现上分别失败、报错，修复后中转 27 项回归全部通过。此代码缺陷已证实，但未据此认定现有每条握手失败日志均由该竞态造成。
