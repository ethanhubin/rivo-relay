# 管理员节点监控 CLI

在 VPS 当前服务快照目录运行（需要部署包含本功能的网关与节点版本）：

```sh
python3 scripts/fleet-status.py
python3 scripts/fleet-status.py --json
```

本机管理员通过已有 SSH 执行同一 CLI：

```sh
ssh -o IdentitiesOnly=yes -i /Users/ethan/Documents/QiGuangBot.pem root@134.175.156.219 'python3 /opt/rivo-relay/current/scripts/fleet-status.py --json'
```

首次安装时运行 `python3 scripts/fleet-status.py --init-token`，再将 `/var/lib/rivo-gateway/monitor-token` 所有者设为网关服务用户 `rivo-gateway`。创建模式为 0600，已有文件拒绝覆盖；令牌不打印、不作为命令行参数、不提交 Git。CLI、证书及令牌可用 `--url`、`--ca-file`、`--token-file` 指定。HTTPS 校验证书，拒绝重定向。

网关 `GET /admin/monitor` 仅接受独立管理员令牌，节点接入密钥与手机授权不能查询全网。节点本地 `/internal/monitor` 仅接受 loopback 访问，网关代理不转发 internal 路径。报告不含手机令牌、设备配对 ID、请求正文或项目路径。

## 统计口径

- 注册槽位与在线控制通道分列，槽位不等同于电脑；独立 ASR 等服务可能占一个槽位。
- 节点每 15 秒上报名称、节点 ID、版本码、原生连接状态、自动更新阶段及阻塞原因；45 秒未收到报告即过期，断线后立即不计为新鲜报告。
- 活跃 App 配对：最近 90 秒有已认证请求，或仍有在途请求/SSE 的节点配对身份数。同一配对多个请求只计一次；直接访问节点的请求也计入。撤销授权后不再计入。
- 这是按节点配对统计，同一手机配对不同节点会分别计数，不能称为全网唯一物理手机数。CLI 明示此范围。
- 请求/SSE 数是当前仍在处理的已认证请求数；数据通道数是网关传输通道数，两者都不是 App 数量。
- 老节点和独立服务未实现报告时显示未知，App 总数注明覆盖不完整；离线、过期报告不累加。报告仅驻留内存，网关重启后等待重新上报；保留的旧版本信息明确标为过期。

本功能只监控，不重启服务、不强制终止任务、不触发更新。部署网关代码、CLI、创建管理员令牌以及发布节点代码后生效；旧版节点不会凭空产生新统计。

## 本次验证

新增 5 项监控测试通过，包含真实本地 HTTP 活跃统计、撤销授权、到期与长请求、管理员鉴权、遥测净化，以及节点连接器 → TLS 网关 → CLI JSON 的完整链路。网关及 HTTP 现有 91 项回归通过：其中并发 40 条长连接测试首次受本机 256 文件句柄上限影响失败，测试进程提高到 1024 后通过，其余检查复用首次结果。`git diff --check` 通过；未运行 lint、未构建 APK、未进行真机/模拟器验证。本次未部署生产网关或节点。
