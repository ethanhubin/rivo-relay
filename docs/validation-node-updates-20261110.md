# 节点自动更新排查与修复（2026-11-10）

## 原因与代码修复

- E2 与 MacBook Pro 只有旧 Relay、连接器 LaunchAgent，没有安装自动更新代理。
- 活跃状态扫描通过 history_host_connection 固定历史读取路由；update_threads 原先把所有路由记录当作订阅。真实 MacBook Pro 重现：启动扫描前空闲检查通过，启动后数百个未加载历史导致检查一直失败。现在单独记录真实 start/fork/resume 与接管订阅；历史只读路由不阻塞升级，自有 writer 仍纳入检查。
- 主节点部署没有注入 RIVO_RELEASE_CODE，验收只检查 status。现在注入发布版本，跳过部署须同时匹配版本配置，验收拒绝缺失或旧版编号。
- 迁移提示词曾引用普通 VPS 发布不存在的 GitHub tag/Release；改为实际源码及统一更新清单。

## 已执行的线上维护

- 主节点只更正 LaunchAgent 版本环境并事务式重启 Relay，健康接口返回 106、nativeConnected=true。备份与日志在主工作区 workspace/artifacts/node-auto-update-20261110。
- MacBook Pro 已安装代理修订 2，旧 Relay/连接器禁用自启并保留配置备份。bootstrap 的 runtime.py 应用了本次空闲修复，保留 .py.before-idle-fix 备份；随后实际自动下载、安装、切换到 106，状态 idle。原节点 ID 保持，6 个有效授权均保留，原生 PID、启动时间、可执行文件均未改变。
- 注意：106 发布包尚未包含本次 runtime 修复。bootstrap 修复解决此次迁移，但运行中的 106 仍可能在下一次升级遇到同类阻塞；必须将本次源码纳入后续发布，不能将本次迁移视作长期修复已分发。
- E2 bootstrap 依赖已准备，未激活。存在两条 thread.turns 提交 unknown（89123211-ac61-4434-9124-283e8296c6f1、7671d592-e450-493b-814c-e5e83118cd07），无 receipt；没有擅自清除或修改结果，也没有停服。
- 公司电脑、家庭服务器和 MacBook Air SSH 不可达。另一个在线版本 95 的节点 eaf23135-a7dc-5551-9534-1fcd8684fbef 尚缺设备映射；未冒充已维护。VPS 7 个槽位不是已核实的 7 台电脑，未撤销离线槽位。

## 验证

- 发布 CLI 26 项测试通过。
- runtime 生命周期、统一更新与恢复组合 42 项测试通过，包含新增历史只读路由不阻塞、真实订阅与自有 writer 仍受保护的回归。
- MacBook Pro 真实自动切换及原生/授权连续性验收通过。
- 未执行 Android 构建、lint、真机或模拟器验证；本轮维护未发布修复版本。
