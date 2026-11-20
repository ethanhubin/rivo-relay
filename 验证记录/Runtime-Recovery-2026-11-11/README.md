# 第二批会话、交互与恢复验证

日期：2026-11-11。开发验证时分支为 `codex/dsh-runtime-integration`，延续第一批实现。随后将本次会话改动迁入基于 `origin/main`（`5a66ba9`）的新工作树，统一提交到 `codex/multi-runtime-adapters`；未推送或发布。用户确认本轮使用模拟器，不做物理手机验证。

## 实现边界

- Codex 和 DSH 在同一次控制读取中返回 `sessionState`，分开运行时代际、控制版本、历史同步和执行状态。消息历史继续沿用现有兼容投影。
- 新交互回应带 sessionId/runtimeGeneration/interactionId/revision；适配器核实当前 pending 身份及选项后才回应原生端。回执完整绑定身份，未知结果不重发。
- `session/invalidated` 让 APP 失效旧权限与在途读取；重启缓存不保存待审批请求，保留配对和未核对提交。UI 布局继续复用。
- 公共聊天路由经 `SessionService`；可选工作树、队列、媒体和原生 token 增量仍有旧接口依赖。Claude Code 未接入，此批只为其保留核心与可选交互边界。

## 定向检查

- Android 定向单测共 98 项通过：RuntimeSessionTest 5、SessionControllerTest 26、NodeSessionSyncTest 10、SubmissionCoordinatorTest 19、NativeFormsTest 9、SessionControlTest 14、ConnectionRecoveryTest 15。新增坏协议回归确认旧异步问题、队列与残留能力不能绕过禁止操作；有效新旧节点仍保持兼容。自动恢复回归先复现旧行为失败，修复后通过，包括网络恢复恰好遇到最后一次旧探测失败的竞态。
- HTTP 82 项通过：test_api 64、test_runtime_capabilities 10、test_runtime_sessions 8。新增 HTTP 契约使用没有 connection/writer/turn 的第三后端，覆盖跨会话/代际/版本拒绝、已接受重放、另一端已回答，以及无确认保持 unknown。
- 适配器与提交首轮回归 214 项通过：test_runtime_state_adapters 13、test_codex 45、test_activity 53、test_client_projection 8、test_queue 11、test_dsh 20、test_receipts 9、test_submissions 18、test_submission_recovery 37。
- Codex 并发锁及最终发送代际修复后，受影响回归包含 test_runtime_state_adapters 16、OwnedRoutingTests 25、test_desktop_bridge 20、test_codex 45，以及上述 HTTP 82。初次运行因沙箱不允许 ps 跳过的 `test_host_probe_executes_on_isolated_native_process_home` 已在允许检查测试进程的环境中单独补跑通过；它只检查临时隔离测试进程，不操作用户运行时。合并去重后服务端共 344 项通过。
- 最终 debug / androidTest APK 构建成功并保留数据安装，见 [APK 摘要](apk-install.json)。未运行 lint 或全量测试，按相关代码变化增量复测。

## 运行环境与验收场景

沿用 `emulator-5554` 和既有 APP 配对。独立 Codex Relay 端口 18786，DSH Relay 端口 18785；主节点 18765 与原生运行时不重启。DSH 再次核对安装版本和官方更新源均为 `0.2.2-rc.1`，见 [版本与连接核验](dsh-connectivity.json)。

两后端各准备独立 network/approval/restart/other 会话。执行中断网用已获单次审批的 `sleep 55; printf …` 命令；审批场景在离线时检查按钮禁用，恢复相同交互后回答；进程恢复通过外部 force-stop 分两段验证 PID/进程起始时间变化，不能以 Activity recreate 代替。每次输入均按 submissionId 核对原生 clientId 恰好一次，恢复消息身份不重复。

| 最终模拟器场景 | Codex | DSH |
| --- | --- | --- |
| 执行中断网、自动恢复和结果读回 | [通过](codex-network-c2ab608f/result.json) | [通过](dsh-network-06934da4/result.json) |
| 审批中断网、Activity 重建、同一请求单次批准 | [通过](codex-approval-c1e232bf/result.json) | [通过](dsh-approval-705e4a1b/result.json) |
| 强制停止仍存活的 APP，再启动恢复审批 | [通过](codex-restart-e2dba5f2/result.json) | [通过](dsh-restart-f13edbb8/result.json) |
| 另一控制端先回答、旧请求失效 | [通过](codex-other-faeeeb2c/result.json) | [通过](dsh-other-585be7bf/result.json) |

八个最终场景均为 idle、无 pending 审批、原生输入一次、文件结果及助手标记一致；审批场景另验证旧身份新 submissionId 被拒绝。强制停止证据包含非空旧 PID、停止后空 PID、启动后的不同 PID，见 [Codex 停止记录](codex-restart-e2dba5f2/host-process-stop.json) 与 [DSH 停止记录](dsh-restart-f13edbb8/host-process-stop.json)。最终界面见 [Codex](codex-restart-e2dba5f2/completed.png)、[DSH](dsh-restart-f13edbb8/completed.png)。

[机器汇总](e2e-summary.json) 保存最终八个场景、稳定快照、交互身份一致性、APK 摘要及额外证据分类。DSH 首张审批完成截图恰逢后续刷新，原始瞬态证据在本机保留；只读重开同会话补取稳定截图，没有再提交输入。

DSH 首轮 `dsh-restart-28e6ba89` 的第一段 instrumentation 退出后 APP 进程已经结束，因此该记录只证明进程结束后重建与审批恢复，不能算作强制停止活进程。编排已改为先通过通知入口重新启动同一 pending 会话，确认 PID 非空、原生审批仍待处理，再 force-stop 并核对 PID 消失；最终 Codex 场景和额外 DSH 专用场景使用这一流程，原首轮证据在本机保留。

首个 Codex 审批断网场景在网络恢复后的 fresh 等待超时（192.491 秒），尚未进行 Activity recreate 或回答审批。设备默认 Wi-Fi 已 VALIDATED，设备内 TCP 请求 `/health` 返回 200，服务端仍显示一个 pending 请求和一条输入。定位到 ConnectionRecovery 离线探测耗尽后停在 DISCONNECTED，而网络恢复只 resetTransport、不重新探测；失败执行已精确取消且未产生结果文件，见 [失败与取消记录](codex-approval-8b39aaf0/failed-and-cancelled.json)。修复后的新独立任务通过上表 Codex 审批断网测试，未重发旧输入。

并发代码复核还发现 Codex 审批锁与 writer 释放锁的获取顺序相反。本轮修复为锁内声明答复占用、锁外进行原生 I/O，并在实际传输边界核验代际；明确拒绝的恢复也必须确认请求未被替换或失效。相关并发回归单独记录。

DSH 另一端回答经原生 bridge 的 respond，Codex 经独立 Relay HTTP 客户端，二者均不冒称已验证桌面 UI 双端审批。DSH bridge 接管的请求不会同时转交桌面审批链，原生桌面竞争仍是单独的产品边界。

工作区 `workspace/runtime-recovery-validation` 存放忽略的编排脚本，其中包含私有审批身份的 case JSON 以 0600 保存；归档只收录不含配对令牌、可执行审批 key 或 choiceId 的结果。模拟器 JSON 仅记录交互身份摘要。

本轮保留此前的扫码配对，不重跑摄像头扫码；未做物理手机、公网 VPS、Claude Code 或所有可选功能验收。主节点未替换，原生运行时未重启，未发布。

详见 [API](../../docs/API.md) 与 [架构](../../docs/multi-runtime-architecture.md)。

## 工作树归档

仓库保留最终验证摘要、8 个场景结果、强制停止记录和代表截图。83 份原始日志、阶段快照和临时截图只在新工作树忽略的 `workspace/verification-archives/Runtime-Recovery-2026-11-11` 本机保留；源工作区迁移备份也保留完整副本。上面的模拟器结果产生于迁移前 APK，迁移后的主线合并另做定向回归，不冒称重新完成全部端到端验证。

迁移后针对主线重叠重新验证：`test_handoff` 47 项、`test_runtime_capabilities` 11 项，以及 Android `ConnectionRecoveryTest` 18 项、`SessionControlTest` 14 项通过；Android 主代码与 androidTest Kotlin 编译通过。合并时去掉重复的网络观察器，沿用主线立即取消旧探测的恢复机制，并修复新能力门禁遮挡“重新接管”预览入口的问题。首次 Android 检查因新工作树未配置 SDK 未启动测试；补充本机忽略的 `local.properties` 后上述检查全部通过。未重复模拟器全流程、未跑 lint 或全量测试。
