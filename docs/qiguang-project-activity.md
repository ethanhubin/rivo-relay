# QiGuang 活动项目扫光对照

来源为 QiGuang 当前工作区 `qiguang/app/app/src/main/java/com/example/qiguang/ui/main/HomeScreen.kt` 的 `CodexProjectRow` 与 `CodexProjectRunningBorder`（第 518–653 行）。该文件有原有未提交修改；读取时 SHA-256 为 `5f2dccbf4d75566cd3f8291c698347578244d84cbc6054c88f4ee6739bb95007`。未修改来源仓库，逐文件记录见 [migration-sources.json](../client/android/migration-sources.json)。

Rivo 在 `ProjectsScreen.kt` 采用相同条件和绘制规则：

- `ProjectSummary.outstandingWorkCount > 0` 时，才组合卡片上方的扫光绘制层。默认值为 0，完成后移除动画。
- 0–1 相位在 1800 ms 内线性循环；2 dp 描边沿卡片圆角边缘移动。
- 扫光采用 32 段渐变采样，宽度系数 0.16，主题 `primary` 与 `tertiary` 混色，跟随 Moonlight 亮暗主题。
- 绘制层没有点击处理，不改变卡片高度、布局、导航或既有点击目标。

活动计数由节点状态投影提供，UI 不根据会话数量、请求加载状态或历史命令文本推断活动；客户端数据接入由 Catalog 层负责。`ProjectCatalog.list` 复用已有全局 `thread/list` 分页，按原生项目归属统计 `status.type == active` 的线程；等待审批或用户输入仍为 active，idle/notLoaded/systemError 不计入。没有新增逐项目 RPC 或历史读取。QiGuang 的计数还包含其队列和派发状态，Rivo 没有这些队列，因此不迁入该部分语义。

首页在 `Lifecycle.STARTED` 期间立即读取，再在每次完成后间隔 5 秒更新；离开首页或进入后台后不再轮询。手动刷新、启动读取和首页观察复用同一个进行中的 GET。已发出的共享读取可以正常结束，不因一个观察者退出而取消其他调用者的读取。

新增隔离的 `ProjectActivityUiTest`，使用受控 Compose 时钟验证活动边框移动、空闲行静止、活动结束后移除及项目仍可点击，并输出两帧截图供视检。该 fixture 不连接节点、不产生原生执行。构建和设备运行结果由统一验收记录汇总，未在本次迁移编辑中运行。

真实节点另在已有隔离项目完成一次最小回复任务：开始后观察到活动计数非零，收到 `RIVO_PROJECT_ACTIVITY_OK` 后恢复为 0，记录在 `workspace/artifacts/project-activity-native.json`。只读全项目检查记录在 `project-activity-read-only.json`，调用仅包含初始化、诊断、项目列表和线程列表分页。
