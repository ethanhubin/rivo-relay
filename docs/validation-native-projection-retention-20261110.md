# 原生历史投影淘汰与按需重建验证（2026-11-10）

## 结论

当前桌面原生运行时能够在显式 `thread/resume` 时重建简单会话的缺失投影，但普通历史分页读取不会透明重建。分叉会话还依赖父历史段投影，恢复子会话不保证补齐父历史。因此不能把共享 `thread_history_1.sqlite` 当作可随意按时间淘汰、读取时必然恢复的缓存；本次不实施容量限制，不修改现有聊天数据。

未在本次检查的官方配置/API 文档中找到投影容量配额、TTL 或安全淘汰接口。这是文档范围内的检索结果，不是对所有内部实现的不存在证明。

## 实验隔离与身份

- 测试对象：ChatGPT 桌面包内 `0.167.0-alpha.3.2`，arm64；实际响应 `cliVersion` 与包版本一致。
- 可执行文件：`/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex`。
- 二进制 SHA-256：`a636331ed87605efc4cf84028ac2884a75363f9d10ead63cffb57ea0016d486e`；270,814,880 字节。
- 全部测试目录在 `/private/tmp/rivo-projection-probe/`。只使用合成的两轮问答，没有复制或打开用户现有聊天库、rollout、配置或凭证。
- 子进程环境使用显式白名单；`HOME`、`CODEX_HOME`、`CODEX_SQLITE_HOME`、`TMPDIR` 均指向各自测试目录。临时配置不设置外部 `sqlite_home`，关闭 shell snapshot，无 MCP 配置。
- 不调用 `turn/start`，不进行模型推理、不加载凭证、不启动原生桌面 UI。所有实验 app-server 子进程均已退出，未重启或操作现有运行时。
- 通过原生启动建立元数据 schema，用合成 legacy rollout 经同一二进制 `migrate-rollouts --apply --thread ... --json` 生成官方 paginated 基线；每个实验使用停止写入后的独立副本，并改写副本元数据路径。
- 本机另装有包版本 `0.159.2` 的 CLI，本次未对其重跑矩阵，结论不外推至该版本或其他节点。

## 观察结果

基线为 2 个 completed turn、4 个 user/assistant item；比较完整分页返回的 turn/item 身份、正文和状态，而不仅比较行数。

| 隔离副本操作 | 普通 `thread/turns/list` | `thread/resume` 后再次读取 |
| --- | --- | --- |
| 保留完整投影 | 2 轮完整历史 | 与基线相同 |
| 删除目标会话消息/轮次记录，保留投影进度标记 | 返回 0 轮，无错误 | 仍为 0 轮 |
| 删除目标会话四张表记录，含投影进度标记 | 返回 0 轮，无错误；未重建 | 恢复 2 轮，与基线完整数据相同 |
| 删除整个测试投影库及其 WAL/SHM | 创建空库，返回 0 轮；未重建 | 恢复该会话 2 轮，与基线相同 |
| 原生 `thread/archive` | 仍可读 2 轮 | 未额外恢复；归档后四张投影表记录数不变 |
| 原生 fork 后删除父、子全部投影 | 子会话返回 0 轮 | 仅恢复子会话后仍为 0 轮 |
| 上一场景再恢复父会话 | 子会话恢复 2 轮，与分叉基线相同 | 已观察父段依赖 |
| 保留父段投影、只删除该分叉子会话投影 | 仍可读继承的 2 轮 | 此子会话没有新增本地轮次，不能外推至有新增轮次的情况 |

普通读取前后合成 rollout 字节摘要不变。简单会话 resume 保留原始字节前缀，但追加 `thread_settings_applied` 事件；所以显式恢复不是纯读取操作，不能把它无条件塞进所有历史查看请求。

分叉的原生 session metadata 带有 `history_base.thread_id`、`end_ordinal_exclusive`、`end_byte_offset`。父段 projection 被淘汰会影响依赖它的子会话，不能仅按独立 thread 最后访问时间清理。

## 磁盘回收实验

在另一份测试投影库中加入约 8 MiB 合成填充，未对真实聊天数据执行 SQL：

| 阶段 | 主库字节数 | freelist 页数 |
| --- | ---: | ---: |
| 填充后并 checkpoint | 8,503,296 | 0 |
| 删除投影记录并 checkpoint | 8,503,296 | 2,052 |
| 离线 VACUUM 并 checkpoint | 90,112 | 0 |

该样本 `auto_vacuum=2`（incremental）。实验只证明删除行不会立即归还文件空间，离线 VACUUM 可回收；未验证运行中的共享库执行 VACUUM、增量回收、WAL 峰值或硬配额的安全性。即使回收成功，后续恢复会话仍会重新生成投影，单次清理不构成持续容量上限。

## 官方配置边界

- [配置参考](https://learn.chatgpt.com/docs/config-file/config-reference)：`history.persistence` / `history.max_bytes` 管理 `history.jsonl`，不是 `thread_history_1.sqlite` 的容量配额。
- [环境变量](https://learn.chatgpt.com/docs/config-file/environment-variables)：`sqlite_home` / `CODEX_SQLITE_HOME` 改变 SQLite 存储位置，不是淘汰策略；配置项优先于环境变量。
- [App Server](https://learn.chatgpt.com/docs/app-server)：分页读取无需 resume；archive 移动日志，delete 删除历史；unsubscribe / unload 管理内存线程。未找到公开的 projection eviction/rebuild API。

## 证据与限制

REA 对停止写入的合成投影库及其 WAL 做了 schema 检查，Evidence ID：`ev_34c41b4076b765da0e56fa711204dda029c60884ea910b4e6ba963cf376f8ec2`。确认四张投影表、分页索引，以及删除 projection state 时清理 realtime items 的触发器。REA 的 Rust 元数据检查因二进制超过其 256 MiB 上限而未执行；未做反编译，不将字符串或猜测当成运行时结论。

本机临时证据目录 `/private/tmp/rivo-projection-probe/` 保留：

- `probe.py`、`seed_fixture.py`、`matrix.py`、`edges.py`、`fork_followup.py`、`space.py`：隔离 RPC harness 和实验步骤；脚本有阶段依赖，现有目录不可直接重复播种。
- `rpc.jsonl`、`migration.json`：原生请求/响应及迁移结果，均为合成数据。
- `matrix-results.json`、`edges-results.json`、`space-results.json`、`binary-identity.json`、`rea-sqlite-evidence.json`：观测结果和身份记录。

没有测试工具输出、图片、realtime item 的完整恢复，多级分叉、冻结父段快照、压缩 rollout、活跃 writer 并发、进程崩溃中断恢复或多个 Codex 版本交替写入。未做真机/模拟器验证，不把这些范围表述为通过。

本次已回答：简单会话能否重建（能，需显式恢复并清除进度状态）、普通读取是否透明重建（本版本实验中不能）、分叉是否独立（不是）、归档是否释放投影（样本中没有）、删除行是否立即缩小文件（没有）。安全的全局容量淘汰方案尚未建立；若继续研发，需要依赖图、真实 writer 协调、读取前完整重建验证及受控空间回收，不能直接在现有库上部署清理脚本。
