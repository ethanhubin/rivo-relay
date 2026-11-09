# VPS 统一更新开发验证（2026-11-09）

## VPS 磁盘实测

先读取设备管理清单及网络设备详情，使用已登记的 `/Users/ethan/Documents/QiGuangBot.pem` 以 root 登录 `134.175.156.219`，未修改远端服务或文件。

- 主机名：`VM-0-7-opencloudos`。
- 根盘 `/dev/vda1`：40G，总已用 4.5G，可用 36G，使用率 12%。更新目录与根盘共用此文件系统。
- `/opt/rivo-relay`：63M，其中虚拟环境 59M，部署快照约 4M。
- `/var/lib/rivo-gateway`：40K。
- `/var/log`：634M；journal 约 484M。
- 当前空间充足，没有删除系统日志、其他服务目录或生产更新文件。实时用量不写入设备身份清单；项目 AGENTS.md 已记录设备管理入口和正确 SSH 密钥路径。

## 本地验证

- Android `NodeAppUpdatesTest` 6 项、`NodeTransportTest` 10 项通过；相关 Kotlin/Java 编译通过。包含固定证书 VPS 源站请求、无需节点探测/配对令牌、错误证书拒绝、APK 校验与续传。未运行 lint，未生成本次发布 APK。
- 服务端新增统一更新测试 13 项通过：原子发布及幂等、防降级/同版本替换、损坏文件/低空间/容量限额、保留 3 版及 24 小时缓冲、ZIP 路径与符号链接拒绝、节点全离线时网关下载及 Range、空闲准入、提交状态与回滚、活动 writer/目标识别。
- 其中进程演练启动真实本地 HTTP 子进程，使用临时 SQLite。候选版本改写测试数据库后故意退出，确认旧进程和数据库恢复；使用模拟原生健康状态，不连接用户 Codex，不等同于生产节点实机升级验收。
- 发布 CLI 新增 VPS 发布失败/重试测试通过：VPS 验收失败时不进入 complete，同一计划重试成功后才完成。
- 受影响的 owned-writer、runtime/media lifecycle、更新 HTTP 准入等 41 项组合测试通过（与上方新增测试有重叠，不累加计数）。初轮 API、网关、发布 CLI 相关用例成功结果已复用。
- 初轮调用缺少 `server/tests` 导入路径，造成 3 项导入错误；修正路径后重跑涉及用例。队列套件中 `PinAndMigrationTests.test_v1_migration_keeps_credentials_and_existing_rows` 仍失败，已在独立导出的未修改 HEAD 中复现同一数据库兼容性错误；本次没有修改数据库迁移代码，也未将该套件表述为全通过。
- Python 编译检查、命令行帮助检查和 `git diff --check` 通过。日志保存在 `workspace/artifacts/central-updates/`，不提交 Git。

## 未完成的生产验证范围

本轮为实现与开发验证，未递增发布版本、未提交/推送 Git、未创建 Release、未更新 VPS 代码或重启任何生产服务，未给其他电脑安装代理。未做 Android 真机/模拟器安装，也未执行 Windows/macOS/Linux 后台安装器的生产注册、重启自启动或真实 Codex 会话升级验收。

正式启用需按 `central-updates.md` 完成一次网关与节点迁移，并发布带新更新源逻辑的 APK；当前线上设备不会仅因为源码变更而开始自动更新。
