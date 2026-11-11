# Tailscale 节点更新与发布

统一更新的新流程见[ VPS 统一更新](central-updates.md)：迁移后的手机使用所配对节点的 VPS 统一更新源，节点由代理自动升级；发布 CLI 在本地主节点发布后同步 VPS 并验收。以下主节点更新机制作为兼容入口，地址为 `http://100.124.150.1:18767`。0.2.43 起，扫描主发布节点带更新源声明的 v2 二维码后，可同时保存 VPS 更新路径；切换会话节点不改变更新源。未配置 VPS 时仍使用默认发布节点，无网络时明确报错，不自动访问 GitHub。

- `GET /v1/app-updates/latest`：公共版本清单，包含 version、versionCode、notes、size、sha256、apkPath；禁止缓存。
- `GET /v1/app-updates/apks/<sha256>.apk`：仅公开发布目录中的安装包，支持单 Range 下载。无配对令牌，不经过 Codex 文件读取，不提供任意文件路径。
- App 验证路径、长度及 SHA-256 后，通过 FileProvider 打开系统安装界面。没有安装权限时先引导授权，再由用户重新点击安装。

普通“发布新版”只发布节点，不推送 Git、创建 tag 或 GitHub Release。用户明确要求提交/推送时单独执行；仅明确要求发布 Release 时同步 GitHub。

## 统一 CLI

### 将普通节点设为管理节点

管理节点就是具备完整发布能力的节点，不需要再指定一台固定的“主节点”。当前初始化支持 macOS，在已安装节点的 Python 环境执行：

```sh
python -m rivo_relay setup-manager --source-node macmini --workspace /原节点/workspace
```

`--source-node` 是首次导入签名和缺少的工具时使用的已授权 SSH 节点，后续发布不依赖它。命令自动准备独立 Python 依赖、所需 Android SDK/JDK、已有发布签名和发布源码；从节点原有配置读取 VPS 地址及证书指纹，不复制手机配对令牌、数据库或原生运行时。默认签名另存，其他项目的 `~/.android/debug.keystore` 不变。重复执行复用已安装工具。

默认源码下载到 `~/.local/share/rivo-release-manager/repository`；已有仓库可加 `--repository /源码目录`。VPS SSH 可用 `--gateway-host <SSH别名>`，必要时加 `--gateway-identity /已有私钥`。管理权限来自已有管理员 SSH 凭据，普通节点接入凭证不提供发布权限。ASR 在本机时自动识别，否则使用 `e2-mac-mini`，可通过 `--asr-host` 指定。

初始化成功后使用输出的 `rivo-release` 路径，子命令仍是下文的 `prepare/run/status/resume`。只有初始化时检查整套环境；日常发布保留原有相关测试、最终 APK 签名和下载完整性校验，不增加全量测试或 lint。需要排查时可主动运行 `rivo-release doctor --online`。

管理节点的每次 `prepare` 都先获取最新 `origin/main`，创建独立 `codex/release-*` 工作区并锁定基准提交 SHA，然后在该工作区递增版本、生成发布说明。本地功能分支及未提交修改不参与构建。编辑 `prepare` 输出工作区内的发布说明后，用同一入口执行 `run <版本> --commit`；恢复只复用原工作区和提交，不重新获取 main。流程不自动推送发布提交或创建 tag/Release。

管理节点直接向 VPS 发布。聊天节点通过已有更新代理在空闲时自行升级；发布完成不代表全部节点已安装新版，也不会重启聊天运行时。原 Tailscale 下载地址作为兼容镜像同步，离线时记录 `steps.compatibility.pending=true`，VPS 发布仍可完成；恢复连接后用原版本 `resume` 重试。没有安装更新代理的旧节点仍需按迁移说明处理。

配置由初始化命令写入源码仓库的 `workspace/release-config.json`，工具、签名和安装验证在 `~/.local/share/rivo-release-manager/`。这些本机数据不提交 Git。Git 中只维护初始化/发布代码、说明和测试。初始化不发布新版、不修改节点服务。

常规发布使用初始化输出的 `rivo-release`，也可使用 `python3 scripts/release.py`。底层继续复用原发布脚本；未初始化的旧发布节点仍按原流程调用 `restart-service.py --submit`。不推送远端、不建 tag/GitHub Release，也不运行 lint。下列命令使用旧入口示例，管理节点可直接替换为 `rivo-release`，源码改动应先合并到 `main`。

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

未初始化管理节点的原发布机器继续使用既有本机服务部署流程，行为不变。旧版本若由手工流程发布，没有 `release-state.json`，继续查看其原有发布验收文件，不使用 `resume` 接管。

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

## 客户端更新入口

客户端仅通过节点／VPS 更新源检查版本，已移除无人调用的 GitHub Release 检查器。GitHub Release 的显式发布功能仍保留在发布脚本中。

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

服务切换前会同时检查候选配置和回滚配置的可执行文件、工作目录及服务快照；收到 SIGTERM 时进入回滚流程，任务异常退出会写入 `worker_failed`。SIGKILL、断电等无法由进程内异常处理恢复，不能据此保证自动回滚。

可按需执行隔离的真实 launchd 演练：`RIVO_TEST_LAUNCHD=1 .venv/bin/python -m unittest discover -s server/tests -p test_restart_launchd_integration.py`。它使用随机测试服务名、临时目录和本地端口，验证成功切换及健康失败回滚；默认单元测试跳过该演练，不操作线上 Relay 或 Codex 原生运行时。

CLI 传入 `--expected-plist launchagent-before.plist`，部署 worker 在重启锁内核对当前配置；准备后配置已变化时提前失败，不停服、不覆盖回滚基准。launchd 明确返回提交失败时会持久化 `submission_failed`，resume 立即报告提交失败，不再空等部署完成；结果不明的提交仍禁止盲目重试。

### 所选原生运行时连续性

发布前按主节点 LaunchAgent 的 `--native-socket` 和 `--codex-home`（或 `CODEX_HOME`）只读连接原生接口，沿用 Relay 的主机身份核验。要求明确配置 `app_server`；记录实际 socket、PID、启动时间、可执行文件、数据目录和平台，在服务切换前后逐项比较。不会扫描并比较所有同路径 Codex 进程，也不会把每次连接产生的 generation 当成主机身份。

辅助进程的启动或退出不再中止发布；所选主机变化、不可连接或身份无法核实时仍停止。部署记录保存 `deploymentBefore.nativeHost` 和验收中的 `nativeHost`。仅保存旧全进程列表的历史计划不能自动补造基准，需要核查后建立新计划。已完成服务切换的恢复仍先核对候选配置及主机身份，再继续发布，不重复重启服务。

2026-11-08 的 0.2.39 发布曾因旧校验把辅助进程退出误报为原生主机变化而停止。实际所选主机 PID 21622 和启动时间未变；人工核验快照、授权和健康后完成发布。本修复将该核验范围固化到后续流程。

恢复发布时，仍先核对本地源码与更新清单；若存在未完成部署，则先核对部署结果，再要求线上 HTTP 清单可访问。结果不明确或恢复失败时停止，不自动重复停服。`--retry-deploy` 先持久化日志归档意图，日志移走后中断可由下一次 resume 接续。已完成发布再次执行也会复核服务快照，服务版本发生变化时不重新发布 APK。
