# Rivo Relay

当前测试包：[下载 Android 0.1.10 APK](https://github.com/ethanhubin/rivo-relay/releases/download/v0.1.10/rivo-relay-0.1.10-debug.apk)（[Release 页面](https://github.com/ethanhubin/rivo-relay/releases/tag/v0.1.10)，debug 测试版）。单文件上传和下载支持 1 GiB（1024 MiB），手机与节点全程分块处理；节点已同步更新，保留登录。验证见 [记录](docs/validation.md)。

Rivo Relay 的目标是让 Android 成为 Codex Desktop 的完整延续，围绕 Codex 会话与待办工作。App 复用原生登录、项目、会话和运行设置，不增加项目白名单、设备权限等级或额外审批；原生要求的提问与审批仍由用户处理。

当前已有 Python 单服务和 Kotlin/Compose App，目录为 `server/` 与 `client/android/`。登录/项目与待办阶段已验收通过：真实扫码、手动授权、文本/图片会话、节点文件预览、待办完整流程、离线/撤销与重启恢复均已验证；同一既有 Codex daemon 上的双客户端原生操作也已通过。**Desktop UI 双向验收仍需用户协助；当前 Codex 0.159.x 的复杂 OpenAI/MCP 表单、原生身份验证挑战和音频采集 UI 尚有差异，不能宣称完整延续已全部验收。** 最新状态见[开发计划](docs/development-plan.md)与[原生验证记录](docs/native-validation.md)。

- [产品说明](docs/product.md)
- [当前架构](docs/architecture.md)
- [HTTP API](docs/API.md)
- [QiGuang 迁移清单](docs/migration-plan.md)
- [开发与验收计划](docs/development-plan.md)
- [实际验收记录](docs/validation.md)

## 运行服务

需要 Python 3.11+；当前验证环境使用项目虚拟环境中的 Python 3.13。服务连接**已经运行且经确认的** Codex Unix WebSocket socket，不启动、重启或关闭 Codex runtime。

```sh
python3.11 -m venv .venv
.venv/bin/python -m pip install -e ./server
scripts/run-server.sh --workspace workspace --host 127.0.0.1 --port 18765 \
  --url http://10.0.2.2:18765 --native-socket /path/to/confirmed-codex.sock
```

把 socket 路径换成目标节点的实际路径。默认 socket 是 `--codex-home` 下的 `app-server-control/app-server-control.sock`，但它的存在不能证明当前 Desktop 使用它。当前验证的远端节点与临时转发说明见[原生验证记录](docs/native-validation.md)；当前电脑 Desktop 的本地 stdio 进程不能当作默认本地 daemon。服务启动失败不会自动降级为独立 stdio 执行。

`--url` 是手机可以访问的地址，不是监听地址。上例仅供 debug 模拟器通过 `10.0.2.2` 访问。正式连接使用受信任 HTTPS；服务可用 `--cert certificate.pem --key private-key.pem` 加载证书，或通过现有本机 HTTPS 代理访问。非 loopback 监听必须启用 TLS，release App 不允许明文连接。CLI 原始默认端口为 `8765`；上述开发命令和验证脚本显式使用 `18765`。

### 当前本机安装

本机已安装用户 LaunchAgent `local.rivo-relay`，登录时启动、异常退出后由 launchd 拉起。配置位于 `~/Library/LaunchAgents/local.rivo-relay.plist`，程序使用本仓库 `.venv`，数据目录为 `workspace/local-node`，日志位于该目录的 `logs/`。它监听 `127.0.0.1:18765`，通过原 Tailscale HTTPS 18766 入口供手机访问，原生 socket 显式指向本机 `~/.codex/app-server-control/app-server-control.sock`。

此前用于 e2-mac-mini 的临时 Relay 和专用 SSH 转发已停止；旧 `workspace/data` 保留，未迁移其连接凭据、提交和待办关联。本机节点使用新数据库和节点身份，手机需重新扫码一次。节点可连接并不证明当前 Desktop 的本地 stdio 会话共享该 daemon，Desktop UI 双向接续仍未验收。

```sh
launchctl print "gui/$(id -u)/local.rivo-relay"
# 停止 Relay 并取消本次登录会话中的自动拉起；不停止 Codex
launchctl bootout "gui/$(id -u)/local.rivo-relay"
# 再次启动
launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/local.rivo-relay.plist"
```

## 配对和撤销

在服务所在电脑、同一 workspace 下生成默认 5 分钟有效的一次性二维码。以下命令把码保存在私有文件，避免在终端打印：

```sh
.venv/bin/rivo-relay pair --workspace workspace --url http://10.0.2.2:18765 \
  --output workspace/artifacts/pairing.png --json-output workspace/artifacts/pairing.json
.venv/bin/rivo-relay connections --workspace workspace
.venv/bin/rivo-relay revoke --workspace workspace CONNECTION_ID
```

App 无已登录节点时显示授权页，以扫码为主、手动粘贴链接或输入地址/码为辅；已有登录节点直接进入项目列表，再进入项目会话列表和会话。普通断网保留登录，撤销后回到授权页。手机保存的是节点连接令牌，Desktop/OpenAI 登录凭据留在原节点。配对图片、JSON 和连接令牌文件不要放入源码或验证报告。

## 构建与验证

Android 工程使用 JDK 17、Android SDK 37、Gradle wrapper，最低 Android API 26。设置本机 SDK 路径后：

```sh
cd client/android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

APK 位于 `client/android/app/build/outputs/apk/debug/` 和 `androidTest/debug/`。从仓库根目录运行服务测试，使用项目 Python：

```sh
PYTHONPATH=server .venv/bin/python -m unittest discover -s server/tests -v
```

`scripts/verify-service.py --url URL --connection-file FILE` 默认只做认证读取；明确传 `--project-id` 才会新建一个会话并发送固定测试短句，检查 SSE、原生历史、相同 submission 去重和待办 CRUD。连接文件是配对 HTTP 响应 JSON，含顶层 `token`；它不同于 CLI 生成的 `pairing.json`。`--out` 创建脱敏报告，`--ca-file` 只为该进程指定信任 CA。

相机姿态辅助仅在开发时需要可选依赖，安装到项目虚拟环境即可：

```sh
.venv/bin/python -m pip install -e './server[emulator]'
```

该 extra 固定当前实测的 gRPC、protobuf、PyJWT 和 cryptography 版本；正式服务仍只需 WebSocket 与二维码生成依赖。首次相机控制还需按脚本提示从已安装 SDK 生成 protobuf bindings，不修改系统 Python 或 gRPC 安全设置。

`scripts/verify-emulator.sh` 使用已运行的 `emulator-5554`，默认不构建 APK，可传 `--build`。它是**全新测试流程，会 `pm clear dev.rivo.relay` 清除该测试 App 的本地数据**，真实注入相机 QR、运行扫码/会话/待办测试，保存脱敏日志和登录后截图；退出恢复相机姿态与海报。Relay、原生转发和隔离项目必须先准备好；具体参数、依赖安装提示与自定义海报恢复方式见脚本 `--help`。脚本不会启动或停止 emulator/用户服务，也不关闭安全校验。

本轮 `verify-emulator.sh` fresh 流程扫码、文本会话、待办 **3/3 通过**；HTTP 完整原生写入与最终只读复核也已通过。结果及附件位置见[实际验收记录](docs/validation.md)。

消息发送结果不确定时，App 保存原提交并提供核对；可查看已知原会话，再明确选择“结束核对”解除手机等待。该操作不会取消或重发原任务，也不会删除节点提交记录。

运行数据库、私密配对产物和验证附件放在已忽略的 `workspace/`。本机用户 LaunchAgent 已安装；正式签名、通用安装/升级程序和发布尚未作为已交付能力。
