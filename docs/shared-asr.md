# 中转统一语音识别

已配置 VPS 的节点默认向所属中转请求语音服务。节点不安装 ASR 模型、不保存模型地址或 ASR 后端密钥。App 继续调用当前配对节点的 `/v1/speech/*`，使用原配对授权；节点使用自身已有的 VPS 凭据调用中转，复用接入凭据中的证书 SHA-256 固定校验。

```text
App → 当前节点 → VPS 共享语音接口 → VPS 选定的 ASR 后端
                                      └─ 当前：E2 出站连接器 → E2 回环 ASR
```

手机到当前节点可走直连或 VPS。后两段固定走公网 VPS，不依赖 Tailscale，也不要求手机切换聊天节点。E2 自己的 Rivo 节点同样先请求中转。

## 中转配置

服务状态目录 `/var/lib/rivo-gateway/speech-backend.json`（0600，归 `rivo-gateway` 所有）包含 `url`、`token`。当前 `url` 指向 VPS 8443 的 E2 ASR 专用槽位；token 是 ASR 服务的 Bearer 密钥，不进入 Git、App 或节点接入包。更换识别主机只改这份中转配置并重启网关，不改各节点配置。网关重启会使现有语音流失效，客户端需重新录音。

共享接口为 `/gateway/speech/{slot}/capabilities` 和 `/gateway/speech/{slot}/v1/speech/*`，使用该 slot 的节点接入 secret 鉴权，每次读取现有密钥库，撤销立即生效。中转复用有界语音会话服务，隔离不同节点的流 ID；节点继续隔离各手机的流 ID。最多 4 个有效语音会话、8 个并行后端调用，取消 HTTP 等待不会提前释放尚在执行的后端调用配额。音频写入不自动重试。

网关未配置 ASR 时，能力发现返回不可用。节点正向能力结果缓存 30 秒、失败缓存 5 秒；旧版网关、无效凭据和证书指纹不符均不能报告可用。接口不返回 ASR 具体地址或密钥。

## 节点默认行为

存在 `workspace/gateway-client.json` 时，节点统一使用该 VPS 的共享 ASR；此前的 `RIVO_SPEECH_BACKEND_URL` / `RIVO_SPEECH_BACKEND_TOKEN` 不再覆盖这个默认路由。无 VPS 凭据的独立节点仍可显式配置后端，但不会自动下载或加载模型。内部构造时显式提供 URL 仍可用于中转自身访问后端和测试。

已安装自动更新代理的节点在空闲且可更新时获取新版；离线节点上线后更新。忙碌、未知提交状态或更新失败的节点会延后，不中断任务强制升级。旧节点在升级前仍保持旧行为。App 已有语音 UI，升级节点后重新连接或重开 App 刷新能力，首次使用需要麦克风授权。

## E2 与本机兼容入口

E2 独立服务 `/Users/e2/Services/rivo-asr`，模型为 Qwen3-ASR-1.7B-8bit，监听 `127.0.0.1:19092`。`local.rivo-asr` 和 `local.rivo-asr.gateway` 分别管理模型与主动出站连接器，识别服务不开放 E2 公网入站端口。实现与依赖锁位于 `scripts/asr/`；权重、token、gateway.json 留在部署目录。

本机系统管理的 Mac AI 服务仍提供视觉/TTS；ASR 改用 `mac_asr_remote.py` 远程代理，不再导入或加载本机 ASR 模型。私有 `asr-remote.json` 保存兼容入口配置；原文件备份 `mac_agent.py.before-e2-asr`。模型文件保留，未删除。该入口用于既有 QiGuang 等调用，不是 Rivo 节点的路由配置。
