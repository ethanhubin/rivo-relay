# 存储基础模块迁移记录

日期：2026-10-29。本批仅实现可独立验证的服务存储基础，不代表 Desktop 接入、完整服务或阶段 1/2 已通过。

## 来源

来源仓库为 `/Users/ethan/Documents/QiGuang-Next`，读取时 HEAD 为 `f31333c`。仓库整体存在本地修改；本批两个来源文件的 `git diff --stat -- <文件>` 无输出，以下摘要对应实际读取内容。

| 源文件（相对 QiGuang-Next） | SHA-256 | 使用范围 |
| --- | --- | --- |
| `qiguang/server/qiguang_sqlite.py` | `6cc53425b7c8d5894c5e0441cd170f45620065328f95870847a97440ca17a5cb` | 事务/savepoint 回滚与提交、WAL、FULL 同步、外键、等待超时和 schema 版本检查 |
| `qiguang/server/qiguang_todos.py` | `c59145d7064c0b4cf1288a572a08a74f836f5b771933261594f59eb20780ece9` | 标题/说明校验、客户端 ID、CRUD、完成状态与时间字段的精简改写 |

未复制旧数据库或运行数据，未修改来源文件或服务。来源文件未带许可证头；本记录说明来源，不追加或推断新的开源许可。此次模块仅使用 Python 标准库。

## 迁移与新增

- `server/rivo_relay/database.py`：一个连接由可重入锁保护，嵌套事务使用 savepoint。新库以 `0600` 建立，schema 只包含配对码、手机连接、提交、待办四张表；已有非 Rivo 库或不兼容版本拒绝打开，不隐式迁移。
- `server/rivo_relay/todos.py`：保留基本 CRUD、客户端 ID 与完成时间，新增 project/thread 关联。删除分类、优先级、日期、提醒、循环、附件、子任务、视频依赖。相同客户端 ID 且内容一致可返回原待办；不同内容返回冲突，避免来源实现无条件返回旧记录掩盖冲突。关联不会完成待办，编辑标题不会改变原生 thread，重新关联不同目标返回冲突。
- `server/rivo_relay/pairing.py`：新增本地生成短期码、原子兑换、随机长期令牌、摘要存储、验证及撤销。默认 5 分钟，二维码与手动输入共用同一码，忽略分组连字符和大小写；无账号、角色或项目权限表。兑换尝试按来源地址及全局滑动窗口限制，只保留有界内存计数；已匹配码的兑换次数记录于配对表。服务应复用单个 store，限流窗口在服务重启后重新计数；高熵配对码与到期时间仍由数据库保留。
- `server/rivo_relay/submissions.py`：新增全局唯一 submission ID、连接归属、operation、固定目标、请求摘要、状态与原生回执。claim 在返回可执行前提交；所有相同 ID 的后续请求均不再次执行，改变归属/操作/目标/正文则冲突。正文不存储，不迁入旧 outbox 或队列；去重记录没有自动清理接口。

## 集成约定

所有 API 同步执行，返回普通 Python `dict`/`list`，时间为 Unix 秒。路径和请求结构由集成层校验；不在这些模块调用 Codex 或网络。

| 模块 | 入口 |
| --- | --- |
| 数据库 | `Database(path)`；`transaction()`、`read()`、`close()` |
| 配对 | `PairingStore(db).create(ttl_seconds=300)`；`exchange(code, name, client_key=remote_address)`；`authenticate(token)`；`list_connections(include_revoked=False)`；`revoke(connection_id)` |
| 提交 | `SubmissionStore(db).claim(submission_id, connection_id, operation, target, payload)`；`get(submission_id, connection_id=None)`；`find_for_target(operation, target)`；`checkpoint(submission_id, receipt)`；`mark_accepted(submission_id, receipt)`；`mark_unknown(submission_id)`；`mark_failed(submission_id, error_code)`；`recover_pending()` |
| 待办 | `TodoStore(db).create(title, notes='', todo_id=None)`；`get(id)`；`list(completed=None)`；`update(id, title=..., notes=..., completed=...)`；`delete(id)`；`link(id, project_id, thread_id)` |

`claim()` 返回 `{should_dispatch, submission}`，只有 `should_dispatch=True` 可以调用原生运行时。`accepted` 表示原生已接受，不能作为任务完成或待办完成。原生调用超时或回执无法确认时标记 `unknown`；只有确认失败才使用 `mark_failed`。`receipt` 强制仅允许 thread/turn/item/request/project/runtime/connection 的 ID 字段，以及本地待办关联的 `todoId`；支持 `threadId` 与 `thread_id` 两种命名，不接收聊天正文或嵌套结果。

新建会话交付待办时，按 `claim → create_session → checkpoint(threadId) → link → send → mark_accepted(turnId)` 执行。checkpoint 在继续操作前持久化已创建的 thread，不修改 pending/unknown 状态，也不赋予重试执行权；未知、失败和重启恢复均保留已知 ID，接受回执在原 ID 上合并，不允许替换已知身份。若创建会话本身的回执丢失，仍须保持 unknown 并核对，不能自动重复创建。

`recover_pending()` 必须在服务独占启动、接收请求前调用一次，把上次未取得回执的 `pending` 变为 `unknown`。普通 CLI 打开数据库不能调用它，避免生成配对码时误改正在执行的请求。开启 store 不会自行恢复或重发操作。

配对码仅由本地 CLI 调用 `create()`，不得暴露匿名生成路由；`client_key` 使用可信的连接远端地址，不直接信任请求提供的转发头。HTTP 层验证每次请求的令牌，撤销时还应关闭该连接已有的 SSE；存储层只负责令牌失效。`authenticate()` 返回连接公开信息或 `None`，不会返回令牌摘要。

领域异常提供 `code` 与 `status`：配对无效为 401，限流为 429（带 `retry_after`），提交/待办冲突为 409，未找到为 404。普通字段错误使用 `ValueError`，由 HTTP 层映射为 400。

## 验证

执行命令：

```sh
PYTHONPATH=server:server/tests .venv/bin/python -m unittest test_database test_pairing test_submissions test_todos -v
```

最初独立批次的 29 项标准库单元测试通过；后续增加了 8 MiB payload 摘要存储和构造失败资源释放回归，当前集成结果见[开发计划](development-plan.md)。原批次覆盖：跨 SQLite 连接并发兑换/claim 只有一次成功、配对过期/重复兑换/来源及全局限流、摘要存储、撤销与重开库、嵌套事务回滚、非 Rivo schema 拒绝、claim 提前持久化、固定目标/摘要冲突、重启 pending 恢复、unknown/failed 不重发、thread checkpoint 的持久化/恢复/回执合并/身份冲突、todoId 回执与空回执拒绝、按目标查询既有提交，以及待办 CRUD/关联/重启恢复。

测试数据均位于独立临时目录，未访问用户 Codex 数据、连接真实运行时或执行项目指令。服务 HTTP、CLI、真实 Desktop 和 Android 的集成验证由后续步骤单独记录。
