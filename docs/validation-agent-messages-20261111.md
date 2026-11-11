# 子任务与 agents 消息显示

## 源码依据

只读核对 `/Applications/ChatGPT.app/Contents/Resources/app.asar`，SHA-256 `a77588b2dcfa9b394d722dd8c2c8b13d3a09bacd49c839cc715245742dba6048`。
REA 记录 `ev_8b59eb430dcfedb01a0c39976321c21654dbdfe30a3cc1972ad1bd515e277bbd`，选定 `webview/assets/async/27319.709dd1bc30.js`；下述另外两个模块通过同包静态文本读取核对。

- `182735.7f13786b92.js`：消息投影区分 `multi-agent-action` 与 `subagent-activity`；`wait` 不进入可见消息列表。`subAgentActivity` 的 started/interacted/interrupted/completed 对应开始、发送消息、中断、完成。
- `225284.63835849b6.js` 的 `ixMX` 及相关渲染：代理活动显示名称、状态；发送给未命名父代理的消息有单独文案；支持打开代理会话。代理操作区分创建、发送、恢复、关闭及其进行中/完成/失败状态。
- `27319.709dd1bc30.js`：代理信息来自 receiverThreads、agentsStates、prompt、model 和 subAgentActivity。工具调用状态和代理执行状态不是同一个字段；wait/sendInput 不直接覆盖代理操作语义。

上述为有范围限制的静态证据，不代表运行时端到端验证。REA 报告未选模块、动态关系及语义保留上限导致的覆盖限制；包内部分 native companion 完整性矛盾不涉及本次选定 JS，也未执行这些文件。

## App 适配

代理事件独立显示，不混入普通命令组。展开为任务/消息、模型、每个代理的名称、执行状态和结果；支持查看子会话，返回键逐级回到父会话。原始事件仍保留。

隐藏 wait 行，但同一回合的 wait 状态/结果会投影到对应代理卡片。未知状态不当作成功；父回合终止后，没有子任务结束证据的 running/pendingInit 显示待核验。仅在已有历史中投影，不额外启动子会话轮询或获取 writer。

范围限于消息列表及子会话导航；未移植桌面侧边栏的全局代理面板、打断/恢复控制和跨回合实时代理状态订阅。未提供名称的原生记录使用短 ID 回退。

## 验证

Android 相关 JVM 测试 32 项通过（NativeAgentsTest、NativeActivityProjectionTest、NativeMessageFormatsTest），Debug Kotlin 编译通过，git diff --check 通过。首轮编译未包含编译启动后补充的中断状态修正，相关用例失败；最终代码重新编译并复测全部通过。未运行 lint，未进行真机或模拟器验证，未发布。审查补充修复：代理终态变化且新事件无结果文本时清除旧状态结果；同一终态的重复事件仍保留结果。两项新增回归及相关测试全部通过。
