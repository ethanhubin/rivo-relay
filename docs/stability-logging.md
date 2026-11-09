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
