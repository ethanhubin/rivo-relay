# Tailscale 节点更新与发布

默认更新源为 `http://100.124.150.1:18767`。App 在未登录或切换会话节点后仍使用这个发布节点；无网络时明确报错，不自动访问 GitHub。

- `GET /v1/app-updates/latest`：公共版本清单，包含 version、versionCode、notes、size、sha256、apkPath；禁止缓存。
- `GET /v1/app-updates/apks/<sha256>.apk`：仅公开发布目录中的安装包，支持单 Range 下载。无配对令牌，不经过 Codex 文件读取，不提供任意文件路径。
- App 验证路径、长度及 SHA-256 后，通过 FileProvider 打开系统安装界面。没有安装权限时先引导授权，再由用户重新点击安装。

普通“发布新版”只发布节点，不推送 Git、创建 tag 或 GitHub Release。用户明确要求提交/推送时单独执行；仅明确要求发布 Release 时同步 GitHub。

## 发布

递增 versionName/versionCode，构建并验证签名，运行相关测试与 lint。把 APK 复制到 `workspace/artifacts/release-<version>/rivo-relay-<version>-debug.apk`，准备说明后运行：

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
