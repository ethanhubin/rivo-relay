# App 永久删除会话验证（2026-11-11）

基于 `origin/main` 的 `7bffb04`，分支 `codex/delete-session`；独立工作树 `/private/tmp/rivo-delete-session`，保留原工作区未提交改动。

## 改动

会话列表长按选择后可删除单个或多个会话，普通列表和归档列表均适用；聊天更多菜单增加永久删除。确认框明确无法恢复且包含派生子会话。列表仅允许状态明确、无排队或待处理工作的会话；聊天入口沿用实时控制权限、活动轮次、请求和队列限制。

新增 `POST /v1/threads/{id}/delete`，使用原生 `thread/delete`，沿用 submissionId 去重和不确定结果只核对机制。Relay 在同会话操作锁内检查执行队列和待回答请求，原生失败不移除本地目录条目。成功回执刷新目录，聊天删除成功返回上级页面。项目文件与托管工作树不由此操作清理。

## 实际验证

- Python 3.13：`test_codex` 与 `test_api` 共运行 109 项，初次 108 项通过，新增成功删除测试因服务启动也会使目录缓存失效而断言失败；将启动移到断言区间之前后，单独重跑该测试通过。其余 108 项复用原结果。
- Android：`:app:testDebugUnitTest --tests dev.rivo.relay.CatalogControllerTest --tests dev.rivo.relay.DeleteSessionTest` 成功，包含最终主代码和测试代码编译；32 项测试全部通过。
- 覆盖删除接口鉴权、相同提交 ID 去重、原生拒绝时保留本地状态、队列/请求阻止删除及客户端状态判断。
- `git diff --check` 通过。

未运行 lint、真机或模拟器 UI 测试，未连接真实 Codex 执行删除，未验证当前部署节点的原生版本支持情况。未构建发布 APK 或发布。提交 PR 前复查删除确认、目标身份、提交去重、成功刷新及失败保留路径，未发现阻塞问题；复用上述测试结果。
