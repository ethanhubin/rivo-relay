# 接入已有 DeepSeek Harness 运行时

本方案让 Rivo 节点连接正在运行的 DSH Web/Desktop Host，Android 继续使用原有节点、项目、会话和审批界面。DSH 保持历史与执行权威，Rivo 负责扫码鉴权、移动显示和提交核对。一个验证节点使用独立的 Rivo workspace 与端口，避免替换现有 Codex 节点。

本次适配目标为 `0.2.2-rc.1`。已安装版本、官方更新源以及实际验收情况见[验证记录](../验证记录/DSH-Integration-2026-11-11/README.md)，其中 QA 结论描述该次运行，不代表未来升级已兼容。

## 先核实并更新实际运行时

检查桌面“关于”和官方更新入口，更新实际运行的 DSH 桌面/Web 包。旧全局 CLI 或 npm 缓存版本不一定是当前 Host 的版本。升级前保留 profile 配置；有活动任务时先按 DSH 自己的流程处理，不用结束进程来强制升级。

项目提供只读检查脚本：

```sh
python3 scripts/verify-dsh.py \
  --app '/Applications/DeepSeek Harness.app' \
  --bridge-socket "$HOME/.dsh/rivo/bridge.sock" \
  --relay-url http://127.0.0.1:18785
```

它读取安装包的 `Info.plist` 和官方更新 feed，核对桥的适配版本，再查询桥状态与 Relay `/health`。JSON 中任何检查失败会返回非零退出码。首次尚未安装桥/启动 Relay 时，相应检查失败是预期结果。脚本不安装、不修改 profile、不重启服务、不生成配对码、不清除 APP 数据，也不访问聊天历史。`/health` 本身不返回后端类型，必须同时核对下方 `--runtime dsh` 启动参数。

## 在现有 Host 加载桥插件

桥源码在 [`integrations/dsh-bridge`](../integrations/dsh-bridge/README.md)，只依赖 Node 内置模块。它通过 DSH 当前进程中的公开服务访问会话，不启动 SDK 子进程或第二个 DSH 运行时。

macOS 桌面示例：先将 `integrations/dsh-bridge/index.mjs` 复制到 `~/.dsh/profiles/desktop/rivo-bridge/index.mjs`，保留 `~/.dsh/profiles/desktop/cordis.patch.yml` 的备份，再向现有 YAML 列表追加独立行，保留原内容：

```yaml
- insert:
    - id: rivo-dsh-bridge
      name: /Users/你的用户名/.dsh/profiles/desktop/rivo-bridge/index.mjs
      config:
        socketPath: /Users/你的用户名/.dsh/rivo/bridge.sock
```

路径必须使用实际绝对路径，YAML 中的 `~` 不会替代这一要求。Web 部署应编辑正在使用的 Web profile，而非另起一套服务。已启用配置 HMR 的 Host 会加载新增行；如果当前部署没有 HMR，应按正常维护时机重新加载配置，不能把源码复制成功当成桥已经工作。

桥默认使用 `$DSH_HOME/rivo/bridge.sock`，未指定时为 `~/.dsh/rivo/bridge.sock`。父目录必须由当前用户持有且权限 `0700`，socket 为 `0600`。插件不监听 TCP。若存在遗留 socket，先确认原桥已经停止；不要直接删除活动实例的端点。

替换桥源码后，递增该插件行的 `config.revision` 整数以触发配置 HMR，再读取桥状态确认新的 `generation`；不能把单独复制文件当成已加载新代码。重新加载会使旧桥代际失效，待处理的 Rivo 审批会回到 DSH 原审批链，应在测试审批结束后操作。

卸载时只移除自己添加的插件行，由 HMR 关闭对应 socket。不要用整份旧备份覆盖后来新增的用户配置。

## 启动独立验证节点

先按仓库开发说明准备 Python 3.11+ 和已安装服务端依赖的 `.venv`。以下命令在仓库根目录执行，使用 Android 模拟器访问宿主机的标准地址：

```sh
scripts/run-server.sh \
  --runtime dsh \
  --dsh-bridge-socket "$HOME/.dsh/rivo/bridge.sock" \
  --workspace "$PWD/workspace/dsh-validation/relay" \
  --host 127.0.0.1 --port 18785 \
  --url http://10.0.2.2:18785
```

此命令持续运行；另开终端执行检查和配对。不要同时传入 Codex 专用的 runtime、可执行文件或 Desktop bridge 参数。

真实手机使用可达的节点地址。普通局域网远程监听应配置受信任 HTTPS，例如启动参数改为 `--host 0.0.0.0 --cert /绝对路径/cert.pem --key /绝对路径/key.pem --url https://节点域名:18785`。证书应匹配域名并受手机信任。已有 Tailscale/VPS 路径按[节点部署](node-deployment.md)和[双通道指南](vps-gateway.md)单独配置；本次 DSH 验证不自动更改公网路由或发布服务。

## 生成二维码并在 APP 配对

保持 `--workspace` 与运行中的节点一致，`--url` 使用手机实际能访问的同一入口：

```sh
.venv/bin/python -m rivo_relay pair \
  --workspace "$PWD/workspace/dsh-validation/relay" \
  --url http://10.0.2.2:18785 \
  --output "$PWD/workspace/dsh-validation/pairing.png" \
  --json-output "$PWD/workspace/dsh-validation/pairing.json"
```

二维码 5 分钟有效、仅能兑换一次；通过 Rivo 原有扫码入口添加节点。二维码和 JSON 含短期配对凭据，应留在已忽略的 `workspace/`，不要提交或放进验证报告。

需要包含本次适配的 Android APK。开发构建为：

```sh
cd client/android
./gradlew :app:assembleDebug
```

在测试设备上安装对应 APK 后扫码。没有必要清除现有 APP 数据；新增 DSH 节点即可。扫码后选 DSH 已登记的工作区和会话，或在工作区内新建会话。桥的 `create` 接受工作区或目录二选一；工作区外的验证会话可能归入 DSH 的未分组区域，不能据此断言项目列表里一定可见。

## 交互与恢复语义

- 文本聊天使用同一原生聊天界面。输入被 DSH 接收后以持久 `requestId` 确认，不把“已接收”伪装成已有执行轮次。
- DSH 原生工具审批显示为通用授权卡片，只提供允许一次、拒绝和取消；权限含义由 DSH 执行。测试审批使用专用会话的 workspace-write/ask，不能改变用户已有会话或全局权限。
- Rivo 轮询存活时承接已查看/发送会话的审批；轮询停止超过 30 秒会使旧审批 key 失效，并退回 DSH 原审批链。其他会话保持原有审批路径。
- 断线重连后读取 DSH 权威快照补齐历史。事件缓冲缺口要求 reset；手机未核对的缓存不授权执行。输入结果未知时只查持久接受证据，不自动重发。
- 停止操作携带期望轮次，在 DSH 进程内核对同一轮次后同步取消，避免旧停止按钮误取消随后开始的新任务。

## 当前验证范围与限制

本阶段覆盖既有运行时的文本会话、流式显示、工具审批和恢复；具体通过/未通过项以[本次验证记录](../验证记录/DSH-Integration-2026-11-11/README.md)为准。只读检查脚本成功只能证明版本和连接状态，不代表扫码、聊天或审批闭环通过。

当前节点未开放 DSH 模型选择、普通附件发送、Rivo 待发队列、目标模式、代码审查/差异、文件管理、会话分叉/删除、Codex writer 接管等功能。既有历史的非文本内容按适配能力显示，不能将占位文字当成文件已传输。较大的历史读取有界，超过适配窗口会报错，不静默返回不完整历史。

后端没有复制一份已完成聊天数据库；显示缓存与提交去重记录仍归 Rivo。上游接口迭代很快，每次 DSH 升级后应重新运行版本/连接检查及受影响的真实闭环验证。
