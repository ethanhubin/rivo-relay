# Codex 实时问答适配（2026-11-10）

## 源码依据

只读分析本机 `/Applications/ChatGPT.app/Contents/Resources/app.asar`，SHA-256 `a77588b2dcfa9b394d722dd8c2c8b13d3a09bacd49c839cc715245742dba6048`。REA 分析记录 `ev_2a167d9f3835f0ddbf9fbd495e1e543621823be37e5a6f3fe5e9f29b8074ed64`，选定 `webview/assets/async/485975.c7ee0888fd.js`；其余相关内容通过同一包的静态文本读取核对。没有修改或重启安装的应用。

- `webview/assets/async/182735.7f13786b92.js` 的 `dNnb`、`uHDr` 模块：异步问题为 `title/options`，身份为 `JSON.stringify(["request_user_input_async", itemId, index])`，答案为 `questionItemId/question/answer` 的 JSON 数组，包在 `send_user_message_question_reply` 标记中。
- `.vite/build/early-bootstrap-815-e030c49be4b9c405.js` 的 `wI/wA`：问题来自 `agentMessage.questions`；只有原生用户消息或已接受的 steering 消息算作回答。
- 旧版 `item/tool/requestUserInput` 仍使用连接绑定的 RPC 回答，不能用异步答案消息替代。

静态分析有范围限制，未推断全部应用行为；REA 记录的若干 native companion 完整性矛盾未用于本次实现，也未执行这些文件。

## 实现边界

Android 新增异步问题卡片，支持多题、选项和自由输入；显式提交后才发送，保留普通输入草稿。已接受答案不重复展示，失效、断线和不可达状态禁止提交。

节点提供独立回答能力与接口，验证原生问题身份和来源回合。桌面持有会话时，只有目标已加载在可达原生连接中才发送答案；不会为回答获取写锁、创建 writer、结束桌面进程或自动接管。运行中直接 steer；空闲时由同一 owner 开始后续回合。连接绑定的旧版问题不再被普通 writer 操作权限误锁定，审批仍维持原权限限制。

## 验证

- 服务端相关回归及回答 HTTP 接口合计 100 项通过（99 项通过后新增/调整恢复用例，相关 11 项复测通过）。
- Android 问答投影及回显单元测试 16 项通过，Debug 编译通过。
- 本机真实原生通道：两个独立客户端连接同一 daemon，在 ephemeral 临时会话分别验证跨客户端 `turn/steer`、空闲 `turn/start` 接受成功；Relay 前后均不拥有该 writer。测试回合已停止并取消订阅，没有向用户当前会话发送测试答案。
- Android 16 模拟器 3 项 UI 测试通过：桌面持权会话多题选项/自由输入提交、普通草稿保留及普通发送禁用；不可达/过期状态禁止回答；离线草稿退出重进并由新存储实例恢复。
- 未进行真机端到端配对测试、未运行 lint、未发布。

## 真实 Desktop → APP → Desktop 端到端验收（12:03–12:05）

用户在真实桌面创建「询问验收标记」会话 `01a1c39d-84ab-7b62-9742-01fe6027022b`，桌面连接主机为 `remote-ssh-discovered:tailscale-ethan-macmini`。Computer Use 明确禁止操作 Codex 桌面，因此桌面发起和最终可见状态由用户操作/确认；没有用注入、脚本或 API 创建会话来替代真实桌面发起。

使用本分支代码启动回环测试节点 `127.0.0.1:18876`，独立临时数据库与配对。Android 16 模拟器安装 Debug APK，`DesktopQuestionsLiveTest` 启动真实 `MainActivity`，通过正式配对、会话导航、问题输入和提交按钮完成回答；未替换界面回调、快照、网络或模型。

1. 初始快照 `writerOwner=external`、`readOnly=true`、`control.canOperate=false`、`canAnswerQuestions=true`。
2. APP 回答 `RIVO_E2E_ALPHA`，原桌面会话实际复述该标记，并通过原生异步工具继续提出第二个问题。对应回合 `01a1c3a0-0d4f-7551-9214-b7d084b5c941`。
3. 重建 APP Activity，再回答 `RIVO_E2E_BETA_20261110`，桌面模型逐字复述并正常结束。对应回合 `01a1c3a0-3ab7-7452-856b-e519cdd52bfa`。
4. 再次重建 Activity：历史保留两条不同问题的答案且各出现一次，没有残留待答卡片；前后四个快照均保持 external/readOnly，未接管。两条提交记录均为 `thread.answers/accepted`。
5. 用户确认实际桌面两次回复均可见、问题已处理。真实 APP 仪器测试 `OK (1 test)`，41.206 秒。
6. 验收结束撤销两个临时配对并停止本轮测试 Relay；桌面测试会话仍加载在原 daemon 中。未更新生产服务、未重启 Codex、未发布。

证据保存在忽略目录 `workspace/artifacts/desktop-question-e2e-20261110/`：四组原始快照与模拟器截图、两条提交回执、仪器测试/构建/服务日志及清理核验。最终截图可直接看到“外部 Codex 占用”、两次模型复述和禁用的普通发送按钮。

结论范围：本次确认真实桌面连接此 Mac mini 节点 daemon 时，APP 可以不取得 writer 回答问题并可靠完成这两轮交互。两次回答均实际开启后续回合；虽然中间观察到 active 状态，本轮不将此算作 `turn/steer` 的真实桌面执行验收。Activity 重建验证了重新读取状态，不等于网络中断、服务崩溃或长期稳定性测试。独立 stdio 桌面进程、其他节点与真机仍未覆盖；不能据此取消独立 writer/释放/接管机制，也不能宣称普通任意消息发送已开放。


## 异常恢复边界审查

- 未回答、离线、退出重进：异步回答使用独立草稿键，按节点/会话/来源回合隔离；离线可编辑，旧快照、失联、其他请求待处理时不可提交。进入后加载草稿，再依新快照校验问题是否仍有效。
- APP 重开：已落盘草稿可由新存储实例恢复；发请求之前必须完成草稿落盘。沿用现有 120ms 合并写入，进程被强杀时最后尚未落盘的输入仍可能丢失，不承诺每个按键即时持久化。
- 提交后断线/节点退出：服务端发送前事务性登记问题身份。pending/unknown/accepted 跨数据库重开保留；同一或不同 submissionId 都不会绕过问题级防重。明确未发送的失败解除登记，允许用户重试。
- 原生已接受但历史尚未更新：accepted 状态隐藏已答卡片；unknown/pending 显示待核对且不自动重放。若历史长期无法确认，只能在桌面核对；本次没有提供强制解除防重的按钮。
- 问题已答、回合改变/中止、连接关闭：重新校验并阻止旧回答；保持普通消息发送和 writer 管理原规则。原生 idle 的 turn/start 不支持 expectedTurnId 原子条件，桌面并发开启新轮次的最后瞬间仍受原生行为约束，不能承诺跨客户端事务隔离。
- 新增回归覆盖 SQLite 文件关闭重开、批量登记回滚、超时/成功后的旧历史重复提交、明确失败重试、离线草稿退出重进、unknown/accepted 状态刷新。旧版连接绑定 RPC 问题不适用本次异步草稿持久化。
- 真实桌面两轮验收发生在本轮恢复加固前；本轮通过回归与模拟器验证恢复加固，未再次执行真实桌面测试，未模拟真机断网/强杀或原生运行时崩溃。
