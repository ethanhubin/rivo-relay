# Tailscale 节点更新与发布

默认更新源为 `http://100.124.150.1:18767`。App 在未登录或切换会话节点后仍使用这个发布节点；无网络时明确报错，不自动访问 GitHub。

- `GET /v1/app-updates/latest`：公共版本清单，包含 version、versionCode、notes、size、sha256、apkPath；禁止缓存。
- `GET /v1/app-updates/apks/<sha256>.apk`：仅公开发布目录中的安装包，支持单 Range 下载。无配对令牌，不经过 Codex 文件读取，不提供任意文件路径。
- App 验证路径、长度及 SHA-256 后，通过 FileProvider 打开系统安装界面。没有安装权限时先引导授权，再由用户重新点击安装。

普通“发布新版”只发布节点，不推送 Git、创建 tag 或 GitHub Release。用户明确要求提交/推送时单独执行；仅明确要求发布 Release 时同步 GitHub。

## 统一 CLI

常规发布使用 `python3 scripts/release.py`。底层仍调用 `publish-update.py` 和 `restart-service.py --submit`；不推送远端、不建 tag/GitHub Release，也不运行 lint。

```sh
# 默认递增 patch/versionCode；按实际改动指定相关测试，不自动跑全量。
python3 scripts/release.py prepare \
  --android-test dev.rivo.relay.SessionControllerTest \
  --server-test test_api

# prepare 输出新版本，并生成 docs/releases/<版本>.md。
# 编辑说明，移除 TODO，明确真机/模拟器验证范围。
# 暂存本次功能修改和新增源码；CLI 只自动暂存版本文件与发布说明。
git add <本次改动的文件>
python3 scripts/release.py run 0.2.28 --commit

python3 scripts/release.py status 0.2.28
python3 scripts/release.py resume 0.2.28
```

示例版本号须替换为 `prepare` 实际输出。`--version` 可指定更高版本，`--notes` 可使用仓库内已有说明，`--device-scope` 记录实际设备验证范围。已有明确的验证依据时，可通过 `--checks-note '检查记录及复用依据'` 留存说明；人工说明不会被 CLI 标记成自动执行通过。Android/服务端测试选项都可重复指定。源码已提交时省略 `--commit`；否则必须显式指定，且其他功能修改应已暂存。CLI 不会擅自提交未暂存或未跟踪的源码。

`run` 和 `resume` 将整个流程交给独立后台进程，终端断开或聊天中断后继续运行。提交后台任务不代表发布成功；查看 `status` 的 `phase`、`running`、错误及日志位置，只有 `complete` 代表 HTTP 验收通过。日志和状态位于 `workspace/artifacts/release-<版本>/`，不提交 Git。

流程依次执行：锁定源码 → 相关测试 → 最终 Debug APK → 包名/版本/与上版签名校验 → 按授权提交 → 从提交导出服务端快照 → 必要时备份数据库并切换 Relay → 原子发布 → 经主节点 HTTP 下载 APK 校验长度和 SHA-256。服务端内容没变时跳过重启；服务切换只针对 Relay，并核对原生 daemon 的 PID/启动时间和已有授权。

同一源码、测试选择和工具链对应的成功检查可跨版本复用；版本号和文档变化不使检查失效，日志被修改或其他源码变化则不复用。保存过的 APK 会重新验签及校验哈希，恢复时不会重建已确认的安装包。构建前检查是否已有 Gradle wrapper 在运行，CLI 发布之间另有排他锁。

恢复会重新确认源码、清单和产物，不能拿修改过的源码继续旧计划；需要新建版本计划。服务切换已完成时直接继续验收，不重复重启。若日志确认回滚完成，排查原因后可显式运行 `resume <版本> --retry-deploy`；若切换结果未知，CLI 继续等待并报出状态，不盲目卸载服务。`prepare` 只改版本/说明和计划，不会构建、提交或上线。

当前 CLI 面向本机主节点 `workspace/local-node` 和 macOS LaunchAgent，需要已有上一版清单/APK、仓库 `.venv` 和 Android SDK/JDK；不负责首次部署、其他节点同步或 GitHub 发布。旧版本若由手工流程发布，没有 `release-state.json`，继续查看其原有发布验收文件，不使用 `resume` 接管。

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

服务切换必须通过 `--submit` 交给独立 launchd job 执行（仅 setsid / start_new_session 无法脱离 Relay 的 launchd 生命周期），避免聊天或命令取消在卸载后杀死部署进程。提交成功不代表部署成功，必须读取 deployment.log 的 complete/rollback 结果并验证 HTTP。

发布主任务和服务切换任务均由独立 launchd job 执行，避免 Relay 卸载时中断后续 APK 发布。提交失败直接报错；提交成功仅代表调度成功，仍须检查 status 和部署日志。发布 worker 在取得全局锁后重新读取状态，避免沿用过期步骤。

一次性任务使用显式 `RunAtLoad=true / KeepAlive=false` 的临时 plist，通过 `launchctl bootstrap` 加载；不要使用 `launchctl submit`，本机实测该方式会重复拉起已完成任务，造成循环停服。验证需覆盖任务退出后至少两个重调度周期，不能只检查首次启动。
