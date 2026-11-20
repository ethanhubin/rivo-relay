# App 与节点兼容协议验证（2026-11-12）

## 范围

在 `codex/api-compatibility-docs` 独立工作区实现并验证；起点为 `origin/main` 的 `ff851f6`。开发验证期间没有改动线上节点、运行发布流程或递增软件版本；提交 PR 与发布独立。

- 节点在鉴权发现接口声明 API 主版本与可选能力；原有 capabilities 保持兼容。
- App 检查节点身份和共同 API，阻止已确认不兼容的业务请求。语音、目标、附件和待办按登记能力处理；历史缺失声明保持未知及既有连接行为。
- App 更新按所有已有配对身份的节点评估，包含失效授权；安装前重新检查，多节点部分兼容需要针对具体 APK/结果确认。
- App 编译常量与 APK 资产共用声明源，发布器从实际 APK 提取，VPS 清单保留同一声明。

## 完成的检查

### Android

`testDebugUnitTest` 定向运行以下 9 个测试类，共 40 项，通过；`assembleDebug` 通过。

- `ApiCompatibilityTest`：严格解析、共同版本、未知字段、节点身份与更新决策矩阵。
- `RelayCompatibilityTest`：不兼容时未发送写请求、升级后恢复、缺失声明保留旧连接、可选功能门控、重连刷新、非法能力字段不能绕过已知协议不兼容。
- `UpdateCompatibilityCheckerTest`：离线、授权失效、超时、调用方取消，以及检查结果变化。
- `BoundNodesCompatibilityTest`：失效授权仍是绑定节点，地址书签不计为绑定。
- `NodeAppUpdatesTest`：候选声明读取、旧/非法声明未知及既有 HTTPS、下载和完整性回归。
- `UpdateViewModelTest`：更新提示、单次检查及安装重新检查、确认失效规则。
- `SpeechRepositoryTest`、`NativeHistoryTest`、`NativeGoalTest`：复用语音发现、历史和目标解析相关回归。

已直接检查构建 APK 中的 `assets/api-compatibility.json` 与生成的 `BuildConfig.API_VERSIONS` 一致，均声明 API v1。APK 仅为本地开发验证产物，不代表已发布。

### 服务端与发布工具

分批运行并复用同一代码上的通过结果，共 26 项：

- `test_api_compatibility`：6 项，验证鉴权发现、旧调用方式的基础读取及文本提交去重、声明严格校验、从 APK 到本地清单/VPS 包的声明传递与不一致拒绝、历史清单读取。
- `test_update_publish`：2 项，验证原子发布、防降级/覆盖及并发保护。
- `test_central_updates`：13 项，验证统一更新清单及既有安装/回滚行为；HTTP 返回保留 App 兼容声明。
- `test_api.ApiTests` 定向 5 项：公共更新文件隔离、原生审批响应去重、传输不确定时不重放、SSE 撤销复核、历史游标/分页限制。

HTTP 验证使用本机临时服务、临时数据库及受控原生替身；没有连接生产 VPS 或用户 Codex 运行时。

### 文档与差异

检查协议、能力登记表、更新规则与实现一致，文档相对链接有效，`git diff --check` 通过。

## 验证边界

未执行全量测试、lint、真机或模拟器 UI/系统安装验证。没有安装任意历史发布 APK，也没有给历史节点建立兼容白名单；跨版本行为用受控旧响应/旧调用方式验证，不能表述为所有历史版本实测通过。已有旧 App 不具备本次新增的更新拦截；缺少声明的旧节点需先升级后，才能被新 App 的内置更新入口确认为兼容。
