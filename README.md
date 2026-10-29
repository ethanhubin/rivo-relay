# Rivo Relay

Rivo Relay 是一个自托管的 **Codex 移动工作台**，让你在 Android 手机上查看项目、继续 Codex 会话，并把待办交给 Codex 执行。离开电脑后，也能查看任务进度、处理提问与审批、阅读生成的文件和结果。

项目由 Android App 和运行在你自己电脑上的 Relay 服务组成。Codex 在电脑端运行，手机通过 Relay 连接已有的 Codex 运行时，复用节点上的登录、项目、原生会话和运行配置。无需在手机上另行登录 OpenAI，Desktop/OpenAI 登录凭据保留在原节点。

## 可以做什么

- **处理 Codex 会话**：浏览项目和历史会话，新建或继续会话，发送文本与图片，查看流式输出，停止任务并响应原生提问与审批。
- **整理工作进度**：置顶、归档和批量整理会话，保留草稿，查看未读状态、上下文占用与账户额度，接收任务完成或待处理交互的本机通知。
- **查看代码与文件**：使用 Plan 讨论模式和 Review 代码审查，查看工作空间 diff，创建独立工作树，预览源码、Markdown、图片和 PDF，下载或分享文件。单文件上传和下载支持 1 GiB。
- **管理并交付待办**：创建待办、子任务、截止日期和提醒，关联节点文件，将待办交给指定项目的 Codex 会话执行，并保留会话关联。待办离线修改可在恢复连接后同步。

当前聚焦 Android、Codex 会话与待办，不包含语音输入或通用桌面远程控制。具体功能与限制见[功能记录](docs/feature-parity.md)。

## 如何工作

```text
Android App  →  你电脑上的 Relay 服务  →  已运行的 Codex 运行时
                配对、待办与连接管理      原生会话、任务执行与配置
```

电脑端生成一次性配对二维码，手机扫码后进入项目列表，再选择会话开始工作。Relay 采用 Python 单服务，Android App 使用 Kotlin/Compose；不需要中央账号或云中转服务。

App 沿用原生节点的权限与运行设置，不另设项目白名单、设备权限等级或额外审批；Codex 原生要求的提问与审批仍由用户处理。会话历史以 Codex 原生数据为准，Relay 保存自己的待办、连接和必要运行状态。

**项目目标是成为 Codex Desktop 在手机上的完整延续，但 Desktop UI 双向接续尚未完成验收。** 当前连接的是指定的既有 Codex daemon，必须确认它与目标 Desktop 的连接关系；仅能连接节点不代表已经共享桌面会话。未知扩展表单与原生身份签名挑战也仍有未解决或未验收边界，详见[原生验证记录](docs/native-validation.md)。

## 开始使用

1. 准备已安装并登录 Codex 的电脑，确认可连接的既有 Codex Unix WebSocket socket。
2. 按下方说明安装并启动 Relay，配置手机可访问的服务地址；正式连接使用受信任 HTTPS。
3. 安装 Android App，在电脑端生成配对二维码，用手机扫码或手动输入配对信息。
4. 在手机选择项目，开始或继续会话，也可以创建待办并交给 Codex 执行。

Android 当前开发构建为 **Android 0.2.1**（versionCode 13）：[本地 APK](workspace/artifacts/rivo-relay-0.2.1-debug.apk)。本轮按栖光源码核对并迁移会话列表、聊天、文件弹窗、目录浏览和待办交互；不支持语音输入，不新增服务、模型或部署依赖。范围及保留差异见[功能记录](docs/feature-parity.md)，验证见[验证记录](docs/validation.md)。此前已发布的 [0.1.10 APK](https://github.com/ethanhubin/rivo-relay/releases/download/v0.1.10/rivo-relay-0.1.10-debug.apk) 保留，0.2.1 尚未发布到 GitHub Release。单文件上传和下载继续支持 1 GiB。

- [运行服务](#运行服务)
- [配对和撤销](#配对和撤销)
- [构建与验证](#构建与验证)
- [项目文档](#项目文档)

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
## 当前本机部署记录

本机已安装用户 LaunchAgent `local.rivo-relay`，登录时启动、异常退出后由 launchd 拉起。配置位于 `~/Library/LaunchAgents/local.rivo-relay.plist`，程序使用本仓库 `.venv`，数据目录为 `workspace/local-node`，日志位于该目录的 `logs/`。它监听 `127.0.0.1:18765`，通过原 Tailscale HTTPS 18766 入口供手机访问，原生 socket 显式指向本机 `~/.codex/app-server-control/app-server-control.sock`。

手机通过本机节点生成的二维码完成配对。节点可连接并不证明当前 Desktop 的本地 stdio 会话共享该 daemon，Desktop UI 双向接续仍未验收。

```sh
launchctl print "gui/$(id -u)/local.rivo-relay"
# 停止 Relay 并取消本次登录会话中的自动拉起；不停止 Codex
launchctl bootout "gui/$(id -u)/local.rivo-relay"
# 再次启动
launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/local.rivo-relay.plist"
```

## 项目文档

| 文档 | 内容 |
| --- | --- |
| [产品说明](docs/product.md) | 产品定位、使用流程与范围 |
| [功能记录](docs/feature-parity.md) | 当前功能、行为与已知限制 |
| [当前架构](docs/architecture.md) | 服务与客户端结构、数据和协议边界 |
| [HTTP API](docs/API.md) | 接口与字段说明 |
| [开发与验收计划](docs/development-plan.md) | 开发进度与待验收事项 |
| [实际验收记录](docs/validation.md) | 测试结果与节点更新记录 |
| [原生验证记录](docs/native-validation.md) | Codex 运行时接入与跨端验证证据 |

源码主要位于 `server/`（Python 服务）、`client/android/`（Android App）和 `scripts/`（运行与验证脚本）。
