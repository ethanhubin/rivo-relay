# Antigravity 节点

Android 复用 Rivo 的扫码配对、会话界面和实时事件。用 VPS HTTPS 直链安装 APK，再扫描[节点配对二合一码](vps-gateway.md#手机配对二合一码)。一个节点连接一个 Antigravity 运行实例。同台 Mac 可以用独立的 Relay 数据目录和端口同时运行 CLI 与桌面节点。Google App 与 CLI 默认各自保存会话历史。 [ACP 接入文档](https://antigravity.google/docs/ide/extensions/acp) [CLI 与桌面 App](https://antigravity.google/blog/introducing-google-antigravity-cli)

## 接入桌面 App 已有会话

登录并运行 macOS Antigravity App。桌面适配器以 App 和 Language Server **2.35.2** 为兼容基线，通过 App 的本机注册信息查找并核验运行中的服务。按[节点部署](node-deployment.md)准备 `RIVO_NODE_DIRECT_HTTPS` 和独立 VPS 凭据。将 `--antigravity-project` 指向 App 项目里的实际目录。包含多个目录的 App 项目需要列出其所有目录。

```sh
scripts/start-node.sh --mode auto --direct-url "$RIVO_NODE_DIRECT_HTTPS" --no-pair \
  --runtime antigravity --antigravity-mode desktop \
  --antigravity-project /absolute/path/to/project \
  --workspace "$PWD/workspace/antigravity-desktop" \
  --local-port 18789
```

默认 App 配置目录为 `~/.gemini/antigravity`。自定义 App 配置目录时带上 `--antigravity-profile-dir /absolute/path/to/profile`。配对后在 Android 选中桌面已存在的会话。Rivo 使用原生会话 ID、运行代际和步骤编号定位命令审批，并在发送决定后核对原生步骤状态。遇到需要其他交互类型的会话，Android 会指向 App 处理。空闲会话具备原生模型配置时可以继续发文本，回执以原生记录中出现对应的输入 ID 为准。 [Multigravity 已公开的本机接口实现](https://github.com/GHSaiMo/antigravity-mobile/tree/6a1692cf659fa29fc731a3bd953488543722c741/internal/proxy)

桌面 App 2.35.2 在「Request Review」下，可把终端命令挂在通用权限请求中。Rivo 核对请求目标与原生命令完全一致后，显示「允许本次」和「拒绝」。Rivo 收到同一步骤的原生一次性权限记录后，确认审批回执。 [Antigravity 权限设置](https://antigravity.google/docs/agent-settings)

## 启动 CLI 验证节点

Rivo 的 Antigravity CLI 接入已在 macOS 上验证。Windows 暂不支持此接入。

1. 安装 Google 官方 Antigravity CLI。试点版本为 1.5.18。用运行节点的账户执行 `agy --version` 和 `agy acp --help`，再以 `agy acp --login` 完成本机登录。 [官方安装入口](https://antigravity.google/download) [CLI 版本说明](https://antigravity.google/docs/changelog?tab=cli)
2. 在仓库根目录准备 Python 3.11 以上的 `.venv` 并安装 `./server`。建立单独的 Rivo workspace 和测试项目目录。

   ```sh
   mkdir -p "$PWD/workspace/antigravity-validation/project"
   scripts/start-node.sh --mode auto --direct-url "$RIVO_NODE_DIRECT_HTTPS" --no-pair \
     --runtime antigravity \
     --antigravity-executable "$(command -v agy)" \
     --antigravity-project "$PWD/workspace/antigravity-validation/project" \
     --workspace "$PWD/workspace/antigravity-validation/relay" --local-port 18786
   ```

   `--antigravity-executable` 接受实际可执行文件的绝对路径。`--antigravity-project` 可重复传入现存目录，节点只列出和接入这些目录的会话，空目录也会显示在 Android 项目列表。macOS 上从完成登录的用户终端启动节点，以便 CLI 访问该用户的钥匙串。

3. 连接器就绪后，用同一启动参数将 `--no-pair` 换成 `--attach --pair-only`，生成[配对二合一码](vps-gateway.md#手机配对二合一码)。Android 本地测试包使用 `client/android` 下的 `./gradlew :app:assembleDebug -PrivoLocalTest=true` 构建，通过 VPS HTTPS 直链交付。

4. 配对后确认节点显示 Antigravity。打开测试项目，新建会话并发送文本。检查流式输出、历史回读、权限提示与重连。可从主机访问 `http://127.0.0.1:18786/health` 检查 `nativeConnected`。

   ACP 回合完成后，Rivo 才确认该次输入的原生回执。等待期间仍可以回答当前会话的审批。手机掉线后按原提交 ID 查询结果。若原生 ACP 连接在确认前中断，Rivo 保留待核对状态并暂停该次提交的重发。

## 远程访问

已配置的 VPS 连接器可用 `--attach` 接入单独运行的 Relay。连接器的 workspace 和 `--local-port` 必须与 Relay 相同。测试节点使用独立凭据，详情见 [VPS 中转与 Tailscale 双通道](vps-gateway.md)。

## 托管更新

用 `scripts/start-node.sh` 配置节点时传入同一组 `--runtime`、`--antigravity-mode`、`--antigravity-project` 和适用的配置目录或 CLI 路径。托管安装器读取节点保存的运行时配置，Antigravity 托管使用第 3 版或更新的代理。桌面节点使用 macOS 登录用户服务，以便连接同一个 App 实例。 [节点自动更新](node-updates.md) [统一更新中心](central-updates.md)

托管代理仅在节点范围内的原生任务和审批处于可更新状态时切换 Relay，并在停止前重新核对桌面状态。候选通过相同节点 ID、版本码、运行时类型、配置目录身份和连接检查后才启用。错误候选回滚时恢复原运行时设置和 SQLite 数据，桌面 App 继续运行。

## 首轮验证

2026-11-27，macOS 上使用官方签名 CLI 1.5.18。Android 16 模拟器扫码配对，打开测试项目并从运行中的真实任务回应一次性终端命令审批。原生回合完成，测试文件恰好写入一行。

在独立的回执测试中，通过真实 CLI 发起一个任务并丢弃首个 HTTP 回包。相同提交 ID 返回待完成状态，提交对应的原生审批后查询到接受回执。原生历史只有一条输入，测试命令只写入一行。重启 Relay 后再次回读得到同一会话历史与回执。

## 桌面阶段验收

2026-11-27，macOS Antigravity App 2.35.2 与 Android 16 模拟器使用独立测试项目。

| 场景 | 结果 |
| --- | --- |
| 桌面已有任务 | Rivo 打开由 App 本机服务发起的会话，读到原有历史、运行状态和输出。 |
| 在 Rivo 接续 | 模拟器向同一会话发送消息并看到回复。App 历史中只有一条对应输入，原生消息 ID 与 Relay 回执一致。 |
| 命令审批 | 在同一个测试会话启用 Request Review。真实 App 停在待审批命令，测试文件尚未创建。在 Android 模拟器选「允许本次」并确认一次后，App 记录一次性审批，原命令以退出码 0 完成，测试文件只写入一行。完成回复沿用同一个原生执行 ID，并显示在 Rivo。 |
| 托管更新 | 本机 TLS 环境验证空闲时启用新版、真实桌面任务执行期间延后、候选配置目录错误时回滚。旧版恢复时保留节点 ID 和已配对的 Android 连接，并重新连接 App。 |
