# Rivo Relay

Rivo Relay 是自托管的 Codex 移动工作台。通过 Android 手机连接电脑上的 Relay 服务，浏览项目、继续会话、处理审批、查看文件，并将待办交给 Codex 执行。

Codex 在节点电脑运行，复用节点的登录、项目和运行配置。手机通过一次性二维码配对，保存节点连接令牌。

## 功能

- 会话：文本、图片与文件输入，流式回复，原生提问与审批，模型和权限设置。
- 项目：历史会话、工作树、代码审查、差异和文件预览。
- 待办：子任务、截止日期、提醒、离线同步及关联 Codex 会话。
- 多节点：Tailscale 直连和可选 VPS 中转。
- 附件：图片编辑、音视频查看、单文件最大 1 GiB 的上传和下载；语音输入需配置 ASR。

## 开始使用

1. 在电脑上安装并登录 Codex，准备 Python 3.11+ 和 Git。
2. 按[节点部署指南](docs/node-deployment.md)启动 Relay，配置手机可访问的地址。
3. 安装 Android App，扫描节点生成的配对二维码。
4. 选择项目，开始或继续会话。

支持[通过 VPS 统一更新节点和 App](docs/central-updates.md)，现有设备需一次性迁移；手机在“设置 → 检查更新”安装新版。原主节点 `http://100.124.150.1:18767` 保留兼容更新入口。发布操作见[节点更新与发布](docs/node-updates.md)。

Desktop 共享会话需要宿主桥接集成，接入方式及验证范围见[Desktop 桥接](docs/codex-desktop-bridge.md)。Windows 部署要求及运行限制见[节点部署指南](docs/node-deployment.md)。

## 开发

源码目录：`server/` 为 Python 服务，`client/android/` 为 Kotlin/Compose 客户端，`scripts/` 为部署和验证工具。

分支、提交、PR 正文及审核合并流程见 [GitHub PR 提交规范](docs/github-pr-workflow.md)。

安装服务端依赖：

```sh
python3.11 -m venv .venv
.venv/bin/python -m pip install -e ./server
```

Android 构建需要 JDK 17 和 Android SDK 37：

```sh
cd client/android
./gradlew :app:assembleDebug
```

APK 输出到 `client/android/app/build/outputs/apk/debug/`。按改动运行相关测试，常用入口：

```sh
# 服务端：将 test_api.py 替换为受影响的测试文件
PYTHONPATH=server .venv/bin/python -m unittest discover -s server/tests -p test_api.py

# Android：在 client/android 下执行，可用 --tests 指定测试类
./gradlew :app:testDebugUnitTest
```

设备验证使用 `scripts/verify-emulator.sh`，参数见 `--help`。该脚本会清除测试 App 的本地数据。运行数据和验证产物存放在已忽略的 `workspace/`。

## 文档

| 文档 | 内容 |
| --- | --- |
| [产品说明](docs/product.md) | 定位与使用流程 |
| [功能说明](docs/feature-parity.md) | 功能及使用范围 |
| [架构](docs/architecture.md) | 服务、客户端和数据结构 |
| [HTTP API](docs/API.md) | 接口与字段 |
| [Android 开发](client/android/README.md) | 构建、模块和客户端行为 |
| [节点部署](docs/node-deployment.md) | macOS、Linux 和 Windows 部署 |
| [VPS 中转](docs/vps-gateway.md) | 双通道和中转配置 |
| [更新与发布](docs/node-updates.md) | APK 发布和服务切换 |
| [验证记录](docs/validation.md) | 历史测试结果与设备范围 |
| [原生验证](docs/native-validation.md) | 运行时接入验证 |
