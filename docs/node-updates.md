# Tailscale 节点更新与发布

默认更新源为 `http://100.124.150.1:18767`。App 在未登录或切换会话节点后仍使用这个发布节点；无网络时明确报错，不自动访问 GitHub。

- `GET /v1/app-updates/latest`：公共版本清单，包含 version、versionCode、notes、size、sha256、apkPath；禁止缓存。
- `GET /v1/app-updates/apks/<sha256>.apk`：仅公开发布目录中的安装包，支持单 Range 下载。无配对令牌，不经过 Codex 文件读取，不提供任意文件路径。
- App 验证路径、长度及 SHA-256 后，通过 FileProvider 打开系统安装界面。没有安装权限时先引导授权，再由用户重新点击安装。

普通“发布新版”只发布节点，不推送 Git、创建 tag 或 GitHub Release。用户明确要求提交/推送时单独执行；仅明确要求发布 Release 时同步 GitHub。

## 发布

递增 versionName/versionCode，构建并验证签名，运行相关测试。默认不运行 lint，版本号递增不触发 lint；仅在明确要求或排查具体静态检查/兼容性问题时按需执行。把 APK 复制到 `workspace/artifacts/release-<version>/rivo-relay-<version>-debug.apk`，准备说明后运行：

```sh
python3 scripts/publish-update.py \
  --workspace workspace/local-node \
  --apk workspace/artifacts/release-0.2.13/rivo-relay-0.2.13-debug.apk \
  --version 0.2.13 --version-code 25 --notes docs/releases/0.2.13.md
```

脚本使用 Android SDK 的 aapt 验证包名和版本，先写入 `updates/apks/<sha256>.apk`，再原子替换 `updates/latest.json`。拒绝降级和同 versionCode 不同内容。旧 APK 保留，使已获取旧清单的下载可继续。后续只更新 APK/清单无需重启；首次安装服务端接口需要平滑重启 Relay，不重启 Codex。

仅在明确要求 GitHub Release 时追加 `--github-release`，并先按用户授权将对应提交推到远端。

## 首次迁移

0.2.12 及更早版本仍使用 GitHub 更新逻辑，需要手动下载一次 0.2.13 节点 APK 并覆盖安装。之后可直接在 App 检查节点更新。此次只部署主发布节点，其他会话节点无需跟随提供更新接口。

## 主节点服务代码隔离

主节点服务使用发布提交的快照 `workspace/deployments/<commit>/server`（具体提交记录在发布目录的部署验收文件中）；LaunchAgent 的 `PYTHONPATH` 指向该目录，避免共享工作区中的并行开发改动混入线上服务。后续涉及服务端的发布，需要生成目标提交的快照并更新该路径后重启 Relay。若发布会话本身由 Relay 承载，由独立进程在该会话最终回复落盘后完成切换与 HTTP 验证，避免等待自身结束造成循环等待。

### 服务切换与失败恢复

服务端配置变更必须使用 `scripts/restart-service.py --candidate <新配置.plist> --submit --log-dir <发布目录>`，
不得直接串联 `bootout` / `bootstrap`。候选配置独立保存；工具预检快照和可执行文件，
加排他锁并保存 `.rollback.plist`，对加载失败进行有界重试，检查本地健康接口；
启动或健康检查失败自动恢复原配置并重新加载，且以失败状态退出。
重启操作只针对 `local.rivo-relay`，不操作 Codex 原生运行时。
将工具标准输出及错误重定向到本次发布目录的 `deployment.log`；日志为带 UTC 时间的阶段事件。
服务端切换完成后仍须验证 Tailscale HTTP 清单及 APK 大小、SHA-256，全部通过才标记发布成功。
HTTP 响应日志记录 UTC 时间、方法和状态，不记录地址、查询参数、凭据或请求正文。

2026-11-07 故障：0.2.23 部署在 19:51:34 卸载服务后 bootstrap 返回错误 5，
旧脚本没有重试与回滚，导致服务保持卸载状态。同配置后续手动 bootstrap 成功；
系统日志确认根因是异步卸载竞态：19:51:33.518 发起 bootout，33.529 即 bootstrap，
launchd 在 33.530 拒绝并记录 `37: Operation already in progress`；旧服务到 34.352 才退出并移除。
修复要求 bootout 后轮询确认服务已从 launchd 移除，才允许 bootstrap；回滚同样遵守此屏障。

服务切换必须通过 `--submit` 交给 launchd 独立执行，避免聊天或命令取消在卸载后杀死部署进程。提交成功不代表部署成功，必须读取 deployment.log 的 complete/rollback 结果并验证 HTTP。
