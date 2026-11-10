# 脚本导航

下表脚本入口均相对于 `scripts/` 目录。运行前阅读对应指南及脚本参数；安装、重启和发布脚本会修改目标环境，不作为普通开发检查执行。

| 用途 | 入口 | 使用说明 |
| --- | --- | --- |
| 日常启动 | `run-server.sh`、`start-node.sh`、`start-node.ps1` | [节点部署](../docs/node-deployment.md) |
| 安装节点 | `install-managed-node.py`、`install-windows-node.ps1` | [节点部署](../docs/node-deployment.md) |
| Windows 后台运行 | `windows-node-background.py` | [节点部署](../docs/node-deployment.md) |
| 网关安装与启动 | `install-gateway-service.sh`、`start-gateway.sh` | [VPS 中转](../docs/vps-gateway.md) |
| 发布编排 | `release.py`、`release_tools/` | [更新与发布](../docs/node-updates.md) |
| 更新文件发布 | `publish-update.py`、`publish-gateway-update.py` | [统一更新中心](../docs/central-updates.md) |
| 节点状态与重启 | `node-update-status.py`、`restart-service.py` | [更新与发布](../docs/node-updates.md) |
| Codex 重新授权 | `reauthorize-codex.py` | 先查看脚本参数，按节点授权场景使用 |
| 服务验证 | `verify-service.py` | [验证记录](../docs/validation.md) |
| Windows 验证 | `verify-windows-node.py`、`verify-windows-full-access.py` | [历史验证归档说明](../docs/validation.md) |
| Android 模拟器验证 | `verify-emulator.sh` | 会清除测试 App 本地数据，先查看 `--help` |
| 模拟器摄像头工具 | `qr-emulator-camera.py`、`emulator-camera-pose.py` | 用于模拟器二维码及摄像头验证 |

按变更选择受影响的验证入口，不默认执行全量测试或 lint。发布、远程操作与 Git 授权要求以根目录 [AGENTS.md](../AGENTS.md) 为准。
