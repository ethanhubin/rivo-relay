# 选择具体额度重置机会验证

日期：2026-11-12。范围：选择要消耗的重置机会、原机会 ID 的持久化与恢复，以及设置页的周额度和重置入口。

## 回归与构建

- 服务端 `test_usage_reset.py` 18 项、`test_submissions.py` 18 项通过，覆盖指定机会透传、重启后复用机会 ID、替换配对恢复及旧记录兼容。匹配本机节点版本的候选快照另通过同一组额度专项 18 项，不重复计入 36 项。
- Android `UsageResetTest` 6 项、`CatalogUsageTest` 5 项通过，覆盖可选机会过滤、元数据映射及所选 ID 的发送；后续界面调整未改动对应数据与提交逻辑，复用既有结果。
- 最终 Debug APK 构建、签名校验、`git diff --check` 通过。验证包使用临时 Gradle 配置保留原包名 `dev.rivo.relay`，版本为 `0.2.121-quota-test` / 133；临时版本、签名配置及 APK 未提交 Git。签名 SHA-256 与 OPPO 原安装包一致：`3f31e18aa34d6144102e35b4640ff94e5fdfa3c92b074df474d02583934e1800`。
- 未运行 lint、全量测试或正式发布流程。

## 设备范围

早期交互方案在独立模拟器使用演示数据通过 2 项 UI 测试：指定机会的选择、取消与确认，以及无明细禁用和刷新使选择失效。截图在默认及软件渲染下均为黑屏，未以模拟器截图作为视觉验收依据；最终布局未重跑模拟器。

OPPO PKB110 使用同签名包覆盖原应用，保留原配对和数据。真实账户的两次可用机会均已显示，并实际选中指定的一次；该阶段截图位于 `workspace/artifacts/selected-quota-reset/oppo-selected-credit.png`。后续根据用户反馈移除编号和获得时间、支持取消勾选、合并周额度与重置入口、在入口右侧显示次数、移除重置弹窗刷新按钮。每次完成适用构建后覆盖安装，用户最终确认“可以了”。未点击真实兑换的最终确认，不将该验收表述为真实额度恢复成功。

## 仅当前本机节点的验证部署

用户明确选择“只更新当前本机节点验证”。在 `lorinmacbook-pro` 节点版本 134 的服务端隔离快照上应用本次额度选择补丁；候选依赖版本与原节点一致，未修改原快照，未发布到 VPS 更新中心或其他节点。

通过现有 `/internal/update-lease` 和 `/internal/update-stop` 完成空闲检查与 Relay 停止；等待节点维护锁释放后，备份数据库与更新状态并切换候选。健康检查返回 `status=ok`、`releaseCode=134`、`nativeConnected=true`，中转就绪标记正常。未操作 Codex Desktop 原生运行时。

本机候选位于 `workspace/artifacts/selected-quota-reset/node-candidate/`，其中 `state-before.json`、`relay-before.sqlite3` 保留切换前状态；原活动版本 `4b220056b763a37b40ac8a0483ce07b2b5ba49ddaab7b20858aaf83aaa046f26` 保留为回滚版本。这些运行数据与临时部署产物不提交 Git。

验证时本机磁盘可用约 439 GiB，未进行磁盘清理。
